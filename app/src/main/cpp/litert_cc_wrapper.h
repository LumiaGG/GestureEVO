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

#ifndef PPOCRV5_LITERT_CC_WRAPPER_H
#define PPOCRV5_LITERT_CC_WRAPPER_H

/**
 * LiteRT C++ API Wrapper for PPOCRv5
 *
 * This header provides a modern C++ interface to LiteRT CompiledModel API,
 * following the official patterns from:
 * - https://ai.google.dev/edge/litert/next/android_cpp
 * - https://ai.google.dev/edge/litert/next/gpu
 */

#include <string>
#include <vector>

#include "litert/cc/litert_compiled_model.h"
#include "litert/cc/litert_environment.h"
#include "litert/cc/litert_expected.h"
#include "litert/cc/litert_macros.h"
#include "litert/cc/litert_model.h"
#include "litert/cc/litert_options.h"
#include "litert/cc/litert_tensor_buffer.h"

#include "logging.h"

#define TAG_WRAPPER "LiteRtWrapper"

namespace ppocrv5 {

/**
 * Hardware accelerator types matching LiteRT API.
 */
    enum class HwAccelerator {
        kCpu = 0,
        kGpu = 1,
        kNpu = 2,
    };

/**
 * Convert to LiteRT hardware accelerator type.
 */
    inline litert::HwAccelerators ToLiteRtHwAccelerator(HwAccelerator type) {
        switch (type) {
            case HwAccelerator::kGpu:
                return litert::HwAccelerators::kGpu;
            case HwAccelerator::kNpu:
                return litert::HwAccelerators::kNpu;
            case HwAccelerator::kCpu:
            default:
                return litert::HwAccelerators::kCpu;
        }
    }

/**
 * Simplified model wrapper using LiteRT C++ CompiledModel API.
 *
 * Example usage:
 *
 *   // 1. Create environment
 *   LITERT_ASSIGN_OR_RETURN(auto env, Environment::Create({}));
 *
 *   // 2. Create compiled model targeting GPU
 *   LITERT_ASSIGN_OR_RETURN(auto compiled_model,
 *       CompiledModel::Create(env, model, kLiteRtHwAcceleratorGpu));
 *
 *   // 3. Create input/output buffers
 *   LITERT_ASSIGN_OR_RETURN(auto input_buffers, compiled_model.CreateInputBuffers());
 *   LITERT_ASSIGN_OR_RETURN(auto output_buffers, compiled_model.CreateOutputBuffers());
 *
 *   // 4. Fill input data
 *   input_buffers[0].Write<float>(absl::MakeConstSpan(cpu_data, data_size));
 *
 *   // 5. Execute
 *   compiled_model.Run(input_buffers, output_buffers);
 *
 *   // 6. Read output
 *   std::vector<float> data(output_size);
 *   output_buffers[0].Read<float>(absl::MakeSpan(data));
 */
    class CompiledModelWrapper {
    public:
        CompiledModelWrapper() = default;

        ~CompiledModelWrapper() = default;

        // Non-copyable, movable
        CompiledModelWrapper(const CompiledModelWrapper &) = delete;

        CompiledModelWrapper &operator=(const CompiledModelWrapper &) = delete;

        CompiledModelWrapper(CompiledModelWrapper &&) = default;

        CompiledModelWrapper &operator=(CompiledModelWrapper &&) = default;

        /**
         * Create a compiled model from file with specified accelerator.
         *
         * @param env LiteRT environment (must outlive this object)
         * @param model_path Path to .tflite model file
         * @param accelerator Hardware accelerator to use
         * @return Expected<CompiledModelWrapper> or error
         */
        static litert::Expected <CompiledModelWrapper> Create(
                litert::Environment &env,
                const std::string &model_path,
                HwAccelerator accelerator) {

            CompiledModelWrapper wrapper;

            auto result = litert::CompiledModel::Create(
                    env, model_path, ToLiteRtHwAccelerator(accelerator));

            if (!result) {
                LOGE(TAG_WRAPPER, "Failed to create CompiledModel: %s",
                     result.Error().Message().c_str());
                return result.Error();
            }

            wrapper.compiled_model_ = std::move(*result);
            wrapper.accelerator_ = accelerator;

            // Pre-create input/output buffers for efficiency
            auto input_result = wrapper.compiled_model_.CreateInputBuffers();
            if (!input_result) {
                LOGE(TAG_WRAPPER, "Failed to create input buffers");
                return input_result.Error();
            }
            wrapper.input_buffers_ = std::move(*input_result);

            auto output_result = wrapper.compiled_model_.CreateOutputBuffers();
            if (!output_result) {
                LOGE(TAG_WRAPPER, "Failed to create output buffers");
                return output_result.Error();
            }
            wrapper.output_buffers_ = std::move(*output_result);

            LOGD(TAG_WRAPPER, "CompiledModel created with %d inputs, %d outputs",
                 static_cast<int>(wrapper.input_buffers_.size()),
                 static_cast<int>(wrapper.output_buffers_.size()));

            return wrapper;
        }

