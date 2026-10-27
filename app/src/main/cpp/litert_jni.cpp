// Komiho (2026-10-27): MTK NPU 后端（LiteRT + NeuroPilot）的 JNI 入口。
//
// 这是 **M0 脚手架**：只暴露「版本 / 运行时是否可 dlopen」两个探针，用来证明
//   1) 独立 .so 能编译进 APK（仅 arm64-v8a）；
//   2) 按需 dlopen 不产生任何启动期副作用。
// 真正的初始化与推理（initialize / prepare / isActive / processTile / processRgba /
// abort / shutdown）在 M1/M2 补齐，接口语义对齐上游 litert_backend。
//
// 为什么不把 LIB_LITERT 静态链进来：`libLiteRt.so` 由 Maven 依赖提供、随包分发，
// 但**只有真正用到 MTK NPU 的设备才该打开它**。dlopen 让非 MTK 设备/IPC 进程永远
// 不加载这 5.37MB，也让加载失败退化成 "backend unavailable" 而不是启动崩溃。

#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>

namespace {

constexpr const char *kTag = "LiteRtJni";

// Maven 依赖 com.google.ai.edge.litert:litert 带进 APK 的文件名（lib/arm64-v8a/）。
constexpr const char *kLiteRtLibrary = "libLiteRt.so";

// NeuroPilot 的两个插件库：由 litert-npu-runtime-mediatek 带入。
//   libLiteRtCompilerPlugin_MediaTek.so —— 编译期把 tflite 图映射成 APU 可执行格式
//   libLiteRtDispatch_MediaTek.so       —— 运行期把算子调度到 MTK NPU
constexpr const char *kMtkCompilerPlugin = "libLiteRtCompilerPlugin_MediaTek.so";
constexpr const char *kMtkDispatch = "libLiteRtDispatch_MediaTek.so";

/** 版本串，M1 起由真实的 litert_c API 版本代替。 */
constexpr const char *kBackendVersion = "litert 2.3.0";

/**
 * 探测某个 .so 能否 dlopen。只做「打开即关」，不取符号、不初始化任何运行时，
 * 因此即使插件库依赖厂商分区里的库，失败也只是返回 false。
 */
bool canOpen(const char *name) {
    void *handle = dlopen(name, RTLD_NOW | RTLD_LOCAL);
    if (handle == nullptr) {
        const char *err = dlerror();
        __android_log_print(ANDROID_LOG_WARN, kTag, "dlopen %s failed: %s", name, err ? err : "?");
        return false;
    }
    dlclose(handle);
    return true;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_eu_kanade_tachiyomi_util_waifu2x_LiteRtNative_nativeVersion(JNIEnv *env, jobject) {
    return env->NewStringUTF(kBackendVersion);
}

/**
 * 运行时是否齐备（三个 .so 都在包内且可加载）。
 *
 * ⚠️ 返回 true **不代表 NPU 可用**：真正的判定是 M2 的 `isFullyAccelerated` + `isActive()`。
 * 这里只回答「包里的东西能不能打开」。
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_eu_kanade_tachiyomi_util_waifu2x_LiteRtNative_nativeProbeRuntime(JNIEnv *, jobject) {
    const bool runtime = canOpen(kLiteRtLibrary);
    const bool plugin = canOpen(kMtkCompilerPlugin);
    const bool dispatch = canOpen(kMtkDispatch);
    __android_log_print(
        ANDROID_LOG_INFO,
        kTag,
        "probe runtime: litert=%d plugin=%d dispatch=%d",
        runtime ? 1 : 0,
        plugin ? 1 : 0,
        dispatch ? 1 : 0,
    );
    return (runtime && plugin && dispatch) ? JNI_TRUE : JNI_FALSE;
}
