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

#ifndef PPOCRV5_TEXT_DETECTOR_H
#define PPOCRV5_TEXT_DETECTOR_H

#include <memory>
#include <string>
#include <vector>

namespace ppocrv5 {

    enum class AcceleratorType;

    struct RotatedRect {
        float center_x = 0.0f;
        float center_y = 0.0f;
        float width = 0.0f;
        float height = 0.0f;
        float angle = 0.0f;
        float confidence = 0.0f;
    };

    class TextDetector {
    public:
        static std::unique_ptr<TextDetector> Create(
                const std::string &model_path,
                AcceleratorType accelerator_type);

        std::vector<RotatedRect> Detect(const uint8_t *image_data,
                                        int width, int height, int stride,
                                        float *detection_time_ms);

        ~TextDetector();

    private:
        TextDetector() = default;

        class Impl;

        std::unique_ptr<Impl> impl_;
    };

}  // namespace ppocrv5

#endif  // PPOCRV5_TEXT_DETECTOR_H
