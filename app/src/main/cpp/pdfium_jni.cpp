// Komiho (2026-11-05): PDF 内嵌图直通 —— PDFium 对象 API 的薄 JNI 包装。
//
// 为什么自己写这一层：`io.legere:pdfiumandroid` 的 Kotlin API 只有「渲染 / 文本」，
// 没暴露 fpdf_edit 的对象接口。PDFium 本体（AAR 里的 libpdfium.so）导出了
// FPDFPage_GetObject / FPDFImageObj_GetImageDataRaw —— 这里用 dlopen + dlsym 直接调，
// 拿到 PDF 里**嵌入的原始压缩流**（DCTDecode 时就是原始 JPEG 字节），0 次重采样、
// 0 次重编码；系统 PdfRenderer 给不了这种东西。
//
// ⚠️ 2026-11-05 连续三次 native 崩溃（22:56:48 / 23:29:3x / 23:49:2x）都发生在打开 PDF
// 后的 probe 阶段。排查结论：只保留**最基础**的对象接口，几何判断也搬进内存里做。
//   · 不再调 FPDFPageObj_GetMatrix（experimental，且已确认崩溃就发生在这类扩展调用上）；
//     改为「页面只有一张图」作为直通前提；
//   · 不再调 FPDFImageObj_GetImageMetadata（结构体布局随版本变化，写越界即炸栈）；
//     CMYK/旋转/跨页全部由**解析 JPEG 头**判断（宽高比与页面比例比对）；
//   · 不再递归 FPDFFormObj_*（experimental，签名不稳）。
//
// 诊断信息的传输：native 日志只进 logcat，而应用内导出的日志读的是 Kotlin 的 DiagLog
// 缓冲 —— 所以这里不再依赖 __android_log_print，把统计通过 IntArray 回传给 Kotlin，
// 由 Kotlin 用 DiagLog 记（`probe page=…` / `raw page=…` / `selftest[..]`）。
//
// 设计约束：
//  1. 任何一步失败都返回 0/null/false，Kotlin 侧回落「自写解析器 → PDFium 渲染」；
//  2. 用独立文档句柄（自己的 FPDF_LoadDocument），不反射 legere 的内部指针；
//  3. PDFium 全局非线程安全 —— JNI 内一把 mutex，Kotlin 侧 PdfiumLock 再串行化两个入口；
//  4. 只认「整页单图 + 纯 JPEG + 比例吻合」：多图页、水印页、JPX/JBIG2/Flate 一律渲染。
#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <new>
#include <string>

#define LOG_TAG "KomihoPdfiumX"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

// PDFium 句柄一律是不透明指针，不引入它的头文件（AAR 只带 .so，没带 public headers）。
using FPDF_DOCUMENT = void*;
using FPDF_PAGE = void*;
using FPDF_PAGEOBJECT = void*;

// FPDF_PAGEOBJ_IMAGE
constexpr int kPageObjImage = 3;

// 单张图上限 256MB：超过就按异常处理，回落渲染。
constexpr uint32_t kMaxRawBytes = 256U * 1024U * 1024U;

// JPEG 宽高比与页面宽高比允许的偏差（白边/水印会让两者略有出入）。
constexpr float kMaxAspectDelta = 0.10f;

struct Api {
    void (*InitLibrary)();
    FPDF_DOCUMENT (*LoadDocument)(const char* path, const char* password);
    void (*CloseDocument)(FPDF_DOCUMENT doc);
    int (*GetPageCount)(FPDF_DOCUMENT doc);
    FPDF_PAGE (*LoadPage)(FPDF_DOCUMENT doc, int index);
    void (*ClosePage)(FPDF_PAGE page);
    float (*GetPageWidthF)(FPDF_PAGE page);
    float (*GetPageHeightF)(FPDF_PAGE page);
    int (*PageCountObjects)(FPDF_PAGE page);
    FPDF_PAGEOBJECT (*PageGetObject)(FPDF_PAGE page, int index);
    int (*PageObjGetType)(FPDF_PAGEOBJECT obj);
    // 返回类型与 buflen 都是 PDFium 的 `unsigned long`（arm64 上是 64 位）。
    unsigned long (*ImageObjGetDataRaw)(FPDF_PAGEOBJECT obj, void* buffer, unsigned long buflen);
};

Api g_api{};
bool g_ready = false;
std::once_flag g_once;
std::mutex g_lock;

struct Ctx {
    FPDF_DOCUMENT doc;
};

