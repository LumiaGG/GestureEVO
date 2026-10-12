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

#ifndef PPOCRV5_LOGGING_H
#define PPOCRV5_LOGGING_H

#include <android/log.h>

// Unified logging macros for PPOCRv5
// LOGD is disabled in release builds (NDEBUG defined)
// LOGE/LOGW are always enabled for error reporting

#ifdef NDEBUG
#define LOGD(TAG, ...) ((void)0)
#define LOGW(TAG, ...) ((void)0)
#else
#define LOGD(TAG, ...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGW(TAG, ...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#endif

#define LOGE(TAG, ...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#endif  // PPOCRV5_LOGGING_H
