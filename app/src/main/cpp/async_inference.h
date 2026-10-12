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

#ifndef PPOCRV5_ASYNC_INFERENCE_H
#define PPOCRV5_ASYNC_INFERENCE_H

/**
 * Asynchronous Inference Utilities for LiteRT CompiledModel API
 *
 * Based on official documentation:
 * https://ai.google.dev/edge/litert/next/gpu
 *
 * LiteRT's asynchronous methods like RunAsync() let you schedule GPU inference
 * while continuing other tasks using the CPU. In complex pipelines, GPU is
 * often used asynchronously alongside CPU or NPUs.
 *
 *
 * Example from official docs:
 *
 *   // 1. Prepare input buffer (OpenGL buffer)
 *   LITERT_ASSIGN_OR_RETURN(auto gl_input,
 *       TensorBuffer::CreateFromGlBuffer(env, tensor_type, opengl_tex));
 *
 *   // 2. Create and set event for synchronization
 *   LITERT_ASSIGN_OR_RETURN(auto input_event,
 *       Event::CreateManagedEvent(env, LiteRtEventTypeEglSyncFence));
 *   inputs[0].SetEvent(std::move(input_event));
 *
 *   // 3. Kick off GPU inference
 *   compiled_model.RunAsync(inputs, outputs);
 *
 *   // 4. Meanwhile, do other CPU work...
 *
 *   // 5. Access model output (blocks until inference complete)
 *   outputs[0].Read<float>(absl::MakeSpan(data));
 */

#include <vector>

#include "litert/cc/litert_compiled_model.h"
#include "litert/cc/litert_environment.h"
#include "litert/cc/litert_event.h"
#include "litert/cc/litert_expected.h"
#include "litert/cc/litert_tensor_buffer.h"

#include "logging.h"

#define TAG_ASYNC "AsyncInference"

namespace ppocrv5 {
    namespace async {

/**
 * Create a managed Event for synchronization.
 *
 * Event types:
 * - kLiteRtEventTypeEglSyncFence: EGL sync fence for GPU synchronization
 * - kLiteRtEventTypeSyncFdFence: Android sync fd fence
 *
 * From official docs:
 *   LITERT_ASSIGN_OR_RETURN(auto input_event,
 *       Event::CreateManagedEvent(env, LiteRtEventTypeEglSyncFence));
 */
        inline litert::Expected <litert::Event> CreateEglSyncEvent(
                litert::Environment &env) {
            return litert::Event::CreateManaged(env, kLiteRtEventTypeEglSyncFence);
        }

/**
 * Attach an event to a tensor buffer.
 *
 * The event signals when the buffer data is ready for use.
 * This ensures GPU doesn't read from buffer until data is written.
 *
 * From official docs:
 *   inputs[0].SetEvent(std::move(input_event));
 */
        inline litert::Expected<void> SetBufferEvent(
                litert::TensorBuffer &buffer,
                litert::Event &&event) {
            return buffer.SetEvent(std::move(event));
        }

/**
 * Get event from tensor buffer.
 */
        inline litert::Expected <litert::Event> GetBufferEvent(
                const litert::TensorBuffer &buffer) {
            return buffer.GetEvent();
        }

/**
 * Check if buffer has an associated event.
 */
        inline bool HasEvent(const litert::TensorBuffer &buffer) {
            return buffer.HasEvent();
        }

/**
 * Clear event from tensor buffer.
 */
        inline litert::Expected<void> ClearBufferEvent(litert::TensorBuffer &buffer) {
            return buffer.ClearEvent();
        }

/**
 * Run asynchronous inference with event synchronization.
 *
 * This is the recommended pattern for GPU inference in pipelines:
 * 1. Create input event and attach to input buffer
 * 2. Call RunAsync (non-blocking)
 * 3. Do other CPU work while GPU processes
 * 4. Read output (blocks until inference complete)
 *
 * @param compiled_model The compiled model
 * @param input_buffers Input tensor buffers
 * @param output_buffers Output tensor buffers
 * @param async Output: true if async execution was used
 * @return Expected<void> or error
 */
        inline litert::Expected<void> RunAsync(
                litert::CompiledModel &compiled_model,
                std::vector<litert::TensorBuffer> &input_buffers,
                std::vector<litert::TensorBuffer> &output_buffers,
                bool &async) {
            return compiled_model.RunAsync(input_buffers, output_buffers, async);
        }

/**
 * Run synchronous inference.
 */
        inline litert::Expected<void> Run(
                litert::CompiledModel &compiled_model,
                const std::vector<litert::TensorBuffer> &input_buffers,
                const std::vector<litert::TensorBuffer> &output_buffers) {
            return compiled_model.Run(input_buffers, output_buffers);
        }

/**
 * Pipeline helper for overlapped CPU preprocessing and GPU inference.
 *
 * Pattern:
 *   Frame N:   [Preprocess] -> [GPU Inference] -> [Postprocess]
 *   Frame N+1:              [Preprocess] -> [GPU Inference] -> [Postprocess]
 *
 * This overlaps CPU preprocessing with GPU inference for better throughput.
 */
        class AsyncPipeline {
        public:
            AsyncPipeline(litert::CompiledModel &model,
                          litert::Environment &env)
                    : model_(model), env_(env) {}

            /**
             * Submit frame for async processing.
             *
             * @param input_buffers Input buffers with data ready
             * @param output_buffers Output buffers to receive results
             * @return true if async execution was used
             */
            litert::Expected<bool> Submit(
                    std::vector<litert::TensorBuffer> &input_buffers,
                    std::vector<litert::TensorBuffer> &output_buffers) {

                bool async = false;
                auto result = model_.RunAsync(input_buffers, output_buffers, async);
                if (!result) {
                    return result.Error();
                }
                return async;
            }

        private:
            litert::CompiledModel &model_;
            litert::Environment &env_;
        };

    }  // namespace async
}  // namespace ppocrv5

#undef TAG_ASYNC

#endif  // PPOCRV5_ASYNC_INFERENCE_H
