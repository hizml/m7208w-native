cmake_minimum_required(VERSION 3.22)
project(jbigjni)
add_library(jbigjni SHARED jbig_jni.c jbig.c jbig_ar.c)
target_include_directories(jbigjni PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
