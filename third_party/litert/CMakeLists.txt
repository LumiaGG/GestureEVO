# LiteRT C++ SDK 2.1.0
cmake_minimum_required(VERSION 3.22.1)
project(litert_cc_sdk)

# Include directories
set(LITERT_INCLUDE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/include")

# Find the prebuilt library path
set(LITERT_LIB_DIR "${CMAKE_CURRENT_SOURCE_DIR}/lib/${ANDROID_ABI}")

# Create imported target for LiteRT core library
add_library(litert_cc_api SHARED IMPORTED GLOBAL)
set_target_properties(litert_cc_api PROPERTIES
    IMPORTED_LOCATION "${LITERT_LIB_DIR}/libLiteRt.so"
)

# Create interface library for include directories
add_library(litert_headers INTERFACE)
target_include_directories(litert_headers INTERFACE "${LITERT_INCLUDE_DIR}")

# Link headers to the main library
target_link_libraries(litert_cc_api INTERFACE litert_headers)

# OpenCL accelerator (optional)
if(EXISTS "${LITERT_LIB_DIR}/libLiteRtOpenClAccelerator.so")
    add_library(litert_opencl SHARED IMPORTED GLOBAL)
    set_target_properties(litert_opencl PROPERTIES
        IMPORTED_LOCATION "${LITERT_LIB_DIR}/libLiteRtOpenClAccelerator.so"
    )
endif()
