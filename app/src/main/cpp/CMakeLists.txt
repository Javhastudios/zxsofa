cmake_minimum_required(VERSION 3.18.1)
project(zxsofa C)

# Fuse (libretro port) - GPLv3. Ver fuse-libretro/LICENSE
include(${CMAKE_CURRENT_SOURCE_DIR}/fuse_sources.cmake)

set(FUSE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/fuse-libretro)

add_library(fusecore STATIC ${FUSE_SOURCES})
target_include_directories(fusecore PUBLIC
    ${FUSE_DIR}/deps/libretro-common/include
    ${FUSE_DIR}
    ${FUSE_DIR}/src
    ${FUSE_DIR}/src/compat
    ${FUSE_DIR}/fuse
    ${FUSE_DIR}/fuse/compat
    ${FUSE_DIR}/libspectrum
    ${FUSE_DIR}/zlib
    ${FUSE_DIR}/bzip2)
target_compile_definitions(fusecore PRIVATE GIT_VERSION="\\\" zxsofa\\\"" NDEBUG)
target_compile_options(fusecore PRIVATE -O2 -fPIC -fno-strict-aliasing -w)
target_link_libraries(fusecore PUBLIC m)

# Mini frontend libretro (independiente de Android, se puede probar en Linux)
add_library(zxcore STATIC zxcore.c zxtape.c)
target_include_directories(zxcore PUBLIC ${CMAKE_CURRENT_SOURCE_DIR} ${FUSE_DIR}/deps/libretro-common/include)
target_compile_options(zxcore PRIVATE -O2 -fPIC -Wall)
target_compile_definitions(zxcore PRIVATE NDEBUG)
target_link_libraries(zxcore PUBLIC fusecore)

if(ANDROID)
    add_library(zxsofa SHARED jni_bridge.c)
    find_library(log-lib log)
    find_library(jnigraphics-lib jnigraphics)
    target_link_libraries(zxsofa zxcore ${log-lib} ${jnigraphics-lib} m)
    # Paginas de 16 KB (requisito de Android 15+)
    target_link_options(zxsofa PRIVATE "-Wl,-z,max-page-size=16384")
else()
    add_executable(zxtest test/zxtest.c)
    target_link_libraries(zxtest zxcore m)
endif()
