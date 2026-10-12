/*
 * Copyright (C) 2025 Fleey
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "ocr_engine.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <numeric>

#include "litert_config.h"
#include "logging.h"

#define TAG "OcrEngine"

namespace ppocrv5 {

    namespace {

        constexpr AcceleratorType kFallbackChain[] = {
                AcceleratorType::kCpu,
        };
        constexpr int kFallbackChainSize = 1;
        constexpr int kWarmupIterations = 0;
        constexpr int kWarmupImageSize = 128;

        constexpr float kMinBoxArea = 100.0f;
        constexpr float kMinConfidenceThreshold = 0.0f;
        constexpr int kRecInputHeight = 48;
        constexpr int kRecInputWidth320 = 320;
        constexpr int kRecInputWidth640 = 640;

        int GetFallbackStartIndex(AcceleratorType requested) {
            return 0;
        }

        const char *AcceleratorName(AcceleratorType type) {
            return "CPU";
        }

        int EstimateRecognitionWidth(const RotatedRect &box) {
            const float src_width = std::max(box.width, box.height);
            const float src_height = std::max(std::min(box.width, box.height), 1.0f);
            return static_cast<int>(std::round(kRecInputHeight * (src_width / src_height)));
        }

    }  // namespace

    std::unique_ptr<OcrEngine> OcrEngine::Create(
            const std::string &det_model_path,
            const std::string &rec_model_320_path,
            const std::string &rec_model_640_path,
            const std::string &keys_path,
            AcceleratorType accelerator_type) {

        auto engine = std::unique_ptr<OcrEngine>(new OcrEngine());
        int start_index = GetFallbackStartIndex(accelerator_type);

        for (int i = start_index; i < kFallbackChainSize; ++i) {
            AcceleratorType current_accelerator = kFallbackChain[i];
            LOGD(TAG, "Attempting to initialize with %s accelerator",
                 AcceleratorName(current_accelerator));

            auto detector = TextDetector::Create(det_model_path, current_accelerator);
            if (!detector) {
                LOGD(TAG, "TextDetector failed with %s, trying next",
                     AcceleratorName(current_accelerator));
                continue;
            }

            auto recognizer_320 = TextRecognizer::Create(rec_model_320_path, keys_path,
                                                         current_accelerator, kRecInputWidth320);
            if (!recognizer_320) {
                LOGD(TAG, "TextRecognizer 320 failed with %s, trying next",
                     AcceleratorName(current_accelerator));
                continue;
            }

            auto recognizer_640 = TextRecognizer::Create(rec_model_640_path, keys_path,
                                                         current_accelerator, kRecInputWidth640);
            if (!recognizer_640) {
                LOGD(TAG, "TextRecognizer 640 failed with %s, trying next",
                     AcceleratorName(current_accelerator));
                continue;
            }

            engine->detector_ = std::move(detector);
            engine->recognizer_320_ = std::move(recognizer_320);
            engine->recognizer_640_ = std::move(recognizer_640);
            engine->active_accelerator_ = current_accelerator;

            LOGD(TAG, "OcrEngine initialized with %s accelerator",
                 AcceleratorName(current_accelerator));

            engine->WarmUp();
            return engine;
        }

        LOGE(TAG, "Failed to initialize OcrEngine with any accelerator");
        return nullptr;
    }

    std::vector<OcrResult> OcrEngine::Process(const uint8_t *image_data,
                                              int width, int height, int stride) {
        if (!detector_ || !recognizer_320_ || !recognizer_640_) {
            LOGE(TAG, "OcrEngine not properly initialized");
            return {};
        }

        auto total_start = std::chrono::high_resolution_clock::now();

        float detection_time_ms = 0.0f;
        auto filtered_boxes = DetectAndFilterTextBoxes(
                image_data, width, height, stride, &detection_time_ms);
        benchmark_.detection_time_ms = detection_time_ms;

        if (filtered_boxes.empty()) {
            auto total_end = std::chrono::high_resolution_clock::now();
            benchmark_.total_time_ms = std::chrono::duration_cast<std::chrono::microseconds>(
                    total_end - total_start).count() / 1000.0f;
            benchmark_.recognition_time_ms = 0.0f;
            benchmark_.fps = (benchmark_.total_time_ms > 0.0f) ? (1000.0f / benchmark_.total_time_ms) : 0.0f;
            return {};
        }

        std::vector<OcrResult> results;
        results.reserve(filtered_boxes.size());

        auto rec_start = std::chrono::high_resolution_clock::now();

        for (const auto &box: filtered_boxes) {
            float rec_time_ms = 0.0f;
            const int estimated_width = EstimateRecognitionWidth(box);
            auto *recognizer = (estimated_width > kRecInputWidth320)
                               ? recognizer_640_.get()
                               : recognizer_320_.get();
            auto rec_result = recognizer->Recognize(image_data, width, height, stride,
                                                    box, &rec_time_ms);

            if (!rec_result.text.empty() && rec_result.confidence >= kMinConfidenceThreshold) {
                OcrResult result;
                result.text = std::move(rec_result.text);
                result.confidence = rec_result.confidence;
                result.box = box;
                results.push_back(std::move(result));
            }
        }

        auto rec_end = std::chrono::high_resolution_clock::now();
        benchmark_.recognition_time_ms = std::chrono::duration_cast<std::chrono::microseconds>(
                rec_end - rec_start).count() / 1000.0f;

        auto total_end = std::chrono::high_resolution_clock::now();
        benchmark_.total_time_ms = std::chrono::duration_cast<std::chrono::microseconds>(
                total_end - total_start).count() / 1000.0f;
        benchmark_.fps = (benchmark_.total_time_ms > 0.0f) ? (1000.0f / benchmark_.total_time_ms) : 0.0f;

        LOGD(TAG, "OCR: %zu/%zu results, det=%.1fms, rec=%.1fms (%.1fms/box), total=%.1fms",
             results.size(), filtered_boxes.size(),
             benchmark_.detection_time_ms, benchmark_.recognition_time_ms,
             filtered_boxes.size() > 0 ? benchmark_.recognition_time_ms / filtered_boxes.size() : 0.0f,
             benchmark_.total_time_ms);

        return results;
    }

    OcrResult OcrEngine::RecognizeTextBox(const uint8_t *image_data,
                                         int width, int height, int stride) {
        if (!image_data || width < 2 || height < 2 ||
            !recognizer_320_ || !recognizer_640_) {
            return {};
        }

        // The caller supplies a detected line crop; no detector inference is needed.
        RotatedRect box;
        box.center_x = width / 2.0f;
        box.center_y = height / 2.0f;
        box.width = static_cast<float>(width);
        box.height = static_cast<float>(height);
        auto *recognizer = EstimateRecognitionWidth(box) > kRecInputWidth320
                           ? recognizer_640_.get() : recognizer_320_.get();
        float recognition_time_ms = 0.0f;
        auto result = recognizer->Recognize(image_data, width, height, stride,
                                             box, &recognition_time_ms);
        benchmark_.detection_time_ms = 0.0f;
        benchmark_.recognition_time_ms = recognition_time_ms;
        benchmark_.total_time_ms = recognition_time_ms;
        benchmark_.fps = recognition_time_ms > 0.0f ? 1000.0f / recognition_time_ms : 0.0f;
        return {std::move(result.text), result.confidence, box};
    }

    std::vector<RotatedRect> OcrEngine::DetectTextBoxes(const uint8_t *image_data,
                                                        int width, int height, int stride) {
        if (!detector_) {
            LOGE(TAG, "Text detector is not initialized");
            return {};
        }

        auto total_start = std::chrono::high_resolution_clock::now();
        float detection_time_ms = 0.0f;
        auto boxes = DetectAndFilterTextBoxes(
                image_data, width, height, stride, &detection_time_ms);

        auto total_end = std::chrono::high_resolution_clock::now();
        benchmark_.detection_time_ms = detection_time_ms;
        benchmark_.recognition_time_ms = 0.0f;
        benchmark_.total_time_ms = std::chrono::duration_cast<std::chrono::microseconds>(
                total_end - total_start).count() / 1000.0f;
        benchmark_.fps = benchmark_.total_time_ms > 0.0f
                         ? 1000.0f / benchmark_.total_time_ms
                         : 0.0f;

        LOGD(TAG, "Detection only: %zu boxes, det=%.1fms, total=%.1fms",
             boxes.size(), benchmark_.detection_time_ms, benchmark_.total_time_ms);
        return boxes;
    }

    std::vector<RotatedRect> OcrEngine::DetectAndFilterTextBoxes(
            const uint8_t *image_data, int width, int height, int stride,
            float *detection_time_ms) {
        auto boxes = detector_->Detect(image_data, width, height, stride, detection_time_ms);
        boxes.erase(
                std::remove_if(boxes.begin(), boxes.end(), [](const RotatedRect &box) {
                    return box.width * box.height < kMinBoxArea;
                }),
                boxes.end());
        return boxes;
    }

    Benchmark OcrEngine::GetBenchmark() const {
        return benchmark_;
    }

    AcceleratorType OcrEngine::GetActiveAccelerator() const {
        return active_accelerator_;
    }

    void OcrEngine::WarmUp() {
        LOGD(TAG, "Starting warm-up (%d iterations)...", kWarmupIterations);

        std::vector<uint8_t> dummy_image(kWarmupImageSize * kWarmupImageSize * 4, 128);
        for (int i = 0; i < kWarmupImageSize * kWarmupImageSize; ++i) {
            dummy_image[i * 4 + 0] = static_cast<uint8_t>((i * 7) % 256);
            dummy_image[i * 4 + 1] = static_cast<uint8_t>((i * 11) % 256);
            dummy_image[i * 4 + 2] = static_cast<uint8_t>((i * 13) % 256);
            dummy_image[i * 4 + 3] = 255;
        }

        for (int iter = 0; iter < kWarmupIterations; ++iter) {
            float detection_time_ms = 0.0f;
            detector_->Detect(dummy_image.data(), kWarmupImageSize, kWarmupImageSize,
                              kWarmupImageSize * 4, &detection_time_ms);
        }

        LOGD(TAG, "Warm-up completed (accelerator: %s)", AcceleratorName(active_accelerator_));
    }

}  // namespace ppocrv5
