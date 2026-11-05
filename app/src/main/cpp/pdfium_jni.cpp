// Komiho (2026-11-05): PDF 内嵌图直通 —— PDFium 对象 API 的薄 JNI 包装。
//
// 为什么自己写这一层：`io.legere:pdfiumandroid` 的 Kotlin API 只有「渲染 / 文本」，
// 没暴露 fpdf_edit 的对象接口。而 PDFium 本体（AAR 里的 libpdfium.so）**动态导出了**
// FPDFPage_GetObject / FPDFPageObj_GetMatrix / FPDFImageObj_GetImageDataRaw —— 实测
// arm64 版 792 个导出符号里全都有。这里用 dlopen + dlsym 直接调它们：
//
//   FPDF_LoadPage → FPDFPage_GetObject → FPDFImageObj_GetImageDataRaw
//
// 拿到的是 PDF 里**嵌入的原始压缩流**：DCTDecode（JPEG）时就是原始 JPEG 字节，可直接
// 交给 Coil 解码，0 次重采样、0 次重编码；系统 PdfRenderer 给不了这种东西（它只有
// render() 到位图）。
//
// 设计约束（破一条就会影响正常阅读）：
//  1. 任何一步失败都返回 0/null/false，Kotlin 侧立刻回落「自写解析器 → PDFium 渲染」，
//     这里绝不向 Java 抛异常；
//  2. 用独立文档句柄（自己的 FPDF_LoadDocument），不去反射 legere 的内部指针；
//  3. PDFium 全局非线程安全 —— 所有调用串行化在一把 mutex 里；
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

struct FPDF_IMAGEOBJ_METADATA {
    unsigned int width;
    unsigned int height;
    float horizontal_dpi;
    float vertical_dpi;
    unsigned int bits_per_pixel;
    int colorspace;
    int marked_content_id;
};

// FPDF_PAGEOBJ_IMAGE / FPDF_PAGEOBJ_FORM
constexpr int kPageObjImage = 3;
constexpr int kPageObjForm = 5;

// FPDF_COLORSPACE_DEVICECMYK：4 分量 JPEG 直通会被解码器按 RGB 硬转 → 偏色，
// 这类页交给 PDFium 渲染（它能做正确的色彩转换）。其余色彩空间照常抽。
constexpr int kColorSpaceDeviceCmyk = 3;

// 单张图上限 256MB：超过就按异常处理，回落渲染。真见过 100MB+ 的整页扫描图。
constexpr uint32_t kMaxRawBytes = 256U * 1024U * 1024U;

// 主图必须覆盖页面的比例（面积）。低于此值说明是拼页/分栏，直通会丢内容。
// 0.75 ≈ 边长 87%：允许扫描件四周有白边，又能把「半页图」（0.5）挡在外面。
constexpr float kMinCoverRatio = 0.75f;
// 除主图之外的图（水印/角标）最多允许占的面积；超过就认为是多图排版，不抽。
constexpr float kMaxOtherCover = 0.10f;
// 主图面积上限（相对页面）。整页图通常 0.95~1.1；明显更大说明这张图跨了两页、
// 页面靠 /Clip 只显示其中一半（跨页 PDF 的常见做法）—— 抽整张会显示错内容，拒绝。
constexpr float kMaxCoverRatio = 1.35f;
// 主图中心与页面中心的最大偏移（按页面尺寸归一化）。跨页图的半张（尺寸够大但整体
// 偏出页面中心）必须挡掉 —— 否则那一页会显示相邻页的内容。
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
    int (*ImageObjGetMetadata)(FPDF_PAGEOBJECT obj, FPDF_PAGE page, FPDF_IMAGEOBJ_METADATA* md);
    // 注意：返回类型与 buflen 都是 PDFium 的 `unsigned long`（arm64 上是 64 位）。
    // 写成 uint32_t 会让高位在半寄存器约定下不确定，宁可照抄真实签名。
    unsigned long (*ImageObjGetDataRaw)(FPDF_PAGEOBJECT obj, void* buffer, unsigned long buflen);
    // 可选（experimental API）：整页图包在 /Subtype /Form 里的 PDF 很常见，
    // 没有这两个符号就只扫页面层对象，功能不受影响（少抽一些文件而已）。
    int (*FormObjCountObjects)(FPDF_PAGEOBJECT form);
    FPDF_PAGEOBJECT (*FormObjGetObject)(FPDF_PAGEOBJECT form, unsigned long index);
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
// native lib 目录里；这里不直接链接它（链接了就得在 CMake 里找到它，没必要）。
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
            bindSymbol(lib, "FPDFImageObj_GetImageMetadata", g_api.ImageObjGetMetadata) &&
            bindSymbol(lib, "FPDFImageObj_GetImageDataRaw", g_api.ImageObjGetDataRaw);
        if (!ok) {
            LOGW("pdfium symbols incomplete (passthrough disabled)");
            return;
        }
        // 可选符号：拿不到就不做 Form 递归（只是少抽一类文件，绝不影响其它路径）。
        g_api.FormObjCountObjects = reinterpret_cast<int (*)(FPDF_PAGEOBJECT)>(
            dlsym(lib, "FPDFFormObj_CountObjects"));
        g_api.FormObjGetObject = reinterpret_cast<FPDF_PAGEOBJECT (*)(FPDF_PAGEOBJECT, unsigned long)>(
            dlsym(lib, "FPDFFormObj_GetObject"));

        // 幂等：legere 的 PdfiumCore 已经 init 过，PDFium 内部有 static 保护。
        // 绝不调 FPDF_DestroyLibrary —— 那会把 legere 正在用的库拆掉。
        g_api.InitLibrary();
        g_ready = true;
        LOGD("pdfium object api ready");
    });
}

