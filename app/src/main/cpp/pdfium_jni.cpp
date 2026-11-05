// Komiho (2026-11-05): PDF 内嵌图直通 —— PDFium 对象 API 的薄 JNI 包装。
//
// 为什么自己写这一层：`io.legere:pdfiumandroid` 的 Kotlin API 只有「渲染 / 文本」，
// 没暴露 fpdf_edit 的对象接口。PDFium 本体（AAR 里的 libpdfium.so）导出了
// FPDFPage_GetObject / FPDFPageObj_GetMatrix / FPDFImageObj_GetImageDataRaw —— 这里用
// dlopen + dlsym 直接调它们，拿到 PDF 里**嵌入的原始压缩流**（DCTDecode 时就是原始
// JPEG 字节），0 次重采样、0 次重编码；系统 PdfRenderer 给不了这种东西。
//
// ⚠️ 2026-11-05 23:29 两次 native 崩溃都落在 openStream → probe 里，且没有打出任何
// `probe page=` 日志 —— 说明死在 findFullPageImage 内部。可疑项按「非基础 API」收敛：
//   · 去掉 FPDFImageObj_GetImageMetadata（结构体布局随版本变化，写越界会炸栈），
//     CMYK 改为直接解析 JPEG 的 SOF 段分量数判断；
//   · 去掉 FPDFFormObj_CountObjects/GetObject（experimental，签名不稳），不再递归
//     Form XObject —— 这类页交给「渲染」或自写解析器，不再冒崩溃风险；
//   · probe 只做几何（LoadPage / CountObjects / GetObject / GetType / GetMatrix），
//     不碰任何数据流；原始字节只在 nativeRawJpeg 里读一次。
//   · nativeOpen 后跑一次 selftest（LoadPage(0) + 遍历对象类型），基础 API 有问题会
//     在打开时就暴露，不用等翻页。
//
// 设计约束（破一条就会影响正常阅读）：
//  1. 任何一步失败都返回 0/null/false，Kotlin 侧立刻回落「自写解析器 → PDFium 渲染」；
//  2. 用独立文档句柄（自己的 FPDF_LoadDocument），不去反射 legere 的内部指针；
//  3. PDFium 全局非线程安全 —— JNI 内一把 mutex，Kotlin 侧还有 PdfiumLock 串行化
//     渲染与抽取两个入口；
//  4. 只认「铺满整页的 JPEG」：多图拼页、水印/logo 页、JPX/JBIG2/Flate 一律不抽。
#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>

#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <new>

#define LOG_TAG "KomihoPdfiumX"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

// PDFium 句柄一律是不透明指针，不引入它的头文件（AAR 只带 .so，没带 public headers）。
using FPDF_DOCUMENT = void*;
using FPDF_PAGE = void*;
using FPDF_PAGEOBJECT = void*;

struct FS_MATRIX {
    float a;
    float b;
    float c;
    float d;
    float e;
    float f;
};

// FPDF_PAGEOBJ_IMAGE
constexpr int kPageObjImage = 3;

// 单张图上限 256MB：超过就按异常处理，回落渲染。
constexpr uint32_t kMaxRawBytes = 256U * 1024U * 1024U;

// 主图必须覆盖页面的比例（面积）。0.75 ≈ 边长 87%：允许扫描件四周有白边，
// 又能把「半页图」（0.5）挡在外面。
constexpr float kMinCoverRatio = 0.75f;
// 主图面积上限：跨页图（靠 /Clip 只显示一半）尺寸约 2.0，必须挡掉。
constexpr float kMaxCoverRatio = 1.35f;
// 除主图之外的图（水印/角标）最多允许占的面积。
constexpr float kMaxOtherCover = 0.10f;
// 主图中心与页面中心的最大偏移（按页面尺寸归一化）。
constexpr float kMaxCenterOffset = 0.30f;

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
    int (*PageObjGetMatrix)(FPDF_PAGEOBJECT obj, FS_MATRIX* matrix);
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
            bindSymbol(lib, "FPDFPageObj_GetMatrix", g_api.PageObjGetMatrix) &&
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

struct BestImage {
    FPDF_PAGEOBJECT obj = nullptr;
    float bestCover = 0.0f;
    float totalCover = 0.0f;
    // 诊断用：本页扫到多少个对象、其中多少张图。
    int objects = 0;
    int images = 0;
};

