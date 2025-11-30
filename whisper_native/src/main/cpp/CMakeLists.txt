cmake_minimum_required(VERSION 3.22.1)
project(TFLiteEngine VERSION 1.0)

# Specify where to find the header files for TF Lite C++
set(INCLUDE_DIRS
        ${CMAKE_CURRENT_LIST_DIR}/tf-lite-api/tensorflow_src
        ${CMAKE_CURRENT_LIST_DIR}/tf-lite-api/include/flatbuffers/include
)

if (ANDROID)
    # Create the native library
    add_library(audioEngine SHARED
            TFLiteEngine.cpp
            TFLiteEngineJNI.cpp)

    target_include_directories(audioEngine PRIVATE ${INCLUDE_DIRS})

    # Link with Android log library
    find_library(log-lib log)

    # Add TensorFlow Lite library (imported from Gradle dependency)
    add_library(tensorflowlite SHARED IMPORTED)
    set_target_properties(tensorflowlite PROPERTIES IMPORTED_LOCATION
            ${CMAKE_CURRENT_LIST_DIR}/tf-lite-api/generated-libs/${ANDROID_ABI}/libtensorflowlite.so)

    target_link_libraries(audioEngine
            ${log-lib}
            tensorflowlite)
endif()
