cmake_minimum_required(VERSION 3.18)
project(mihonsy-enhance)

set(CMAKE_CXX_STANDARD 17)

set(ENHANCE_SOURCES
    lanczos3.cpp
)

add_library(mihonsy-enhance SHARED ${ENHANCE_SOURCES})

target_include_directories(mihonsy-enhance PRIVATE ${CMAKE_SOURCE_DIR})

target_link_libraries(mihonsy-enhance
    android
    log
    jnigraphics
)