// 矩阵合成：先 inner 再 outer（与 PDF 的 CTM 顺序一致）。
// [a b c d e f] 表示 x' = a*x + c*y + e，y' = b*x + d*y + f。
FS_MATRIX composeMatrix(const FS_MATRIX& outer, const FS_MATRIX& inner) {
    FS_MATRIX r{};
    r.a = inner.a * outer.a + inner.b * outer.c;
    r.b = inner.a * outer.b + inner.b * outer.d;
    r.c = inner.c * outer.a + inner.d * outer.c;
    r.d = inner.c * outer.b + inner.d * outer.d;
    r.e = inner.e * outer.a + inner.f * outer.c + outer.e;
    r.f = inner.e * outer.b + inner.f * outer.d + outer.f;
    return r;
}

struct BestImage {
    FPDF_PAGEOBJECT obj = nullptr;
    float bestCover = 0.0f;
    float totalCover = 0.0f;
};

// Form XObject 递归上限：正常文档 0~1 层，给到 3 层只为容错。
constexpr int kMaxFormDepth = 3;

// 一张图对页面的覆盖率（面积）。outer = 它所在容器（页面或 Form）到页面空间的变换。
void considerImage(FPDF_PAGEOBJECT obj, FPDF_PAGE page, const FS_MATRIX& outer,
                   float pw, float ph, BestImage& out) {
    FS_MATRIX m{};
    if (!g_api.PageObjGetMatrix(obj, &m)) return;
    FPDF_IMAGEOBJ_METADATA md{};
    if (!g_api.ImageObjGetMetadata(obj, page, &md)) return;
    if (md.width == 0 || md.height == 0) return;

    const FS_MATRIX t = composeMatrix(outer, m);
    const float iw = static_cast<float>(md.width);
    const float ih = static_cast<float>(md.height);
    // 外接矩形：像素宽方向 (a,b)、高方向 (c,d)；旋转页（/Rotate 90）落在 b/c 上，同样成立。
    const float x1 = t.a * iw + t.c * ih + t.e;
    const float y1 = t.b * iw + t.d * ih + t.f;
    const float w = std::fabs(x1 - t.e);
    const float h = std::fabs(y1 - t.f);
    if (!(w > 1.0f) || !(h > 1.0f)) return;

    const float cover = (w / pw) * (h / ph);
    // 明显离谱的矩阵（异常文件）或跨页大图都不当作整页图。
    if (!(cover > 0.0f) || cover > kMaxCoverRatio) return;

    // 位置必须大致居中：跨页图被切成两半的 PDF，每半张的尺寸都等于整页，
    // 只靠面积会误判成整页图 → 直通后这一页显示的是隔壁页的内容。
    const float cx = (t.e + x1) * 0.5f;
    const float cy = (t.f + y1) * 0.5f;
    if (std::fabs(cx - pw * 0.5f) / pw > kMaxCenterOffset) return;
    if (std::fabs(cy - ph * 0.5f) / ph > kMaxCenterOffset) return;

    out.totalCover += cover;
    if (cover > out.bestCover) {
        out.bestCover = cover;
        out.obj = obj;
    }
}

