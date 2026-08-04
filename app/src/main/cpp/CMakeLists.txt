# Kotori offline translation — thin llama.cpp JNI for arm64-v8a + x86_64.
# Requires a pinned vendor tree at <repo>/third_party/llama.cpp (tag b10240).
# Model GGUF weights are NEVER vendored. Release builds must not FetchContent.

cmake_minimum_required(VERSION 3.22.1)
project(kotori_llama LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# app/src/main/cpp → ../../../../ = repo root
set(LLAMA_SRC_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/llama.cpp")
set(LLAMA_CPP_PIN "b10240")

if(NOT EXISTS "${LLAMA_SRC_DIR}/CMakeLists.txt")
    message(FATAL_ERROR
        "Vendored llama.cpp not found at ${LLAMA_SRC_DIR}.\n"
        "Pin tag ${LLAMA_CPP_PIN} is required for reproducible builds.\n"
        "Clone: git clone --depth 1 --branch ${LLAMA_CPP_PIN} "
        "https://github.com/ggml-org/llama.cpp.git third_party/llama.cpp\n"
        "Do not rely on network FetchContent for release.")
endif()

message(STATUS "Using vendored llama.cpp (pin ${LLAMA_CPP_PIN}) at ${LLAMA_SRC_DIR}")
set(LLAMA_SRC "${LLAMA_SRC_DIR}")

# Keep the Android build lean: library only, no tools/examples/tests, no OpenMP.
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)
set(GGML_STATIC ON CACHE BOOL "" FORCE)

add_subdirectory(${LLAMA_SRC} ${CMAKE_BINARY_DIR}/llama-build EXCLUDE_FROM_ALL)

add_library(kotori_llama SHARED kotori_llama_jni.cpp)

target_include_directories(kotori_llama PRIVATE
    ${LLAMA_SRC}/include
    ${LLAMA_SRC}/ggml/include
)

target_link_libraries(kotori_llama
    llama
    android
    log
)

set_target_properties(kotori_llama PROPERTIES
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN ON
)
