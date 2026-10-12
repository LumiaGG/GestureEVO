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

#include "image_utils.h"

#include <algorithm>
#include <cmath>
#include <cstring>

#if defined(__ARM_NEON) || defined(__ARM_NEON__)

#include <arm_neon.h>

#define USE_NEON 1
#else
#define USE_NEON 0
#endif

namespace ppocrv5::image_utils {

    namespace {

        constexpr float kDetMean[] = {0.485f * 255.f, 0.456f * 255.f, 0.406f * 255.f};
        constexpr float kDetStd[] = {1.f / (0.229f * 255.f), 1.f / (0.224f * 255.f), 1.f / (0.225f * 255.f)};
        constexpr float kRecMean = 127.5f;
        constexpr float kRecScale = 1.f / 127.5f;

        inline float Clamp(float v, float lo, float hi) {
            return std::max(lo, std::min(v, hi));
        }

        inline void BilinearSample(const uint8_t *src, int src_w, int src_h, int stride,
                                   float x, float y, uint8_t *out) {
            int x0 = static_cast<int>(x);
            int y0 = static_cast<int>(y);
            int x1 = std::min(x0 + 1, src_w - 1);
            int y1 = std::min(y0 + 1, src_h - 1);

            float dx = x - x0;
            float dy = y - y0;
            float w00 = (1.0f - dx) * (1.0f - dy);
            float w01 = dx * (1.0f - dy);
            float w10 = (1.0f - dx) * dy;
            float w11 = dx * dy;

            const uint8_t *row0 = src + y0 * stride;
            const uint8_t *row1 = src + y1 * stride;

            for (int c = 0; c < 3; ++c) {
                float v = row0[x0 * 4 + c] * w00 + row0[x1 * 4 + c] * w01 +
                          row1[x0 * 4 + c] * w10 + row1[x1 * 4 + c] * w11;
                out[c] = static_cast<uint8_t>(Clamp(v, 0.f, 255.f));
            }
            out[3] = 255;
        }

        struct Homography {
            float h[9];

            bool ComputeFromQuad(const float *pts, int dst_w, int dst_h) {
                float x0 = pts[0], y0 = pts[1];
                float x1 = pts[2], y1 = pts[3];
                float x2 = pts[4], y2 = pts[5];
                float x3 = pts[6], y3 = pts[7];

                float dw = static_cast<float>(dst_w - 1);
                float dh = static_cast<float>(dst_h - 1);

                float A[8][8] = {
                        {0,  0,  1, 0,  0,  0, 0,        0},
                        {0,  0,  0, 0,  0,  1, 0,        0},
                        {dw, 0,  1, 0,  0,  0, -dw * x1, 0},
                        {0,  0,  0, dw, 0,  1, -dw * y1, 0},
                        {dw, dh, 1, 0,  0,  0, -dw * x2, -dh * x2},
                        {0,  0,  0, dw, dh, 1, -dw * y2, -dh * y2},
                        {0,  dh, 1, 0,  0,  0, 0,        -dh * x3},
                        {0,  0,  0, 0,  dh, 1, 0,        -dh * y3}
                };
                float b[8] = {x0, y0, x1, y1, x2, y2, x3, y3};

                for (int i = 0; i < 8; ++i) {
                    int max_row = i;
                    float max_val = std::abs(A[i][i]);
                    for (int k = i + 1; k < 8; ++k) {
                        if (std::abs(A[k][i]) > max_val) {
                            max_val = std::abs(A[k][i]);
                            max_row = k;
                        }
                    }

                    if (max_row != i) {
                        std::swap(b[i], b[max_row]);
                        for (int j = 0; j < 8; ++j) std::swap(A[i][j], A[max_row][j]);
                    }

                    if (std::abs(A[i][i]) < 1e-10f) return false;

                    for (int k = i + 1; k < 8; ++k) {
                        float factor = A[k][i] / A[i][i];
                        b[k] -= factor * b[i];
                        for (int j = i; j < 8; ++j) A[k][j] -= factor * A[i][j];
                    }
                }

                for (int i = 7; i >= 0; --i) {
                    h[i] = b[i];
                    for (int j = i + 1; j < 8; ++j) h[i] -= A[i][j] * h[j];
                    h[i] /= A[i][i];
                }
                h[8] = 1.0f;
                return true;
            }

            bool Transform(int dx, int dy, float &sx, float &sy) const {
                float w = h[6] * dx + h[7] * dy + h[8];
                if (std::abs(w) < 1e-10f) return false;
                sx = (h[0] * dx + h[1] * dy + h[2]) / w;
                sy = (h[3] * dx + h[4] * dy + h[5]) / w;
                return true;
            }
        };

        struct AffineTransform {
            float a00, a01, a10, a11, tx, ty;

