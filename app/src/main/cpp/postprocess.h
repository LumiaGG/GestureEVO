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

#ifndef PPOCRV5_POSTPROCESS_H
#define PPOCRV5_POSTPROCESS_H

#include <cstdint>
#include <vector>

#include "text_detector.h"


namespace ppocrv5::postprocess {

    struct Point {
        float x = 0.0f;
        float y = 0.0f;
    };

    std::vector<std::vector<Point>> FindContours(const uint8_t *binary_map,
                                                 int width, int height);

    RotatedRect MinAreaRect(const std::vector<Point> &contour);

    std::vector<RotatedRect> FilterAndSortBoxes(
            const std::vector<RotatedRect> &boxes,
            float min_confidence, float min_area);

} // namespace ppocrv5::postprocess


#endif  // PPOCRV5_POSTPROCESS_H
