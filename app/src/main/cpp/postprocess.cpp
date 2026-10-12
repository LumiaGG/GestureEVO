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

#include "postprocess.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <limits>
#include <queue>
#include <utility>

#if defined(__ARM_NEON) || defined(__ARM_NEON__)

#include <arm_neon.h>

#define USE_NEON 1
#else
#define USE_NEON 0
#endif

#include "logging.h"

#define TAG "PostProcess"


namespace ppocrv5::postprocess {

    namespace {

        constexpr int kDownsampleFactor = 4;
        constexpr int kMinComponentPixels = 3;
        constexpr int kMaxBoundaryPoints = 200;
        constexpr int kMaxContours = 100;

        constexpr int kNeighborDx4[] = {1, -1, 0, 0};
        constexpr int kNeighborDy4[] = {0, 0, 1, -1};

        inline bool is_valid(int x, int y, int width, int height) {
            return x >= 0 && x < width && y >= 0 && y < height;
        }

        inline int pixel_at(const uint8_t *map, int x, int y, int width) {
            return map[y * width + x];
        }

        inline bool is_boundary_pixel(const uint8_t *map, int x, int y, int width, int height) {
            for (int d = 0; d < 4; ++d) {
                int nx = x + kNeighborDx4[d];
                int ny = y + kNeighborDy4[d];
                if (!is_valid(nx, ny, width, height) || pixel_at(map, nx, ny, width) == 0) {
                    return true;
                }
            }
            return false;
        }

        float cross_product(const Point &o, const Point &a, const Point &b) {
            return (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x);
        }

        std::vector<Point> convex_hull(std::vector<Point> points) {
            size_t n = points.size();
            if (n < 3) return points;

            std::sort(points.begin(), points.end(), [](const Point &a, const Point &b) {
                return a.x < b.x || (a.x == b.x && a.y < b.y);
            });

            std::vector<Point> hull;
            hull.reserve(2 * n);

            for (size_t i = 0; i < n; ++i) {
                while (hull.size() >= 2 &&
                       cross_product(hull[hull.size() - 2], hull[hull.size() - 1], points[i]) <= 0) {
                    hull.pop_back();
                }
                hull.push_back(points[i]);
            }

            size_t lower_size = hull.size();
            for (size_t i = n - 1; i > 0; --i) {
                while (hull.size() > lower_size &&
                       cross_product(hull[hull.size() - 2], hull[hull.size() - 1], points[i - 1]) <= 0) {
                    hull.pop_back();
                }
                hull.push_back(points[i - 1]);
            }

            hull.pop_back();
            return hull;
        }

        float dot_product(float ax, float ay, float bx, float by) {
            return ax * bx + ay * by;
        }

        void project_points_onto_axis(const std::vector<Point> &hull,
                                      float axis_x, float axis_y,
                                      float &min_proj, float &max_proj) {
            min_proj = std::numeric_limits<float>::max();
            max_proj = std::numeric_limits<float>::lowest();

            for (const auto &p: hull) {
                float proj = dot_product(p.x, p.y, axis_x, axis_y);
                min_proj = std::min(min_proj, proj);
                max_proj = std::max(max_proj, proj);
            }
        }

        void compute_aabb(const std::vector<Point> &points,
                          float &min_x, float &max_x, float &min_y, float &max_y) {
            min_x = min_y = std::numeric_limits<float>::max();
            max_x = max_y = std::numeric_limits<float>::lowest();
            for (const auto &p: points) {
                min_x = std::min(min_x, p.x);
                max_x = std::max(max_x, p.x);
                min_y = std::min(min_y, p.y);
                max_y = std::max(max_y, p.y);
            }
        }

        std::vector<Point> subsample_points(const std::vector<Point> &points, size_t max_points) {
            if (points.size() <= max_points) return points;

            std::vector<Point> result;
            result.reserve(max_points);
            float step = static_cast<float>(points.size()) / max_points;
            for (size_t i = 0; i < max_points; ++i) {
                size_t idx = static_cast<size_t>(i * step);
                result.push_back(points[idx]);
            }
            return result;
        }

