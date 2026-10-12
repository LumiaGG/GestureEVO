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

#ifndef PPOCRV5_LITERT_CONFIG_H
#define PPOCRV5_LITERT_CONFIG_H

#include <string>

/**
 * LiteRT CompiledModel API Configuration
 *
 * Based on official documentation:
 * - https://ai.google.dev/edge/litert/next/android_cpp
 * - https://ai.google.dev/edge/litert/next/gpu
 *
 */

namespace ppocrv5::litert_config {

// NPU compilation cache directory (set at runtime via Environment options)
// From docs: Environment::OptionTag::CompilerCacheDir
    inline std::string g_compiler_cache_dir;

// Enable NPU compilation caching for faster subsequent loads
// First load: ~7s, Cached load: ~200ms (30-40x speedup)
    constexpr bool kEnableCompilerCache = true;

// Enable zero-copy buffer optimization
// From docs: TensorBuffer::CreateFromHostMemory, CreateFromAhwb, CreateFromGlBuffer
// Reduces CPU-GPU memory copy overhead by 20-40%
    constexpr bool kEnableZeroCopy = true;

// Enable asynchronous inference execution
// From docs: CompiledModel::RunAsync() with Event synchronization
// Allows CPU to continue work while GPU processes
    constexpr bool kEnableAsyncInference = true;

// Performance tuning constants
    constexpr int kWarmupIterations = 0;  // Number of warmup inferences

// NPU-specific optimizations
    namespace npu {
        // NPU is optimized for INT8 quantized models
        // For FP16 models, GPU delegate provides better performance
        constexpr bool kPreferForInt8 = true;

        // Minimum API level for NPU support
        constexpr int kMinApiLevel = 31;
    }

// GPU-specific optimizations (primary accelerator for FP16 models)
// Reference: https://ai.google.dev/edge/litert/next/gpu
    namespace gpu {
        // Enable OpenCL buffer sharing for zero-copy
        // From docs: TensorBuffer::GetOpenClMemory()
        constexpr bool kEnableOpenClBufferSharing = true;

        // FP16 precision - optimal for GPU inference
        // Adreno 650 (SD870) has excellent FP16 throughput
        // Expected speedup: 1.5-2x vs CPU
        constexpr bool kPreferFp16 = true;

        // Enable AHardwareBuffer for zero-copy GPU inference
        // From docs: TensorBuffer::CreateFromAhwb(), GetAhwb()
        // Supports interop: AHWB -> OpenGL, AHWB -> OpenCL
        constexpr bool kEnableAhwbZeroCopy = true;

        // Enable async inference for pipelined execution
        // From docs: CompiledModel::RunAsync() with Event::CreateManaged()
        // Pattern: CPU preprocess frame N+1 while GPU processes frame N
        constexpr bool kEnableAsyncExecution = true;

        // Enable OpenGL buffer support for camera pipeline integration
        // From docs: TensorBuffer::CreateFromGlBuffer()
        constexpr bool kEnableGlBufferSupport = true;
    }

// Buffer interop capabilities (check at runtime via Environment)
// From docs: Environment::SupportsClGlInterop(), SupportsAhwbClInterop(), SupportsAhwbGlInterop()
    namespace interop {
        // CL/GL interop: Create OpenCL buffer from OpenGL buffer (zero-copy)
        constexpr bool kCheckClGlInterop = true;

        // AHWB/CL interop: Create OpenCL buffer from AHardwareBuffer (zero-copy)
        constexpr bool kCheckAhwbClInterop = true;

        // AHWB/GL interop: Create OpenGL buffer from AHardwareBuffer (zero-copy)
        constexpr bool kCheckAhwbGlInterop = true;
    }

// SIMD optimization flags
    namespace simd {
        // Enable NEON SIMD for ARM64 preprocessing/postprocessing
#if defined(__ARM_NEON) || defined(__ARM_NEON__)
        constexpr bool kEnableNeon = true;
#else
        constexpr bool kEnableNeon = false;
#endif

        // Prefetch distance for memory operations
        constexpr int kPrefetchDistance = 256;

        // Vector width for NEON operations
        constexpr int kNeonVectorWidth = 4;
    }

// Memory optimization
    namespace memory {
        // Align buffers to cache line size (required for zero-copy)
        // From docs: LITERT_HOST_MEMORY_BUFFER_ALIGNMENT
        constexpr size_t kCacheLineSize = 64;

        // Pre-allocate buffer sizes for detection model
        // Input: [1, 1728, 768, 3] NHWC float32
        constexpr size_t kDetInputBufferSize = 1 * 1728 * 768 * 3 * sizeof(float);

        // Pre-allocate buffer sizes for recognition model
        // Input: [1, 48, 320, 3] NHWC float32
        constexpr size_t kRecInputBufferSize = 1 * 48 * 320 * 3 * sizeof(float);

        // Maximum number of text boxes to process per frame
        constexpr int kMaxTextBoxes = 50;
    }

} // namespace ppocrv5::litert_config

#endif  // PPOCRV5_LITERT_CONFIG_H