        /**
         * Run synchronous inference.
         */
        litert::Expected<void> Run() {
            return compiled_model_.Run(input_buffers_, output_buffers_);
        }

        /**
         * Run asynchronous inference (if supported by accelerator).
         *
         * @param async Output: true if async execution was used
         * @return Expected<void> or error
         */
        litert::Expected<void> RunAsync(bool &async) {
            return compiled_model_.RunAsync(input_buffers_, output_buffers_, async);
        }

        /**
         * Write data to input buffer.
         */
        template<typename T>
        litert::Expected<void> WriteInput(size_t index, absl::Span<const T> data) {
            if (index >= input_buffers_.size()) {
                return litert::Unexpected(kLiteRtStatusErrorInvalidArgument,
                                          "Input index out of range");
            }
            return input_buffers_[index].Write<T>(data);
        }

        /**
         * Read data from output buffer.
         */
        template<typename T>
        litert::Expected<void> ReadOutput(size_t index, absl::Span <T> data) {
            if (index >= output_buffers_.size()) {
                return litert::Unexpected(kLiteRtStatusErrorInvalidArgument,
                                          "Output index out of range");
            }
            return output_buffers_[index].Read<T>(data);
        }

        /**
         * Get input buffer for direct access (zero-copy scenarios).
         */
        litert::TensorBuffer &GetInputBuffer(size_t index) {
            return input_buffers_[index];
        }

        /**
         * Get output buffer for direct access.
         */
        litert::TensorBuffer &GetOutputBuffer(size_t index) {
            return output_buffers_[index];
        }

        /**
         * Resize input tensor for dynamic shapes.
         */
        litert::Expected<void> ResizeInput(size_t index, absl::Span<const int> dims) {
            return compiled_model_.ResizeInputTensor(index, dims);
        }

        /**
         * Check if model is fully accelerated.
         */
        litert::Expected<bool> IsFullyAccelerated() {
            return compiled_model_.IsFullyAccelerated();
        }

        HwAccelerator GetAccelerator() const { return accelerator_; }

        size_t GetNumInputs() const { return input_buffers_.size(); }

        size_t GetNumOutputs() const { return output_buffers_.size(); }

    private:
        litert::CompiledModel compiled_model_;
        std::vector<litert::TensorBuffer> input_buffers_;
        std::vector<litert::TensorBuffer> output_buffers_;
        HwAccelerator accelerator_ = HwAccelerator::kCpu;
    };

/**
 * Environment manager using LiteRT C++ API.
 *
 * Supports:
 * - Compiler cache directory for NPU compilation caching
 * - OpenCL/OpenGL context sharing
 * - EGL display/context for GPU
 */
    class EnvironmentManager {
    public:
        static EnvironmentManager &GetInstance() {
            static EnvironmentManager instance;
            return instance;
        }

        /**
         * Initialize environment with cache directory.
         */
        litert::Expected<void> Initialize(const std::string &cache_dir) {
            if (initialized_) {
                return {};
            }

            std::vector<litert::Environment::Option> options;

            if (!cache_dir.empty()) {
                options.push_back({
                                          litert::Environment::OptionTag::CompilerCacheDir,
                                          litert::LiteRtVariant{cache_dir.c_str()}
                                  });
                LOGD(TAG_WRAPPER, "NPU compiler cache enabled: %s", cache_dir.c_str());
            }

            auto result = litert::Environment::Create(absl::MakeConstSpan(options));
            if (!result) {
                LOGE(TAG_WRAPPER, "Failed to create Environment: %s",
                     result.Error().Message().c_str());
                return result.Error();
            }

            env_ = std::move(*result);
            initialized_ = true;

            LOGD(TAG_WRAPPER, "Environment created - CL/GL interop: %s, AHWB/CL: %s, AHWB/GL: %s",
                 env_.SupportsClGlInterop() ? "yes" : "no",
                 env_.SupportsAhwbClInterop() ? "yes" : "no",
                 env_.SupportsAhwbGlInterop() ? "yes" : "no");

            return {};
        }

        litert::Environment &GetEnvironment() { return env_; }

        bool IsInitialized() const { return initialized_; }

        void Shutdown() {
            if (initialized_) {
                env_ = litert::Environment();
                initialized_ = false;
            }
        }

    private:
        EnvironmentManager() = default;

        ~EnvironmentManager() { Shutdown(); }

        litert::Environment env_;
        bool initialized_ = false;
    };

}  // namespace ppocrv5

#undef TAG_WRAPPER

#endif  // PPOCRV5_LITERT_CC_WRAPPER_H
