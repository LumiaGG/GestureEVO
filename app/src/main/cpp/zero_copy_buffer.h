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

#ifndef PPOCRV5_ZERO_COPY_BUFFER_H
#define PPOCRV5_ZERO_COPY_BUFFER_H

/**
 * Zero-Copy Buffer Utilities for LiteRT CompiledModel API
 *
 * Based on official documentation:
 * https://ai.google.dev/edge/litert/next/gpu
 * https://ai.google.dev/edge/litert/next/android_cpp
 *
 * Zero-copy enables GPU to access data directly without CPU memory copies.
 *
 * Buffer interop (zero-copy conversion):
 * - AHardwareBuffer -> OpenGL buffer
 * - AHardwareBuffer -> OpenCL buffer
 * - OpenGL buffer -> OpenCL buffer (on supported devices)
 */

#include <android/hardware_buffer.h>

#include "litert/cc/litert_environment.h"
#include "litert/cc/litert_expected.h"
#include "litert/cc/litert_tensor_buffer.h"

#include "logging.h"

#define TAG_ZEROCOPY "ZeroCopyBuffer"

namespace ppocrv5 {
    namespace zero_copy {

/**
 * Create a TensorBuffer from existing host memory.
 *
 * The provided host memory is NOT owned by the TensorBuffer and must
 * outlive the TensorBuffer object.
 *
 * From official docs:
 *   LITERT_ASSIGN_OR_RETURN(auto tensor_buffer_from_host,
 *       TensorBuffer::CreateFromHostMemory(env, ranked_tensor_type,
 *       ptr_to_host_memory, buffer_size));
 */
        inline litert::Expected <litert::TensorBuffer> CreateFromHostMemory(
                litert::Environment &env,
                const litert::RankedTensorType &tensor_type,
                void *host_memory,
                size_t buffer_size) {
            return litert::TensorBuffer::CreateFromHostMemory(
                    env, tensor_type, host_memory, buffer_size);
        }

/**
 * Create a TensorBuffer from AHardwareBuffer.
 *
 * AHardwareBuffer provides:
 * - Zero-copy between CPU and GPU
 * - Direct camera frame access (via ImageReader)
 * - Efficient buffer sharing across processes
 *
 * From official docs:
 *   LITERT_ASSIGN_OR_RETURN(auto tensor_buffer_from_ahwb,
 *       TensorBuffer::CreateFromAhwb(env, ranked_tensor_type,
 *       ahardware_buffer, offset));
 */
        inline litert::Expected <litert::TensorBuffer> CreateFromAhwb(
                litert::Environment &env,
                const litert::RankedTensorType &tensor_type,
                AHardwareBuffer *ahwb,
                size_t offset = 0) {
            return litert::TensorBuffer::CreateFromAhwb(env, tensor_type, ahwb, offset);
        }

/**
 * Create a managed TensorBuffer backed by AHardwareBuffer.
 *
 * The buffer is managed by LiteRT and will be automatically released.
 */
        inline litert::Expected <litert::TensorBuffer> CreateManagedAhwb(
                litert::Environment &env,
                const litert::RankedTensorType &tensor_type,
                size_t buffer_size) {
            return litert::TensorBuffer::CreateManaged(
                    env, litert::TensorBufferType::kAhwb, tensor_type, buffer_size);
        }

/**
 * Create a TensorBuffer from OpenGL buffer.
 *
 * For camera/rendering pipeline integration where images are already
 * in OpenGL buffers.
 *
 * From official docs:
 *   LITERT_ASSIGN_OR_RETURN(auto gl_input_buffer,
 *       TensorBuffer::CreateFromGlBuffer(env, tensor_type,
 *       opengl_buffer.target, opengl_buffer.id,
 *       opengl_buffer.size_bytes, opengl_buffer.offset));
 */
        inline litert::Expected <litert::TensorBuffer> CreateFromGlBuffer(
                litert::Environment &env,
                const litert::RankedTensorType &tensor_type,
                unsigned int gl_target,
                unsigned int gl_id,
                size_t size_bytes,
                size_t offset = 0) {
            return litert::TensorBuffer::CreateFromGlBuffer(
                    env, tensor_type, gl_target, gl_id, size_bytes, offset);
        }

/**
 * Get OpenCL buffer from TensorBuffer.
 *
 * On mobile devices that support interoperability, CL buffers can be
 * created from GL buffers or AHardwareBuffers with zero-copy.
 *
 * From official docs:
 *   LITERT_ASSIGN_OR_RETURN(auto cl_buffer, tensor_buffer.GetOpenClMemory());
 */
        inline litert::Expected <cl_mem> GetOpenClMemory(litert::TensorBuffer &buffer) {
            return buffer.GetOpenClMemory();
        }

/**
 * Get OpenGL buffer info from TensorBuffer.
 */
        inline litert::Expected <litert::TensorBuffer::GlBuffer> GetGlBuffer(
                litert::TensorBuffer &buffer) {
            return buffer.GetGlBuffer();
        }

/**
 * Get AHardwareBuffer from TensorBuffer.
 */
        inline litert::Expected<AHardwareBuffer *> GetAhwb(litert::TensorBuffer &buffer) {
            return buffer.GetAhwb();
        }

/**
 * Check if environment supports CL/GL interop.
 */
        inline bool SupportsClGlInterop(litert::Environment &env) {
            return env.SupportsClGlInterop();
        }

/**
 * Check if environment supports AHWB/CL interop.
 */
        inline bool SupportsAhwbClInterop(litert::Environment &env) {
            return env.SupportsAhwbClInterop();
        }

/**
 * Check if environment supports AHWB/GL interop.
 */
        inline bool SupportsAhwbGlInterop(litert::Environment &env) {
            return env.SupportsAhwbGlInterop();
        }

/**
 * RAII scoped lock for TensorBuffer.
 *
 * Usage:
 *   auto [lock, ptr] = TensorBufferScopedLock::Create<float>(buffer, LockMode::kWrite);
 *   // Use ptr...
 *   // Automatically unlocked when lock goes out of scope
 */
        using ScopedLock = litert::TensorBufferScopedLock;

    }  // namespace zero_copy
}  // namespace ppocrv5

#undef TAG_ZEROCOPY

#endif  // PPOCRV5_ZERO_COPY_BUFFER_H