template <typename T>
bool bindSymbol(void* lib, const char* name, T& out) {
    out = reinterpret_cast<T>(dlsym(lib, name));
    if (out == nullptr) {
        LOGW("missing pdfium symbol: %s", name);
        return false;
    }
    return true;
}

// 惰性绑定：libpdfium.so 由 io.legere:pdfiumandroid-core 的 AAR 提供，运行期一定在
// native lib 目录里（且 legere 已经加载过，dlopen 直接命中已有 handle）。
void ensureLoaded() {
    std::call_once(g_once, [] {
        const char* kCandidates[] = {"libpdfium.so", "libpdfiumandroid.so"};
        void* lib = nullptr;
        for (const char* name : kCandidates) {
            lib = dlopen(name, RTLD_LAZY | RTLD_LOCAL);
            if (lib != nullptr) break;
        }
        if (lib == nullptr) {
            const char* err = dlerror();
            LOGW("dlopen libpdfium failed: %s (passthrough disabled)", err != nullptr ? err : "?");
            return;
        }
        bool ok = bindSymbol(lib, "FPDF_InitLibrary", g_api.InitLibrary) &&
            bindSymbol(lib, "FPDF_LoadDocument", g_api.LoadDocument) &&
            bindSymbol(lib, "FPDF_CloseDocument", g_api.CloseDocument) &&
            bindSymbol(lib, "FPDF_GetPageCount", g_api.GetPageCount) &&
            bindSymbol(lib, "FPDF_LoadPage", g_api.LoadPage) &&
            bindSymbol(lib, "FPDF_ClosePage", g_api.ClosePage) &&
            bindSymbol(lib, "FPDF_GetPageWidthF", g_api.GetPageWidthF) &&
            bindSymbol(lib, "FPDF_GetPageHeightF", g_api.GetPageHeightF) &&
            bindSymbol(lib, "FPDFPage_CountObjects", g_api.PageCountObjects) &&
            bindSymbol(lib, "FPDFPage_GetObject", g_api.PageGetObject) &&
            bindSymbol(lib, "FPDFPageObj_GetType", g_api.PageObjGetType) &&
            bindSymbol(lib, "FPDFImageObj_GetImageDataRaw", g_api.ImageObjGetDataRaw);
        if (!ok) {
            LOGW("pdfium symbols incomplete (passthrough disabled)");
            return;
        }
        // 幂等：legere 的 PdfiumCore 已经 init 过，PDFium 内部有 static 保护。
        // 绝不调 FPDF_DestroyLibrary —— 那会把 legere 正在用的库拆掉。
        g_api.InitLibrary();
        g_ready = true;
        LOGD("pdfium object api ready");
    });
}

/**
 * 页面里「唯一的那个 image 对象」。
 *
 * 直通前提从「几何覆盖整页」退化为「本页只有一张图」：不再需要 FPDFPageObj_GetMatrix
 * （已确认崩溃就发生在扩展对象 API 上）。多图页（拼页/水印/分栏）一律返回 nullptr → 渲染。
 */
FPDF_PAGEOBJECT singlePageImage(FPDF_PAGE page, int* objects, int* images) {
    *objects = 0;
    *images = 0;
    if (page == nullptr) return nullptr;
    const int count = g_api.PageCountObjects(page);
    if (count <= 0 || count > 4096) return nullptr;
    *objects = count;

    FPDF_PAGEOBJECT only = nullptr;
    for (int i = 0; i < count; i++) {
        FPDF_PAGEOBJECT obj = g_api.PageGetObject(page, i);
        if (obj == nullptr) continue;
        if (g_api.PageObjGetType(obj) != kPageObjImage) continue;
        (*images)++;
        if (*images > 1) return nullptr; // 多图页：只抽一张会丢内容
        only = obj;
    }
    return *images == 1 ? only : nullptr;
}

/** 长度归一化 —— 规避 `FPDF_DWORD` 的 32/64 位歧义（返回值高位可能是寄存器残留）。 */
unsigned long normalizeLen(unsigned long raw) {
    if (raw >= 4 && raw <= kMaxRawBytes) return raw;
    const unsigned long low = raw & 0xFFFFFFFFUL;
    if (low >= 4 && low <= kMaxRawBytes) return low;
    return 0;
}

struct JpegHeader {
    int comps = 0;// 1=灰度 3=YCbCr 4=CMYK
    int width = 0;
    int height = 0;
    bool hasEoi = false;
};