// 一张图对页面的覆盖率（面积）。
//
// ⚠️ 图像 XObject 的坐标系是**单位正方形**（0..1）：FPDFPageObj_GetMatrix 已经把
// 「单位图像」映射到页面矩形，因此渲染尺寸 = 基向量长度，**不能乘像素宽高**
// （乘了会得到 像素×页面 的量级，cover 上百万，所有页都被判成非整页图）。
void considerImage(FPDF_PAGEOBJECT obj, float pw, float ph, BestImage& out) {
    FS_MATRIX m{};
    if (!g_api.PageObjGetMatrix(obj, &m)) return;

    // 旋转页（/Rotate 90）的映射落在 b/c 上，用基向量长度同样成立。
    const float w = std::sqrt(m.a * m.a + m.b * m.b);
    const float h = std::sqrt(m.c * m.c + m.d * m.d);
    if (!(w > 1.0f) || !(h > 1.0f)) return;

    const float cover = (w / pw) * (h / ph);
    if (!(cover > 0.0f) || cover > kMaxCoverRatio) return;

    // 位置必须大致居中：跨页图被切成两半的 PDF，每半张尺寸都等于整页，
    // 只靠面积会误判 → 直通后这一页显示的是隔壁页的内容。
    const float cx = 0.5f * (m.a + m.c) + m.e;
    const float cy = 0.5f * (m.b + m.d) + m.f;
    if (std::fabs(cx - pw * 0.5f) / pw > kMaxCenterOffset) return;
    if (std::fabs(cy - ph * 0.5f) / ph > kMaxCenterOffset) return;

    out.totalCover += cover;
    if (cover > out.bestCover) {
        out.bestCover = cover;
        out.obj = obj;
    }
}

// 页面里「铺满整页的那张图」；没有就返回 nullptr（交给渲染）。
// [stats] 非空时带出本页扫描统计，供诊断日志用。
FPDF_PAGEOBJECT findFullPageImage(FPDF_PAGE page, BestImage* stats) {
    BestImage best;
    FPDF_PAGEOBJECT result = nullptr;

    if (page != nullptr) {
        const float pw = g_api.GetPageWidthF(page);
        const float ph = g_api.GetPageHeightF(page);
        if (pw > 1.0f && ph > 1.0f) {
            const int count = g_api.PageCountObjects(page);
            if (count > 0 && count <= 4096) {
                best.objects = count;
                for (int i = 0; i < count; i++) {
                    FPDF_PAGEOBJECT obj = g_api.PageGetObject(page, i);
                    if (obj == nullptr) continue;
                    if (g_api.PageObjGetType(obj) != kPageObjImage) continue;
                    best.images++;
                    considerImage(obj, pw, ph, best);
                }
            }
            // 主图必须铺满整页，且不能还有别的图占着版面（多图拼页只抽一张会丢内容）。
            if (best.obj != nullptr &&
                best.bestCover >= kMinCoverRatio &&
                best.totalCover - best.bestCover <= kMaxOtherCover) {
                result = best.obj;
            }
        }
    }

    if (stats != nullptr) *stats = best;
    return result;
}

/** 长度归一化 —— 规避 `FPDF_DWORD` 的 32/64 位歧义（返回值高位可能是寄存器残留）。 */
unsigned long normalizeLen(unsigned long raw) {
    if (raw >= 4 && raw <= kMaxRawBytes) return raw;
    const unsigned long low = raw & 0xFFFFFFFFUL;
    if (low >= 4 && low <= kMaxRawBytes) return low;
    return 0;
}

/** JPEG 的 SOF 段里的分量数（1=灰度，3=YCbCr，4=CMYK）；解析不出返回 0。 */
int jpegComponentCount(const uint8_t* d, size_t n) {
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
        if (seg < 2 || i + 2 + seg > n) return 0;
        // SOF0..SOF15（C4=DHT、C8=JPG、CC=DAC 不是 SOF）
        if (marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
            if (seg < 8) return 0;
            return d[i + 9];
        }
        i += 2 + seg;
    }
    return 0;
}

/** 原始流是不是能直接交给解码器的 JPEG：SOI + 完整 EOI + 非 CMYK。 */
bool isPlainJpeg(const uint8_t* d, size_t n) {
    if (n < 4 || d[0] != 0xFF || d[1] != 0xD8) return false;

    // 尾部允许 0/换行填充，但必须找得到 EOI —— 截断的 JPEG 解码会失败直接显示错误页。
    size_t end = n;
    while (end > 2 && (d[end - 1] == 0x00 || d[end - 1] == 0x0A ||
                       d[end - 1] == 0x0D || d[end - 1] == 0x20)) {
        end--;
    }
    if (end < 4 || d[end - 2] != 0xFF || d[end - 1] != 0xD9) return false;

    const int comps = jpegComponentCount(d, n);
    // 4 分量（CMYK）会被解码器按 RGB 硬转 → 偏色，交给 PDFium 渲染才正确。
    return comps == 1 || comps == 3;
}

