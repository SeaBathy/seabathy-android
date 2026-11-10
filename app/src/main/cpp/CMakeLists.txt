# The /vsiseabathyfd/ GDAL plugin (seabathy_fd_vsi.c): lets GDAL read picked
# files in place with no storage permission. GDAL itself is not linked here; the
# plugin finds it at runtime.
cmake_minimum_required(VERSION 3.22.1)
project(seabathyfd C)

add_library(seabathyfd SHARED seabathy_fd_vsi.c)
target_compile_options(seabathyfd PRIVATE -Wall -Wextra -O2)
# 16 KB page alignment, as Google Play requires (also the NDK r28 default).
target_link_options(seabathyfd PRIVATE "-Wl,-z,max-page-size=16384")
target_link_libraries(seabathyfd dl)