/** 只解析 JPEG 头（SOI/EOI/SOF），不依赖任何 PDFium 结构体。 */
JpegHeader parseJpeg(const uint8_t* d, size_t n) {
    JpegHeader h;
    if (n < 4 || d[0] != 0xFF || d[1] != 0xD8) return h;

    // 尾部允许 0/换行填充，但必须找得到 EOI —— 截断的 JPEG 解码会失败直接显示错误页。
    size_t end = n;
    while (end > 2 && (d[end - 1] == 0x00 || d[end - 1] == 0x0A ||
                       d[end - 1] == 0x0D || d[end - 1] == 0x20)) {
        end--;
    }
    h.hasEoi = end >= 4 && d[end - 2] == 0xFF && d[end - 1] == 0xD9;

    size_t i = 2; // 跳过 SOI
    while (i + 4 <= n) {
        if (d[i] != 0xFF) {
            i++;
            continue;
        }
        const uint8_t marker = d[i + 1];
        if (marker == 0xD8 || marker == 0xD9 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
            i += 2;
            continue;
        }
        const size_t seg = (static_cast<size_t>(d[i + 2]) << 8) | d[i + 3];
        if (seg < 2 || i + 2 + seg > n) break;
        // SOF0..SOF15（C4=DHT、C8=JPG、CC=DAC 不是 SOF）
        if (marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
            if (seg >= 8) {
                h.height = (d[i + 5] << 8) | d[i + 6];
                h.width = (d[i + 7] << 8) | d[i + 8];
                h.comps = d[i + 9];
            }
            break;
        }
        i += 2 + seg;
    }
    return h;
}

bool aspectMatches(const JpegHeader& h, float pageW, float pageH) {
    if (h.comps != 1 && h.comps != 3) return false; // 4=CMYK 会偏色；未知分量同样不抽
    if (h.width <= 0 || h.height <= 0 || pageW <= 1.0f || pageH <= 1.0f) return false;
    const float pageRatio = pageW / pageH;
    const float imgRatio = static_cast<float>(h.width) / static_cast<float>(h.height);
    const float d1 = std::fabs(imgRatio - pageRatio) / pageRatio;
    const float d2 = std::fabs(1.0f / imgRatio - pageRatio) / pageRatio; // 页面 /Rotate 90
    return std::fmin(d1, d2) <= kMaxAspectDelta;
}

/** 打开后的基础 API 自检，摘要文本回传给 Kotlin 记日志（native 日志进不了应用内缓冲）。 */
std::string selfTest(FPDF_DOCUMENT doc) {
    char buf[160];
    if (doc == nullptr || g_api.GetPageCount(doc) <= 0) return "pages=0";
    FPDF_PAGE page = g_api.LoadPage(doc, 0);
    if (page == nullptr) return "LoadPage(0)=null";
    int objects = 0;
    int images = 0;
    singlePageImage(page, &objects, &images);
    const float w = g_api.GetPageWidthF(page);
    const float h = g_api.GetPageHeightF(page);
    g_api.ClosePage(page);
    snprintf(buf, sizeof(buf), "objs=%d imgs=%d page=%.0fx%.0f", objects, images, w, h);
    return buf;
}