/**
 * 打开后的基础 API 自检：LoadPage(0) → CountObjects → GetObject → GetType → GetMatrix。
 *
 * 目的不是"验证功能"，而是**把崩溃点钉在打开阶段**：日志最后一行就是最后成功的调用，
 * 不用等翻页、也不用猜是哪个符号。任何一步失败都只记日志，不影响可用性。
 */
void selfTest(FPDF_DOCUMENT doc) {
    if (doc == nullptr || g_api.GetPageCount(doc) <= 0) return;
    LOGD("selftest begin");
    FPDF_PAGE page = g_api.LoadPage(doc, 0);
    if (page == nullptr) {
        LOGW("selftest: LoadPage(0) failed");
        return;
    }
    const int count = g_api.PageCountObjects(page);
    int images = 0;
    bool matrixLogged = false;
    for (int i = 0; i < count && i < 512; i++) {
        FPDF_PAGEOBJECT obj = g_api.PageGetObject(page, i);
        if (obj == nullptr) continue;
        if (g_api.PageObjGetType(obj) != kPageObjImage) continue;
        images++;
        if (!matrixLogged) {
            FS_MATRIX m{};
            const int got = g_api.PageObjGetMatrix(obj, &m);
            matrixLogged = true;
            LOGD("selftest: matrix got=%d a=%.3f b=%.3f c=%.3f d=%.3f e=%.1f f=%.1f",
                 got, m.a, m.b, m.c, m.d, m.e, m.f);
        }
    }
    const float w = g_api.GetPageWidthF(page);
    const float h = g_api.GetPageHeightF(page);
    g_api.ClosePage(page);
    LOGD("selftest end objs=%d imgs=%d page=%.1fx%.1f", count, images, w, h);
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

    {
        std::lock_guard<std::mutex> guard(g_lock);
        selfTest(doc);
    }
    return reinterpret_cast<jlong>(ctx);
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
        JNIEnv*, jobject, jlong handle, jint index) {
    auto* ctx = reinterpret_cast<Ctx*>(handle);
    if (!g_ready || ctx == nullptr || ctx->doc == nullptr || index < 0) return JNI_FALSE;

    std::lock_guard<std::mutex> guard(g_lock);
    FPDF_PAGE page = g_api.LoadPage(ctx->doc, index);
    if (page == nullptr) {
        LOGW("probe page=%d LoadPage failed", index);
        return JNI_FALSE;
    }
    BestImage stats;
    FPDF_PAGEOBJECT obj = findFullPageImage(page, &stats);
    g_api.ClosePage(page);
    const bool ok = obj != nullptr;
    // 诊断：判否时看 cover/images 就知道卡在哪道门（几何问题 vs 多图页 vs 无图页）。
    LOGD("probe page=%d objs=%d imgs=%d cover=%.2f total=%.2f full=%d",
         index, stats.objects, stats.images, stats.bestCover, stats.totalCover, ok ? 1 : 0);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_eu_kanade_tachiyomi_util_pdf_PdfiumExtractor_nativeRawJpeg(
        JNIEnv* env, jobject, jlong handle, jint index) {
    auto* ctx = reinterpret_cast<Ctx*>(handle);
    if (!g_ready || ctx == nullptr || ctx->doc == nullptr || index < 0) return nullptr;

    std::lock_guard<std::mutex> guard(g_lock);
    FPDF_PAGE page = g_api.LoadPage(ctx->doc, index);
    if (page == nullptr) return nullptr;

    FPDF_PAGEOBJECT obj = findFullPageImage(page, nullptr);
    if (obj == nullptr) {
        g_api.ClosePage(page);
        return nullptr;
    }

    const unsigned long len = normalizeLen(g_api.ImageObjGetDataRaw(obj, nullptr, 0));
    if (len == 0) {
        g_api.ClosePage(page);
        return nullptr;
    }
    auto* buffer = static_cast<uint8_t*>(std::malloc(static_cast<size_t>(len)));
    if (buffer == nullptr) {
        g_api.ClosePage(page);
        return nullptr;
    }
    const unsigned long copied = normalizeLen(g_api.ImageObjGetDataRaw(obj, buffer, len));
    g_api.ClosePage(page);

    const bool ok = copied == len && isPlainJpeg(buffer, static_cast<size_t>(len));
    LOGD("raw page=%d len=%lu copied=%lu ok=%d", index, len, copied, ok ? 1 : 0);
    if (!ok) {
        std::free(buffer);
        return nullptr;
    }

    jbyteArray out = env->NewByteArray(static_cast<jsize>(len));
    if (out != nullptr) {
        env->SetByteArrayRegion(out, 0, static_cast<jsize>(len),
                                reinterpret_cast<const jbyte*>(buffer));
    }
    std::free(buffer);
    return out;
}
