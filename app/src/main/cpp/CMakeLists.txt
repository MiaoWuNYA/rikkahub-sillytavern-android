cmake_minimum_required(VERSION 3.22)
project(hdguard LANGUAGES C)

# 极小的守卫库：只提供插件密钥派生因子的运行时供给。
# 不依赖任何第三方库，编译产物约 10KB。
add_library(hdguard SHARED hdguard.c)
target_link_libraries(hdguard PRIVATE log)
