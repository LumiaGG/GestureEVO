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

#ifndef PPOCRV5_LITERT_ENV_MANAGER_H
#define PPOCRV5_LITERT_ENV_MANAGER_H

#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "litert/c/litert_any.h"
#include "litert/c/litert_common.h"
#include "litert/c/litert_environment.h"
#include "litert/c/litert_environment_options.h"
#include "litert/c/litert_options.h"

#include "logging.h"

#define TAG_ENV "LiteRtEnvManager"

namespace ppocrv5 {

/**
 * Singleton manager for LiteRT environment with compilation caching.
 *
 * Based on official documentation:
 * https://ai.google.dev/edge/litert/next/android_cpp
 *
 * Usage:
 *   LiteRtEnvManager::GetInstance().SetCacheDirectory(cache_dir);
 *   auto env = LiteRtEnvManager::GetInstance().GetEnvironment();
 */
    class LiteRtEnvManager {
    public:
        static LiteRtEnvManager &GetInstance() {
            static LiteRtEnvManager instance;
            return instance;
        }

        /**
         * Set cache directory before first use.
         * Call from JNI with app cache directory.
         *
         * From docs: Environment::OptionTag::CompilerCacheDir
         */
        void SetCacheDirectory(const std::string &cache_dir) {
            std::lock_guard<std::mutex> lock(mutex_);
            cache_dir_ = cache_dir;
            LOGD(TAG_ENV, "Compiler cache directory set: %s", cache_dir.c_str());
        }

        /**
         * Get or create the shared LiteRT environment.
         *
         * From docs:
         *   LITERT_ASSIGN_OR_RETURN(auto env, Environment::Create({}));
         */
        LiteRtEnvironment GetEnvironment() {
            std::lock_guard<std::mutex> lock(mutex_);

            if (env_ != nullptr) {
                return env_;
            }

            std::vector<LiteRtEnvOption> options;

            if (!cache_dir_.empty()) {
                LiteRtEnvOption cache_option;
                cache_option.tag = kLiteRtEnvOptionTagCompilerCacheDir;
                cache_option.value.type = kLiteRtAnyTypeString;
                cache_option.value.str_value = cache_dir_.c_str();
                options.push_back(cache_option);
                LOGD(TAG_ENV, "Compilation caching enabled: %s", cache_dir_.c_str());
            }

            LiteRtStatus status = LiteRtCreateEnvironment(
                    options.size(),
                    options.empty() ? nullptr : options.data(),
                    &env_
            );

            if (status != kLiteRtStatusOk) {
                LOGE(TAG_ENV, "Failed to create LiteRT environment with options: %d", status);
                // Fallback: create without options
                status = LiteRtCreateEnvironment(0, nullptr, &env_);
                if (status != kLiteRtStatusOk) {
                    LOGE(TAG_ENV, "Failed to create LiteRT environment: %d", status);
                    return nullptr;
                }
            }

            LOGD(TAG_ENV, "LiteRT environment created successfully");
            return env_;
        }

        /**
         * Create optimized options for the given accelerator.
         *
         * From docs:
         *   LITERT_ASSIGN_OR_RETURN(auto compiled_model,
         *       CompiledModel::Create(env, model, kLiteRtHwAcceleratorGpu));
         */
        static LiteRtOptions CreateOptions(LiteRtHwAcceleratorSet accelerator) {
            LiteRtOptions options = nullptr;
            LiteRtStatus status = LiteRtCreateOptions(&options);
            if (status != kLiteRtStatusOk) {
                LOGE(TAG_ENV, "Failed to create options: %d", status);
                return nullptr;
            }

            status = LiteRtSetOptionsHardwareAccelerators(options, accelerator);
            if (status != kLiteRtStatusOk) {
                LOGE(TAG_ENV, "Failed to set hardware accelerators: %d", status);
                LiteRtDestroyOptions(options);
                return nullptr;
            }

            return options;
        }

        /**
         * Shutdown and release resources.
         * Call on app termination.
         */
        void Shutdown() {
            std::lock_guard<std::mutex> lock(mutex_);
            if (env_ != nullptr) {
                LiteRtDestroyEnvironment(env_);
                env_ = nullptr;
                LOGD(TAG_ENV, "LiteRT environment destroyed");
            }
        }

        ~LiteRtEnvManager() {
            Shutdown();
        }

    private:
        LiteRtEnvManager() = default;

        LiteRtEnvManager(const LiteRtEnvManager &) = delete;

        LiteRtEnvManager &operator=(const LiteRtEnvManager &) = delete;

        std::mutex mutex_;
        LiteRtEnvironment env_ = nullptr;
        std::string cache_dir_;
    };

}  // namespace ppocrv5

#undef TAG_ENV

#endif  // PPOCRV5_LITERT_ENV_MANAGER_H
