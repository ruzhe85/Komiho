// Komiho (2026-10-27): MTK NPU 后端（LiteRT + NeuroPilot）的 JNI 入口。
//
// 这一层只做「类型转换 + 参数校验」，所有引擎逻辑在 litert_backend.cpp。
// 单独编成 `liblitert-jni.so`（见 CMakeLists）：与 waifu2x-jni 的全局引擎状态完全隔离，
// 且 `libLiteRt.so` 走运行期 dlopen —— 非 MTK 设备永远不会打开它。
//
// M1 起提供完整接口：initialize / isActive / process / abort / progress / timing / shutdown。
// 生产代码目前**没有任何调用点**（模型来源与会话化在 M2/M3），因此不会影响现有用户。

#include <jni.h>

#include <android/bitmap.h>
#include <android/log.h>
#include <dlfcn.h>

#include <cstring>
#include <string>
#include <vector>

#include "litert_backend.h"

namespace {

constexpr const char *kTag = "LiteRtJni";

constexpr const char *kLiteRtLibrary = "libLiteRt.so";
constexpr const char *kMtkCompilerPlugin = "libLiteRtCompilerPlugin_MediaTek.so";
constexpr const char *kMtkDispatch = "libLiteRtDispatch_MediaTek.so";

constexpr const char *kBackendVersion = "litert 2.3.0";

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

std::string toStdString(JNIEnv *env, jstring value) {
    if (value == nullptr) return std::string();
    const char *chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return std::string();
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

jstring toString(JNIEnv *env, const std::string &value) {
    return env->NewStringUTF(value.c_str());
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_eu_kanade_tachiyomi_util_waifu2x_LiteRtNative_nativeVersion(JNIEnv *env, jobject) {
    return env->NewStringUTF(kBackendVersion);
}

/**
 * 运行时是否齐备（三个 .so 都在包内且可加载）。
 * ⚠️ true **不代表 NPU 可用** —— 那要 `nativeInitialize` 成功且 `nativeIsActive()` 为真。
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_eu_kanade_tachiyomi_util_waifu2x_LiteRtNative_nativeProbeRuntime(JNIEnv *, jobject) {
    const bool runtime = canOpen(kLiteRtLibrary);
    const bool plugin = canOpen(kMtkCompilerPlugin);
    const bool dispatch = canOpen(kMtkDispatch);
    __android_log_print(ANDROID_LOG_INFO,
                        kTag,
                        "probe runtime: litert=%d plugin=%d dispatch=%d",
                        runtime ? 1 : 0,
                        plugin ? 1 : 0,
                        dispatch ? 1 : 0);
    return (runtime && plugin && dispatch) ? JNI_TRUE : JNI_FALSE;
}

/** 初始化引擎；返回 "" 表示成功，否则是错误描述（可直接写进诊断日志）。 */
extern "C" JNIEXPORT jstring JNICALL
Java_eu_kanade_tachiyomi_util_waifu2x_LiteRtNative_nativeInitialize(
    JNIEnv *env, jobject, jstring jModelPath, jstring jCacheDir, jstring jLibraryDir, jint scale,
    jint padding, jboolean cpuOnly) {
    litert_backend::Config config;
    config.model_path = toStdString(env, jModelPath);
    config.cache_dir = toStdString(env, jCacheDir);
    config.library_dir = toStdString(env, jLibraryDir);
    config.scale = scale;
    config.padding = padding;
    config.cpu_only = cpuOnly == JNI_TRUE;

    std::string error;
    if (!litert_backend::initialize(config, &error)) {
        if (error.empty()) error = litert_backend::last_error();
        return toString(env, error);
    }
    return env->NewStringUTF("");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_eu_kanade_tachiyomi_util_waifu2x_LiteRtNative_nativeIsInitialized(JNIEnv *, jobject) {
    return litert_backend::is_initialized() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_eu_kanade_tachiyomi_util_waifu2x_LiteRtNative_nativeIsActive(JNIEnv *, jobject) {
    return litert_backend::is_npu_active() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_eu_kanade_tachiyomi_util_waifu2x_LiteRtNative_nativeScale(JNIEnv *, jobject) {
    return litert_backend::scale_value();
}

extern "C" JNIEXPORT jint JNICALL
Java_eu_kanade_tachiyomi_util_waifu2x_LiteRtNative_nativeTileSize(JNIEnv *, jobject) {
    return litert_backend::tile_size();
}

/**
 * 整页放大。输入 RGBA_8888（`width*height*4`），返回放大后的 RGBA_8888
 * （尺寸 `width*scale × height*scale`）；失败/被中止返回 null，原因用 [nativeLastError] 取。
 */
extern "C" JNIEXPORT jbyteArray JNICALL
Java_eu_kanade_tachiyomi_util_waifu2x_LiteRtNative_nativeProcess(JNIEnv *env, jobject,
                                                                jbyteArray jRgba, jint width,
                                                                jint height) {
    if (jRgba == nullptr || width <= 0 || height <= 0) return nullptr;
    const jsize length = env->GetArrayLength(jRgba);
    const jsize expected = width * height * 4;
    if (length < expected) {
        __android_log_print(ANDROID_LOG_ERROR,
                            kTag,
                            "nativeProcess: input too small %d < %d",
                            length,
                            expected);
        return nullptr;
    }

    std::vector<uint8_t> input(static_cast<size_t>(expected));
    env->GetByteArrayRegion(jRgba, 0, expected, reinterpret_cast<jbyte *>(input.data()));

    std::vector<uint8_t> output;
    int out_w = 0;
    int out_h = 0;
    std::string error;
    if (!litert_backend::process(
            input.data(), width, height, &output, &out_w, &out_h, &error)) {
        __android_log_print(ANDROID_LOG_WARN, kTag, "nativeProcess failed: %s", error.c_str());
        return nullptr;
    }

    const jsize outLength = static_cast<jsize>(output.size());
    jbyteArray result = env->NewByteArray(outLength);
    if (result == nullptr) return nullptr;
    env->SetByteArrayRegion(result, 0, outLength, reinterpret_cast<const jbyte *>(output.data()));
    return result;
}

namespace {

/** Bitmap(ARGB_8888) → 紧凑 RGBA（Bitmap 的 stride 常带 padding，不能直接当紧密缓冲用）。 */
bool readBitmapRgba(JNIEnv *env, jobject bitmap, std::vector<uint8_t> *out, int *out_w,
                    int *out_h) {
    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "getInfo failed");
        return false;
    }
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "unsupported bitmap format=%d", info.format);
        return false;
    }
    void *pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS ||
        pixels == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "lockPixels failed");
        return false;
    }
    const int width = static_cast<int>(info.width);
    const int height = static_cast<int>(info.height);
    out->resize(static_cast<size_t>(width) * height * 4);
    const auto *src = static_cast<const uint8_t *>(pixels);
    for (int y = 0; y < height; ++y) {
        std::memcpy(out->data() + static_cast<size_t>(y) * width * 4,
                    src + static_cast<size_t>(y) * info.stride,
                    static_cast<size_t>(width) * 4);
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    *out_w = width;
    *out_h = height;
    return true;
}

/** 紧凑 RGBA → 新建 ARGB_8888 Bitmap。 */
jobject createBitmapFromRgba(JNIEnv *env, const std::vector<uint8_t> &rgba, int width,
                             int height) {
    jclass bitmap_cls = env->FindClass("android/graphics/Bitmap");
    jclass config_cls = env->FindClass("android/graphics/Bitmap$Config");
    if (bitmap_cls == nullptr || config_cls == nullptr) return nullptr;
    jfieldID argb_field =
        env->GetStaticFieldID(config_cls, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
    jmethodID create = env->GetStaticMethodID(
        bitmap_cls,
        "createBitmap",
        "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    if (argb_field == nullptr || create == nullptr) return nullptr;
    jobject config = env->GetStaticObjectField(config_cls, argb_field);
    jobject bitmap = env->CallStaticObjectMethod(bitmap_cls, create, width, height, config);
    if (bitmap == nullptr) return nullptr;

    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return nullptr;
    }
    void *pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS ||
        pixels == nullptr) {
        return nullptr;
    }
    auto *dst = static_cast<uint8_t *>(pixels);
    for (int y = 0; y < height; ++y) {
        std::memcpy(dst + static_cast<size_t>(y) * info.stride,
                    rgba.data() + static_cast<size_t>(y) * width * 4,
                    static_cast<size_t>(width) * 4);
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return bitmap;
}

}  // namespace

/**
 * Bitmap → 放大 → Bitmap（生产路径）。
 *
 * 为什么不复用 [nativeProcess] 的 ByteArray 接口：2× 放大的输出（例：2000×3000 → 4000×6000）
 * 若在 Kotlin 侧再开一份紧凑字节数组就是 ~96MB 额外峰值，低内存机直接 OOM。
 * 这里在 native 内逐行拷贝，峰值只有输入/输出 Bitmap + 分块缓冲。
 */
extern "C" JNIEXPORT jobject JNICALL
Java_eu_kanade_tachiyomi_util_waifu2x_LiteRtNative_nativeProcessBitmap(JNIEnv *env, jobject,
                                                                      jobject bitmap) {
    if (bitmap == nullptr) return nullptr;
    std::vector<uint8_t> input;
    int width = 0;
    int height = 0;
    if (!readBitmapRgba(env, bitmap, &input, &width, &height)) return nullptr;

    std::vector<uint8_t> output;
    int out_w = 0;
    int out_h = 0;
    std::string error;
    if (!litert_backend::process(input.data(), width, height, &output, &out_w, &out_h, &error)) {
        __android_log_print(ANDROID_LOG_WARN, kTag, "processBitmap failed: %s", error.c_str());
        return nullptr;
    }
    return createBitmapFromRgba(env, output, out_w, out_h);
}

extern "C" JNIEXPORT jint JNICALL
Java_eu_kanade_tachiyomi_util_waifu2x_LiteRtNative_nativeGetProgress(JNIEnv *, jobject) {
    return litert_backend::progress();
}

extern "C" JNIEXPORT jlong JNICALL
Java_eu_kanade_tachiyomi_util_waifu2x_LiteRtNative_nativeLastInferenceMs(JNIEnv *, jobject) {
    return static_cast<jlong>(litert_backend::last_timing().inference_ms);
}

extern "C" JNIEXPORT jlong JNICALL
Java_eu_kanade_tachiyomi_util_waifu2x_LiteRtNative_nativeLastTotalMs(JNIEnv *, jobject) {
    return static_cast<jlong>(litert_backend::last_timing().total_ms);
}

extern "C" JNIEXPORT void JNICALL
Java_eu_kanade_tachiyomi_util_waifu2x_LiteRtNative_nativeAbort(JNIEnv *, jobject) {
    litert_backend::request_abort();
}

extern "C" JNIEXPORT void JNICALL
Java_eu_kanade_tachiyomi_util_waifu2x_LiteRtNative_nativeClearAbort(JNIEnv *, jobject) {
    litert_backend::clear_abort();
}

extern "C" JNIEXPORT jstring JNICALL
Java_eu_kanade_tachiyomi_util_waifu2x_LiteRtNative_nativeLastError(JNIEnv *env, jobject) {
    const std::string error = litert_backend::last_error();
    const std::string detail = litert_backend::last_error_detail();
    if (detail.empty()) return toString(env, error);
    return toString(env, error + ": " + detail);
}

extern "C" JNIEXPORT void JNICALL
Java_eu_kanade_tachiyomi_util_waifu2x_LiteRtNative_nativeShutdown(JNIEnv *, jobject) {
    litert_backend::shutdown();
}