        void downsample_binary_map(const uint8_t *src, int src_w, int src_h,
                                   uint8_t *dst, int dst_w, int dst_h, int factor) {
#if USE_NEON
            if (factor == 4 && dst_w >= 4) {
                for (int dy = 0; dy < dst_h; ++dy) {
                    int sy_start = dy * factor;
                    int sy_end = std::min(sy_start + factor, src_h);

                    int dx = 0;
                    for (; dx + 4 <= dst_w; dx += 4) {
                        uint8x16_t max_vals = vdupq_n_u8(0);

                        for (int sy = sy_start; sy < sy_end; ++sy) {
                            const uint8_t *row = src + sy * src_w + dx * factor;
                            uint8x16_t vals = vld1q_u8(row);
                            max_vals = vmaxq_u8(max_vals, vals);
                        }

                        uint8_t results[4];
                        uint8_t temp[16];
                        vst1q_u8(temp, max_vals);
                        for (int i = 0; i < 4; ++i) {
                            uint8_t m = temp[i * 4];
                            m = std::max(m, temp[i * 4 + 1]);
                            m = std::max(m, temp[i * 4 + 2]);
                            m = std::max(m, temp[i * 4 + 3]);
                            results[i] = m;
                        }

                        dst[dy * dst_w + dx + 0] = results[0];
                        dst[dy * dst_w + dx + 1] = results[1];
                        dst[dy * dst_w + dx + 2] = results[2];
                        dst[dy * dst_w + dx + 3] = results[3];
                    }

                    for (; dx < dst_w; ++dx) {
                        uint8_t max_val = 0;
                        int sx_start = dx * factor;
                        int sx_end = std::min(sx_start + factor, src_w);

                        for (int sy = sy_start; sy < sy_end; ++sy) {
                            for (int sx = sx_start; sx < sx_end; ++sx) {
                                max_val = std::max(max_val, src[sy * src_w + sx]);
                            }
                        }
                        dst[dy * dst_w + dx] = max_val;
                    }
                }
                return;
            }
#endif
            for (int dy = 0; dy < dst_h; ++dy) {
                for (int dx = 0; dx < dst_w; ++dx) {
                    uint8_t max_val = 0;
                    int sy_start = dy * factor;
                    int sx_start = dx * factor;
                    int sy_end = std::min(sy_start + factor, src_h);
                    int sx_end = std::min(sx_start + factor, src_w);

                    for (int sy = sy_start; sy < sy_end; ++sy) {
                        for (int sx = sx_start; sx < sx_end; ++sx) {
                            max_val = std::max(max_val, src[sy * src_w + sx]);
                        }
                    }
                    dst[dy * dst_w + dx] = max_val;
                }
            }
        }

    }  // namespace

    std::vector<std::vector<Point>> FindContours(const uint8_t *binary_map,
                                                 int width, int height) {
        int ds_width = (width + kDownsampleFactor - 1) / kDownsampleFactor;
        int ds_height = (height + kDownsampleFactor - 1) / kDownsampleFactor;

        std::vector<uint8_t> ds_map(ds_width * ds_height);
        downsample_binary_map(binary_map, width, height, ds_map.data(), ds_width, ds_height, kDownsampleFactor);

        std::vector<std::vector<Point>> contours;
        std::vector<int> labels(ds_width * ds_height, 0);
        int current_label = 0;

        for (int y = 0; y < ds_height; ++y) {
            for (int x = 0; x < ds_width; ++x) {
                if (pixel_at(ds_map.data(), x, y, ds_width) > 0 && labels[y * ds_width + x] == 0) {
                    current_label++;
                    std::vector<Point> boundary;
                    int pixel_count = 0;
                    std::queue<std::pair<int, int>> queue;
                    queue.push({x, y});
                    labels[y * ds_width + x] = current_label;

                    while (!queue.empty()) {
                        auto [cx, cy] = queue.front();
                        queue.pop();
                        pixel_count++;

                        if (is_boundary_pixel(ds_map.data(), cx, cy, ds_width, ds_height)) {
                            boundary.push_back({
                                                       static_cast<float>(cx * kDownsampleFactor +
                                                                          kDownsampleFactor / 2),
                                                       static_cast<float>(cy * kDownsampleFactor +
                                                                          kDownsampleFactor / 2)
                                               });
                        }

                        for (int d = 0; d < 4; ++d) {
                            int nx = cx + kNeighborDx4[d];
                            int ny = cy + kNeighborDy4[d];
                            if (is_valid(nx, ny, ds_width, ds_height) &&
                                pixel_at(ds_map.data(), nx, ny, ds_width) > 0 &&
                                labels[ny * ds_width + nx] == 0) {
                                labels[ny * ds_width + nx] = current_label;
                                queue.push({nx, ny});
                            }
                        }
                    }

                    if (pixel_count >= kMinComponentPixels && boundary.size() >= 4) {
                        contours.push_back(std::move(boundary));
                        if (contours.size() >= kMaxContours) {
                            LOGD(TAG, "FindContours: reached max contour limit (%d)", kMaxContours);
                            goto done;
                        }
                    }
                }
            }
        }
        done:

        LOGD(TAG, "FindContours (optimized): found %d components, %zu valid contours",
             current_label, contours.size());

        return contours;
    }

