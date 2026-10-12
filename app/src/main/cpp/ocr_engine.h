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

#ifndef PPOCRV5_OCR_ENGINE_H
#define PPOCRV5_OCR_ENGINE_H

#include <memory>
#include <string>
#include <vector>

#include "text_detector.h"
#include "text_recognizer.h"

namespace ppocrv5 {

    /**
     * Hardware acceleration is intentionally disabled for the built-in OCR path.
     */
    enum class AcceleratorType {
        kCpu = 1,  // CPU inference
    };

    struct Benchmark {
        float detection_time_ms = 0.0f;
        float recognition_time_ms = 0.0f;
        float total_time_ms = 0.0f;
        float fps = 0.0f;
    };

    struct OcrResult {
        std::string text;
        float confidence;
        RotatedRect box;
    };

    class OcrEngine {
    public:
        static std::unique_ptr<OcrEngine> Create(
                const std::string &det_model_path,
                const std::string &rec_model_320_path,
                const std::string &rec_model_640_path,
                const std::string &keys_path,
                AcceleratorType accelerator_type);

        std::vector<OcrResult> Process(const uint8_t *image_data,
                                       int width, int height, int stride);

        std::vector<RotatedRect> DetectTextBoxes(const uint8_t *image_data,
                                                 int width, int height, int stride);

        Benchmark GetBenchmark() const;

        OcrResult RecognizeTextBox(const uint8_t *image_data,
                                   int width, int height, int stride);

        AcceleratorType GetActiveAccelerator() const;

    private:
        OcrEngine() = default;

        void WarmUp();

        std::vector<RotatedRect> DetectAndFilterTextBoxes(const uint8_t *image_data,
                                                          int width, int height, int stride,
                                                          float *detection_time_ms);

        std::unique_ptr<TextDetector> detector_;
        std::unique_ptr<TextRecognizer> recognizer_320_;
        std::unique_ptr<TextRecognizer> recognizer_640_;
        Benchmark benchmark_;
        AcceleratorType active_accelerator_ = AcceleratorType::kCpu;
    };

}  // namespace ppocrv5

#endif  // PPOCRV5_OCR_ENGINE_H
