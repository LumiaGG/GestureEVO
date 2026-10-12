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

#ifndef PPOCRV5_IMAGE_UTILS_H
#define PPOCRV5_IMAGE_UTILS_H

#include <cstdint>

namespace ppocrv5::image_utils {

    void ResizeBilinear(const uint8_t *src, int src_w, int src_h, int src_stride,
                        uint8_t *dst, int dst_w, int dst_h);

    void NormalizeImageNet(const uint8_t *src, int w, int h, int stride, float *dst);

    void NormalizeRecognition(const uint8_t *src, int w, int h, int stride, float *dst);

    void PerspectiveTransform(const uint8_t *src, int src_w, int src_h, int stride,
                              const float *src_points, float *dst, int dst_w, int dst_h);

    void PerspectiveTransformFloat32Raw(const uint8_t *src, int src_w, int src_h, int stride,
                                        const float *src_points, float *dst, int dst_w, int dst_h);

    void PerspectiveTransformUint8(const uint8_t *src, int src_w, int src_h, int stride,
                                   const float *src_points, uint8_t *dst, int dst_w, int dst_h);

}  // namespace ppocrv5::image_utils

#endif  // PPOCRV5_IMAGE_UTILS_H