// form == nullptr 表示扫页面层；否则扫该 Form XObject 的内容流。
void scanObjects(FPDF_PAGE page, FPDF_PAGEOBJECT form, const FS_MATRIX& outer,
                 float pw, float ph, int depth, BestImage& out) {
    if (depth > kMaxFormDepth) return;

    int count = 0;
    if (form == nullptr) {
        count = g_api.PageCountObjects(page);
    } else if (g_api.FormObjCountObjects != nullptr && g_api.FormObjGetObject != nullptr) {
        count = g_api.FormObjCountObjects(form);
    }
    if (count <= 0 || count > 4096) return;

    for (int i = 0; i < count; i++) {
        FPDF_PAGEOBJECT obj = form == nullptr
            ? g_api.PageGetObject(page, i)
            : g_api.FormObjGetObject(form, static_cast<unsigned long>(i));
        if (obj == nullptr) continue;
        const int type = g_api.PageObjGetType(obj);
        if (type == kPageObjImage) {
            considerImage(obj, page, outer, pw, ph, out);
        } else if (type == kPageObjForm) {
            FS_MATRIX fm{};
            if (!g_api.PageObjGetMatrix(obj, &fm)) continue;
            scanObjects(page, obj, composeMatrix(outer, fm), pw, ph, depth + 1, out);
        }
    }
}

// 页面里「铺满整页的那张图」；没有就返回 nullptr（交给渲染）。
FPDF_PAGEOBJECT findFullPageImage(FPDF_PAGE page) {
    if (page == nullptr) return nullptr;
    const float pw = g_api.GetPageWidthF(page);
    const float ph = g_api.GetPageHeightF(page);
    if (!(pw > 1.0f) || !(ph > 1.0f)) return nullptr;

    BestImage best;
    const FS_MATRIX identity{1.0f, 0.0f, 0.0f, 1.0f, 0.0f, 0.0f};
    scanObjects(page, nullptr, identity, pw, ph, 0, best);

    if (best.obj == nullptr) return nullptr;
    if (best.bestCover < kMinCoverRatio) return nullptr;
    // 还有别的图占着页面（多图拼页/分栏），只抽一张会丢内容 → 渲染。
    if (best.totalCover - best.bestCover > kMaxOtherCover) return nullptr;
    return best.obj;
}

/**
 * 长度归一化 —— 规避 `FPDF_DWORD` 的 32/64 位歧义。
 *
 * PDFium 头文件里 `FPDF_DWORD` 在不同构建下是 `uint32_t` 或 `unsigned long`；我们按
 * `unsigned long` 声明（参数安全：低 32 位总对），但**返回值**若实际是 32 位，
 * arm64 上 x0 的高 32 位可能是寄存器残留 → 读出巨大的长度。两种都兼容：先按原值，
 * 不合理就截低 32 位再判一次。
 */
unsigned long normalizeLen(unsigned long raw) {
    if (raw >= 4 && raw <= kMaxRawBytes) return raw;
    const unsigned long low = raw & 0xFFFFFFFFUL;
    if (low >= 4 && low <= kMaxRawBytes) return low;
    return 0;
}

// 原始压缩流是不是**能直接解码**的 JPEG（SOI = FF D8，且不是 CMYK）。
bool isJpegRaw(FPDF_PAGEOBJECT obj, FPDF_PAGE page) {
    if (obj == nullptr) return false;

    FPDF_IMAGEOBJ_METADATA md{};
    if (g_api.ImageObjGetMetadata(obj, page, &md) && md.colorspace == kColorSpaceDeviceCmyk) {
        return false;
    }

    const unsigned long len = normalizeLen(g_api.ImageObjGetDataRaw(obj, nullptr, 0));
    if (len == 0) return false;
    uint8_t head[2] = {0, 0};
    // 注意：这个 API 返回的是**数据总长度**（不是实际拷进 buffer 的字节数），
    // 传 buflen=2 只拷前 2 字节、返回值仍是全长 —— 所以这里比的是 >= 2。
    if (g_api.ImageObjGetDataRaw(obj, head, 2) < 2) return false;
    return head[0] == 0xFF && head[1] == 0xD8;
}

bool jpegStreamHead(const uint8_t* data, size_t size) {
    return size >= 4 && data[0] == 0xFF && data[1] == 0xD8;
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
    if (page == nullptr) return JNI_FALSE;
    FPDF_PAGEOBJECT obj = findFullPageImage(page);
    const bool ok = isJpegRaw(obj, page);
    g_api.ClosePage(page);
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

    FPDF_PAGEOBJECT obj = findFullPageImage(page);
    if (!isJpegRaw(obj, page)) {
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

    // 尾部必须是 EOI(FF D9)：流被截断的 JPEG 解码会失败 → 阅读器直接显示错误页。
    // 这种畸形文件交给 PDFium 渲染更安全（渲染能容错）。允许尾部有换行/0 填充。
    size_t end = static_cast<size_t>(len);
    while (end > 2 && (buffer[end - 1] == 0x00 || buffer[end - 1] == 0x0A ||
                       buffer[end - 1] == 0x0D || buffer[end - 1] == 0x20)) {
        end--;
    }
    const bool hasEoi = end >= 4 && buffer[end - 2] == 0xFF && buffer[end - 1] == 0xD9;

    if (copied != len || !jpegStreamHead(buffer, len) || !hasEoi) {
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