            void ComputeFromQuad(const float *pts, int dst_w, int dst_h) {
                float inv_w = 1.0f / std::max(dst_w - 1, 1);
                float inv_h = 1.0f / std::max(dst_h - 1, 1);
                tx = pts[0];
                ty = pts[1];
                a00 = (pts[2] - pts[0]) * inv_w;
                a01 = (pts[6] - pts[0]) * inv_h;
                a10 = (pts[3] - pts[1]) * inv_w;
                a11 = (pts[7] - pts[1]) * inv_h;
            }

            void Transform(int dx, int dy, float &sx, float &sy) const {
                sx = tx + a00 * dx + a01 * dy;
                sy = ty + a10 * dx + a11 * dy;
            }
        };

        inline bool IsNearlyParallelogram(const float *pts) {
            float diag1_x = pts[4] - pts[0], diag1_y = pts[5] - pts[1];
            float diag2_x = pts[6] - pts[2], diag2_y = pts[7] - pts[3];
            return std::abs(diag1_x - diag2_x) + std::abs(diag1_y - diag2_y) < 10.0f;
        }

        template<typename OutputFunc>
        void WarpPerspectiveImpl(const uint8_t *src, int src_w, int src_h, int stride,
                                 const float *pts, int dst_w, int dst_h, OutputFunc output) {
            if (IsNearlyParallelogram(pts)) {
                AffineTransform affine;
                affine.ComputeFromQuad(pts, dst_w, dst_h);

                for (int dy = 0; dy < dst_h; ++dy) {
                    float base_sx = affine.tx + affine.a01 * dy;
                    float base_sy = affine.ty + affine.a11 * dy;

                    for (int dx = 0; dx < dst_w; ++dx) {
                        float sx = base_sx + affine.a00 * dx;
                        float sy = base_sy + affine.a10 * dx;
                        output(dx, dy, src, src_w, src_h, stride, sx, sy);
                    }
                }
            } else {
                Homography H;
                if (!H.ComputeFromQuad(pts, dst_w, dst_h)) {
                    return;
                }

                for (int dy = 0; dy < dst_h; ++dy) {
                    for (int dx = 0; dx < dst_w; ++dx) {
                        float sx, sy;
                        if (H.Transform(dx, dy, sx, sy)) {
                            output(dx, dy, src, src_w, src_h, stride, sx, sy);
                        }
                    }
                }
            }
        }

    }  // namespace

    void ResizeBilinear(const uint8_t *src, int src_w, int src_h, int src_stride,
                        uint8_t *dst, int dst_w, int dst_h) {
        if (src_w == dst_w && src_h == dst_h) {
            int dst_stride = dst_w * 4;
            for (int y = 0; y < dst_h; ++y) {
                std::memcpy(dst + y * dst_stride, src + y * src_stride, dst_stride);
            }
            return;
        }

        float scale_x = static_cast<float>(src_w) / dst_w;
        float scale_y = static_cast<float>(src_h) / dst_h;

        for (int y = 0; y < dst_h; ++y) {
            float src_y = Clamp((y + 0.5f) * scale_y - 0.5f, 0.f, src_h - 1.f);
            int y0 = static_cast<int>(src_y);
            int y1 = std::min(y0 + 1, src_h - 1);
            float dy = src_y - y0;

            const uint8_t *row0 = src + y0 * src_stride;
            const uint8_t *row1 = src + y1 * src_stride;
            uint8_t *dst_row = dst + y * dst_w * 4;

            for (int x = 0; x < dst_w; ++x) {
                float src_x = Clamp((x + 0.5f) * scale_x - 0.5f, 0.f, src_w - 1.f);
                int x0 = static_cast<int>(src_x);
                int x1 = std::min(x0 + 1, src_w - 1);
                float dx = src_x - x0;

                float w00 = (1.0f - dx) * (1.0f - dy);
                float w01 = dx * (1.0f - dy);
                float w10 = (1.0f - dx) * dy;
                float w11 = dx * dy;

                uint8_t *out = dst_row + x * 4;
                for (int c = 0; c < 3; ++c) {
                    float v = row0[x0 * 4 + c] * w00 + row0[x1 * 4 + c] * w01 +
                              row1[x0 * 4 + c] * w10 + row1[x1 * 4 + c] * w11;
                    out[c] = static_cast<uint8_t>(Clamp(v, 0.f, 255.f));
                }
                out[3] = 255;
            }
        }
    }

    void NormalizeImageNet(const uint8_t *src, int w, int h, int stride, float *dst) {
        for (int y = 0; y < h; ++y) {
            const uint8_t *row = src + y * stride;
            float *dst_row = dst + y * w * 3;

            for (int x = 0; x < w; ++x) {
                int si = x * 4;
                int di = x * 3;
                dst_row[di + 0] = (row[si + 0] - kDetMean[0]) * kDetStd[0];
                dst_row[di + 1] = (row[si + 1] - kDetMean[1]) * kDetStd[1];
                dst_row[di + 2] = (row[si + 2] - kDetMean[2]) * kDetStd[2];
            }
        }
    }