void fillStats(JNIEnv* env, jintArray array, int32_t a, int32_t b, int32_t c) {
    if (array == nullptr || env->GetArrayLength(array) < 3) return;
    jint values[3] = {a, b, c};
    env->SetIntArrayRegion(array, 0, 3, values);
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_eu_kanade_tachiyomi_util_pdf_PdfiumExtractor_nativeOpen(
        JNIEnv* env, jobject, jstring jPath, jstring jPassword) {
    ensureLoaded();
    if (!g_ready || jPath == nullptr) return 0;

    const char* path = env->GetStringUTFChars(jPath, nullptr);
    if (path == nullptr) return 0;
    const char* password = nullptr;
    if (jPassword != nullptr) password = env->GetStringUTFChars(jPassword, nullptr);

    FPDF_DOCUMENT doc = nullptr;
    int pages = 0;
    {
        std::lock_guard<std::mutex> guard(g_lock);
        doc = g_api.LoadDocument(path, password);
        if (doc != nullptr) pages = g_api.GetPageCount(doc);
    }

    if (password != nullptr) env->ReleaseStringUTFChars(jPassword, password);
    env->ReleaseStringUTFChars(jPath, path);

    if (doc == nullptr) {
        LOGW("LoadDocument failed (passthrough disabled for this file)");
        return 0;
    }
    auto* ctx = new (std::nothrow) Ctx{doc};
    if (ctx == nullptr) {
        std::lock_guard<std::mutex> guard(g_lock);
        g_api.CloseDocument(doc);
        return 0;
    }
    LOGD("open ok pages=%d", pages);
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT jstring JNICALL
Java_eu_kanade_tachiyomi_util_pdf_PdfiumExtractor_nativeSelfTest(JNIEnv* env, jobject, jlong handle) {
    auto* ctx = reinterpret_cast<Ctx*>(handle);
    if (!g_ready || ctx == nullptr || ctx->doc == nullptr) return nullptr;
    std::lock_guard<std::mutex> guard(g_lock);
    return env->NewStringUTF(selfTest(ctx->doc).c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_eu_kanade_tachiyomi_util_pdf_PdfiumExtractor_nativeClose(JNIEnv*, jobject, jlong handle) {
    auto* ctx = reinterpret_cast<Ctx*>(handle);
    if (ctx == nullptr) return;
    {
        std::lock_guard<std::mutex> guard(g_lock);
        if (ctx->doc != nullptr) g_api.CloseDocument(ctx->doc);
        ctx->doc = nullptr;
    }
    delete ctx;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_eu_kanade_tachiyomi_util_pdf_PdfiumExtractor_nativeProbe(
        JNIEnv* env, jobject, jlong handle, jint index, jintArray jStats) {
    auto* ctx = reinterpret_cast<Ctx*>(handle);
    if (!g_ready || ctx == nullptr || ctx->doc == nullptr || index < 0) return JNI_FALSE;

    int objects = 0;
    int images = 0;
    FPDF_PAGEOBJECT obj = nullptr;
    {
        std::lock_guard<std::mutex> guard(g_lock);
        FPDF_PAGE page = g_api.LoadPage(ctx->doc, index);
        if (page == nullptr) {
            fillStats(env, jStats, -1, 0, 0);
            return JNI_FALSE;
        }
        obj = singlePageImage(page, &objects, &images);
        g_api.ClosePage(page);
    }
    fillStats(env, jStats, objects, images, 0);
    return obj != nullptr ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_eu_kanade_tachiyomi_util_pdf_PdfiumExtractor_nativeRawJpeg(
        JNIEnv* env, jobject, jlong handle, jint index, jintArray jStats) {
    auto* ctx = reinterpret_cast<Ctx*>(handle);
    if (!g_ready || ctx == nullptr || ctx->doc == nullptr || index < 0) return nullptr;

    unsigned long len = 0;
    unsigned long copied = 0;
    int objects = 0;
    int images = 0;
    int comps = 0;
    float pageW = 0.0f;
    float pageH = 0.0f;
    uint8_t* buffer = nullptr;

    {
        std::lock_guard<std::mutex> guard(g_lock);
        FPDF_PAGE page = g_api.LoadPage(ctx->doc, index);
        if (page == nullptr) {
            fillStats(env, jStats, -1, 0, 0);
            return nullptr;
        }
        pageW = g_api.GetPageWidthF(page);
        pageH = g_api.GetPageHeightF(page);
        FPDF_PAGEOBJECT obj = singlePageImage(page, &objects, &images);
        if (obj != nullptr) {
            len = normalizeLen(g_api.ImageObjGetDataRaw(obj, nullptr, 0));
            if (len != 0) {
                buffer = static_cast<uint8_t*>(std::malloc(static_cast<size_t>(len)));
                if (buffer != nullptr) {
                    copied = normalizeLen(
                        g_api.ImageObjGetDataRaw(obj, buffer, static_cast<unsigned long>(len)));
                }
            }
        }
        g_api.ClosePage(page);
    }

    if (buffer == nullptr || copied != len) {
        if (buffer != nullptr) std::free(buffer);
        fillStats(env, jStats, objects, images, 0);
        return nullptr;
    }

    const JpegHeader header = parseJpeg(buffer, static_cast<size_t>(len));
    comps = header.comps;
    const bool ok = header.hasEoi && aspectMatches(header, pageW, pageH);

    jbyteArray out = nullptr;
    if (ok) {
        out = env->NewByteArray(static_cast<jsize>(len));
        if (out != nullptr) {
            env->SetByteArrayRegion(out, 0, static_cast<jsize>(len),
                                    reinterpret_cast<const jbyte*>(buffer));
        }
    }
    std::free(buffer);
    // stats: [0]=objects [1]=images [2]=JPEG 分量数（0=头解析失败，4=CMYK，1/3 才是可直通）
    fillStats(env, jStats, objects, images, comps);
    return out;
}
