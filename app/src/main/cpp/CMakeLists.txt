cmake_minimum_required(VERSION 3.22.1)
project(ppocrv5)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Fetch Abseil for LiteRT C++ API
include(FetchContent)
set(ABSL_PROPAGATE_CXX_STD ON)
set(ABSL_BUILD_TESTING OFF)
set(ABSL_ENABLE_INSTALL OFF)

FetchContent_Declare(
        abseil-cpp
        GIT_REPOSITORY https://github.com/abseil/abseil-cpp.git
        # 20250814.1: pin the release commit so a moved tag cannot change builds.
        GIT_TAG d38452e1ee03523a208362186fd42248ff2609f6
)
FetchContent_MakeAvailable(abseil-cpp)

# Release build optimizations
if (CMAKE_BUILD_TYPE STREQUAL "Release")
    add_compile_definitions(NDEBUG)
    set(CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} -O3 -ffast-math -ftree-vectorize")
    set(CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} -march=armv8-a+simd+crypto")
    set(CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} -mtune=cortex-a76")
    set(CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} -flto=thin")
    set(CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} -funroll-loops")
    set(CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} -fomit-frame-pointer")
    set(CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} -ffunction-sections -fdata-sections")
    set(CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} -fvisibility=hidden -fvisibility-inlines-hidden")
    set(CMAKE_SHARED_LINKER_FLAGS_RELEASE "${CMAKE_SHARED_LINKER_FLAGS_RELEASE} -flto=thin -Wl,--gc-sections -Wl,--strip-all")
endif ()

if (CMAKE_BUILD_TYPE STREQUAL "Debug")
    set(CMAKE_CXX_FLAGS_DEBUG "${CMAKE_CXX_FLAGS_DEBUG} -O1")
endif ()

# Disable optional hardware support.
add_compile_definitions(
        LITERT_DISABLE_OPENCL_SUPPORT
        LITERT_DISABLE_VULKAN_SUPPORT
        LITERT_DISABLE_WEBGPU_SUPPORT
)

# LiteRT SDK (prebuilt libraries)
set(LITERT_SDK_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/litert")
add_subdirectory("${LITERT_SDK_DIR}" "litert_cc_sdk/build")

# Include directories
include_directories("${LITERT_SDK_DIR}/include")

# LiteRT C++ wrapper sources
# Use simplified local implementations for options and tensor_buffer_types
# to avoid linking issues with unsupported functions in prebuilt library
set(LITERT_CC_SOURCES
        # Core C++ wrappers from SDK
        ${LITERT_SDK_DIR}/include/litert/cc/litert_compiled_model.cc
        ${LITERT_SDK_DIR}/include/litert/cc/litert_macros.cc
        ${LITERT_SDK_DIR}/include/litert/cc/litert_model.cc
        ${LITERT_SDK_DIR}/include/litert/cc/litert_tensor_buffer.cc
        ${LITERT_SDK_DIR}/include/litert/cc/litert_opaque_options.cc
        ${LITERT_SDK_DIR}/include/litert/cc/litert_custom_op_kernel.cc
        # Simplified local implementations
        ${CMAKE_CURRENT_SOURCE_DIR}/litert_options_lite.cc
        ${CMAKE_CURRENT_SOURCE_DIR}/litert_tensor_buffer_types_lite.cc
        # C++ options wrappers (only those supported by prebuilt lib)
        ${LITERT_SDK_DIR}/include/litert/cc/options/litert_compiler_options.cc
        ${LITERT_SDK_DIR}/include/litert/cc/options/litert_cpu_options.cc
        ${LITERT_SDK_DIR}/include/litert/cc/options/litert_gpu_options.cc
        ${LITERT_SDK_DIR}/include/litert/cc/options/litert_runtime_options.cc
        # C++ internal utilities
        ${LITERT_SDK_DIR}/include/litert/cc/internal/scoped_file.cc
        ${LITERT_SDK_DIR}/include/litert/cc/internal/scoped_file_posix.cc
)

add_library(ppocrv5_jni SHARED
        ppocrv5_jni.cpp
        ocr_engine.cpp
        text_detector.cpp
        text_recognizer.cpp
        image_utils.cpp
        postprocess.cpp
        ${LITERT_CC_SOURCES}
)

# Ensure LOAD segment alignment for 16 KB page-size devices (Android 15+)
target_link_options(ppocrv5_jni PRIVATE
        "-Wl,-z,max-page-size=16384"
        "-Wl,-z,common-page-size=16384"
)

target_link_libraries(ppocrv5_jni
        litert_cc_api
        android
        log
        jnigraphics
        absl::span
        absl::flat_hash_map
        absl::strings
        absl::status
        absl::statusor
        absl::cleanup
        absl::log
        absl::log_internal_check_op
        absl::log_internal_message
        absl::algorithm_container
        absl::any_invocable
        absl::string_view
)
