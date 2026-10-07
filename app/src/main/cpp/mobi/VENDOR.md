# libmobi (vendored)

- 来源：https://github.com/bfabiszewski/libmobi
- 版本：v0.12（tag tarball，源码未做任何修改）
- 许可：LGPL-3.0（见 LICENSE）
- 用途：MOBI/AZW3/AZW 解析（纯抽图），由同目录 `../mobi_jni.cpp` 包装成 `libmobi-jni.so`，
  编译配置见 `../CMakeLists.txt`（定义对应上游 CMake 默认开启项：USE_ENCRYPTION /
  USE_XMLWRITER / HAVE_UNISTD_H / HAVE_GETOPT / HAVE_STRDUP / HAVE_SYS_RESOURCE_H /
  MOBI_INLINE=inline / HAVE_ATTRIBUTE_NORETURN，zlib 用 NDK 自带 libz，miniz 按上游
  OBJECT 库方式单独编译并带 MINIZ_* 宏）。

如需升级：下载对应 tag tarball，覆盖 src/*.c *.h 与 LICENSE，并更新本文件版本号。
