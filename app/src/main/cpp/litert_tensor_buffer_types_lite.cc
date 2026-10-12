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

#include "litert/cc/litert_tensor_buffer_types.h"

#include <string>

#include "litert/c/litert_tensor_buffer_types.h"

namespace litert {

    namespace {

        std::string BufferTypeToStringImpl(LiteRtTensorBufferType buffer_type) {
            switch (buffer_type) {
                case kLiteRtTensorBufferTypeUnknown:
                    return "Unknown";
                case kLiteRtTensorBufferTypeHostMemory:
                    return "HostMemory";
                case kLiteRtTensorBufferTypeAhwb:
                    return "Ahwb";
                case kLiteRtTensorBufferTypeIon:
                    return "Ion";
                case kLiteRtTensorBufferTypeDmaBuf:
                    return "DmaBuf";
                case kLiteRtTensorBufferTypeFastRpc:
                    return "FastRpc";
                case kLiteRtTensorBufferTypeOpenClBuffer:
                    return "OpenClBuffer";
                default:
                    return "Unknown(" + std::to_string(static_cast<int>(buffer_type)) + ")";
            }
        }

    }  // namespace

    std::string BufferTypeToStringCC(TensorBufferType buffer_type) {
        return BufferTypeToStringImpl(static_cast<LiteRtTensorBufferType>(buffer_type));
    }

}  // namespace litert
