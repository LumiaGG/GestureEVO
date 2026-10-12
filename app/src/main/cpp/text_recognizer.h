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

#ifndef PPOCRV5_TEXT_RECOGNIZER_H
#define PPOCRV5_TEXT_RECOGNIZER_H

#include <memory>
#include <string>
#include <vector>

#include "text_detector.h"

namespace ppocrv5 {

    enum class AcceleratorType;

    struct RecognitionResult {
        std::string text;
        float confidence = 0.0f;
    };

    class TextRecognizer {
    public:
        static std::unique_ptr<TextRecognizer> Create(
                const std::string &model_path,
                const std::string &keys_path,
                AcceleratorType accelerator_type,
                int input_width);

        RecognitionResult Recognize(const uint8_t *image_data,
                                    int width, int height, int stride,
                                    const RotatedRect &box,
                                    float *recognition_time_ms);

        ~TextRecognizer();

    private:
        TextRecognizer() = default;

        class Impl;

        std::unique_ptr<Impl> impl_;
    };

}  // namespace ppocrv5

#endif  // PPOCRV5_TEXT_RECOGNIZER_H