    void NormalizeRecognition(const uint8_t *src, int w, int h, int stride, float *dst) {
        for (int y = 0; y < h; ++y) {
            const uint8_t *row = src + y * stride;
            float *dst_row = dst + y * w * 3;

            for (int x = 0; x < w; ++x) {
                int si = x * 4;
                int di = x * 3;
                dst_row[di + 0] = (row[si + 0] - kRecMean) * kRecScale;
                dst_row[di + 1] = (row[si + 1] - kRecMean) * kRecScale;
                dst_row[di + 2] = (row[si + 2] - kRecMean) * kRecScale;
            }
        }
    }

    void PerspectiveTransform(const uint8_t *src, int src_w, int src_h, int stride,
                              const float *src_points, float *dst, int dst_w, int dst_h) {
        std::memset(dst, 0, dst_w * dst_h * 3 * sizeof(float));

        auto output = [dst, dst_w](int dx, int dy, const uint8_t *src, int src_w, int src_h,
                                   int stride, float sx, float sy) {
            if (sx < 0 || sx >= src_w - 1 || sy < 0 || sy >= src_h - 1) return;

            int x0 = static_cast<int>(sx), y0 = static_cast<int>(sy);
            int x1 = x0 + 1, y1 = y0 + 1;
            float fx = sx - x0, fy = sy - y0;

            const uint8_t *r0 = src + y0 * stride;
            const uint8_t *r1 = src + y1 * stride;
            float *out = dst + (dy * dst_w + dx) * 3;

            for (int c = 0; c < 3; ++c) {
                float v = r0[x0 * 4 + c] * (1 - fx) * (1 - fy) + r0[x1 * 4 + c] * fx * (1 - fy) +
                          r1[x0 * 4 + c] * (1 - fx) * fy + r1[x1 * 4 + c] * fx * fy;
                out[c] = (v - kRecMean) * kRecScale;
            }
        };

        WarpPerspectiveImpl(src, src_w, src_h, stride, src_points, dst_w, dst_h, output);
    }

    void PerspectiveTransformFloat32Raw(const uint8_t *src, int src_w, int src_h, int stride,
                                        const float *src_points, float *dst, int dst_w, int dst_h) {
        std::memset(dst, 0, dst_w * dst_h * 3 * sizeof(float));

        auto output = [dst, dst_w](int dx, int dy, const uint8_t *src, int src_w, int src_h,
                                   int stride, float sx, float sy) {
            if (sx < 0 || sx >= src_w - 1 || sy < 0 || sy >= src_h - 1) return;

            int x0 = static_cast<int>(sx), y0 = static_cast<int>(sy);
            int x1 = x0 + 1, y1 = y0 + 1;
            float fx = sx - x0, fy = sy - y0;

            const uint8_t *r0 = src + y0 * stride;
            const uint8_t *r1 = src + y1 * stride;
            float *out = dst + (dy * dst_w + dx) * 3;

            for (int c = 0; c < 3; ++c) {
                float v = r0[x0 * 4 + c] * (1 - fx) * (1 - fy) + r0[x1 * 4 + c] * fx * (1 - fy) +
                          r1[x0 * 4 + c] * (1 - fx) * fy + r1[x1 * 4 + c] * fx * fy;
                out[c] = (v - 127.5f) / 127.5f;
            }
        };

        WarpPerspectiveImpl(src, src_w, src_h, stride, src_points, dst_w, dst_h, output);
    }

    void PerspectiveTransformUint8(const uint8_t *src, int src_w, int src_h, int stride,
                                   const float *src_points, uint8_t *dst, int dst_w, int dst_h) {
        std::memset(dst, 0, dst_w * dst_h * 3);

        auto output = [dst, dst_w](int dx, int dy, const uint8_t *src, int src_w, int src_h,
                                   int stride, float sx, float sy) {
            if (sx < 0 || sx >= src_w - 1 || sy < 0 || sy >= src_h - 1) return;

            int x0 = static_cast<int>(sx), y0 = static_cast<int>(sy);
            int x1 = x0 + 1, y1 = y0 + 1;
            float fx = sx - x0, fy = sy - y0;

            const uint8_t *r0 = src + y0 * stride;
            const uint8_t *r1 = src + y1 * stride;
            uint8_t *out = dst + (dy * dst_w + dx) * 3;

            for (int c = 0; c < 3; ++c) {
                float v = r0[x0 * 4 + c] * (1 - fx) * (1 - fy) + r0[x1 * 4 + c] * fx * (1 - fy) +
                          r1[x0 * 4 + c] * (1 - fx) * fy + r1[x1 * 4 + c] * fx * fy;
                out[c] = static_cast<uint8_t>(Clamp(v, 0.f, 255.f));
            }
        };

        WarpPerspectiveImpl(src, src_w, src_h, stride, src_points, dst_w, dst_h, output);
    }

}  // namespace ppocrv5::image_utils