    RotatedRect MinAreaRect(const std::vector<Point> &contour) {
        if (contour.size() < 3) {
            return {};
        }

        std::vector<Point> points = subsample_points(contour, kMaxBoundaryPoints);

        float min_x, max_x, min_y, max_y;
        compute_aabb(points, min_x, max_x, min_y, max_y);

        float aabb_width = max_x - min_x;
        float aabb_height = max_y - min_y;

        float aspect = std::max(aabb_width, aabb_height) / std::max(1.0f, std::min(aabb_width, aabb_height));
        if (aspect > 2.0f && points.size() > 50) {
            RotatedRect rect;
            rect.center_x = (min_x + max_x) / 2.0f;
            rect.center_y = (min_y + max_y) / 2.0f;
            rect.width = aabb_width;
            rect.height = aabb_height;
            rect.angle = 0.0f;

            if (rect.width < rect.height) {
                std::swap(rect.width, rect.height);
                rect.angle = 90.0f;
            }
            return rect;
        }

        std::vector<Point> hull = convex_hull(std::vector<Point>(points));
        if (hull.size() < 3) {
            return {};
        }

        float min_area = std::numeric_limits<float>::max();
        RotatedRect best_rect;

        size_t n = hull.size();
        for (size_t i = 0; i < n; ++i) {
            size_t j = (i + 1) % n;

            float edge_x = hull[j].x - hull[i].x;
            float edge_y = hull[j].y - hull[i].y;
            float edge_len = std::sqrt(edge_x * edge_x + edge_y * edge_y);

            if (edge_len < 1e-6f) continue;

            float axis1_x = edge_x / edge_len;
            float axis1_y = edge_y / edge_len;
            float axis2_x = -axis1_y;
            float axis2_y = axis1_x;

            float min1, max1, min2, max2;
            project_points_onto_axis(hull, axis1_x, axis1_y, min1, max1);
            project_points_onto_axis(hull, axis2_x, axis2_y, min2, max2);

            float rect_width = max1 - min1;
            float rect_height = max2 - min2;
            float area = rect_width * rect_height;

            if (area < min_area) {
                min_area = area;

                float center_proj1 = (min1 + max1) / 2.0f;
                float center_proj2 = (min2 + max2) / 2.0f;

                best_rect.center_x = center_proj1 * axis1_x + center_proj2 * axis2_x;
                best_rect.center_y = center_proj1 * axis1_y + center_proj2 * axis2_y;
                best_rect.width = rect_width;
                best_rect.height = rect_height;
                best_rect.angle = std::atan2(axis1_y, axis1_x) * 180.0f / static_cast<float>(M_PI);
            }
        }

        if (best_rect.width < best_rect.height) {
            std::swap(best_rect.width, best_rect.height);
            best_rect.angle += 90.0f;
        }

        while (best_rect.angle > 90.0f) best_rect.angle -= 180.0f;
        while (best_rect.angle < -90.0f) best_rect.angle += 180.0f;

        return best_rect;
    }

    std::vector<RotatedRect> FilterAndSortBoxes(
            const std::vector<RotatedRect> &boxes,
            float min_confidence, float min_area) {
        std::vector<RotatedRect> filtered;
        filtered.reserve(boxes.size());

        for (const auto &box: boxes) {
            float area = box.width * box.height;
            if (box.confidence >= min_confidence && area >= min_area) {
                filtered.push_back(box);
            }
        }

        std::sort(filtered.begin(), filtered.end(), [](const RotatedRect &a, const RotatedRect &b) {
            constexpr float kLineThreshold = 20.0f;
            if (std::abs(a.center_y - b.center_y) < kLineThreshold) {
                return a.center_x < b.center_x;
            }
            return a.center_y < b.center_y;
        });

        return filtered;
    }

} // namespace ppocrv5::postprocess
