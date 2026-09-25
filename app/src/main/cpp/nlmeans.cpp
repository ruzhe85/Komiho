// Komiho: Fast NLM (Non-Local Means) 漫画降噪 —— CPU 实现，零外部依赖。
//
// 算法等价于 OpenCV fastNlMeansDenoisingColored 的配方搬到 YCbCr 空间：
//   1) RGBA → Y / Cb / Cr（BT.601）；
//   2) 亮度平面 Y 单独做 NLM（h 较强 —— 漫画最重要的是黑色线稿/文字/轮廓）；
//   3) 色度平面 Cb+Cr 联合做 NLM（合并平方差，hColor 适中 —— 不过度抹彩色细节）；
//   4) 转回 RGB，alpha 原样保留。
//
// 「Fast」与 OpenCV 同思路：对每个搜索偏移，用行内前缀和把模板窗口(7×7)的平方差(SSD)
// 从 O(49) 次读写降到 O(7) 次查表；权重用近似 exp（Schraudolph 位技巧，~2% 相对误差，
// 远低于感知阈值），SSD 超过阈值的偏移直接跳过（exp(-24)≈4e-11，权重可忽略 ——
// 漫画大面积白底下多数偏移都命中这条快速路径，省掉 exp + 三通道累加）。
//
// 并行：按列分条带（stripe），每线程独立处理自己的列区间（条带自带 search+template
// 水平边界，读整幅平面、只写自己的列），累加器按列隔离、无锁。
// 线程数 = min(8, hardware_concurrency)。
//
// 取消：g_nlmAbort 原子标志，每个搜索偏移（约几十 ms 粒度）检查一次；置位后整页放弃，
// JNI 返回 null，Kotlin 侧回落原图。每次进入 nativeNlmDenoise 自动清零 —— 与
// Waifu2x 的 nativeClearAbortProcessing 语义一致（只打断「当前正在跑的那一次」）。

#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <thread>
#include <vector>

#define LOG_TAG "NlmDenoise"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {

std::atomic<bool> g_nlmAbort{false};

// 近似 exp(-x)（Schraudolph 位技巧）。x 必须非负；过大直接给 0，
// 否则 float→int 溢出是 UB。
inline float fastNegExp(float x) {
    if (x > 87.0f) return 0.0f;  // exp(-87) ≈ 1.6e-38
    union {
        float f;
        int32_t i;
    } u;
    u.i = static_cast<int32_t>(12102203.0f * (-x) + 1065353216.0f);
    return u.f;
}

inline int clampi(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }

// 单次 NLM 平面降噪 worker：负责输出列区间 [x0, x1) 的全部行。
//
//   A        源平面 1（亮度，或色度对的 Cb）—— 行紧致存放（W 一行）。
//   Bp       源平面 2（仅 kPair=true 时有效：Cr）。距离 = 两平面平方差之和。
//   accA/accB/sumW  全图累加器（已按自权重预置），worker 只写 [x0,x1) 列。
//
// 前缀和域：对输出列 x 的模板窗口（x-t .. x+t）做行内窗口和，前缀行从
// spanL = x0-t-1 起累计（窗口左缘最小值），到 spanR = x1+t 止。
// 行环缓冲：输出行 y 需要源行 y-t .. y+t 的前缀行；y 递增时整组行号单调右移一格，
// 故 2t+1 行的环形缓冲够用（新行覆盖恰好被淘汰的那一行）。
template <bool kPair>
void nlmWorker(const uint8_t *A, const uint8_t *Bp, int W, int H, float *accA, float *accB,
               float *sumW, float h, int t, int s, int x0, int x1, std::atomic<bool> &aborted) {
    const float h2 = h * h;
    const uint32_t cutoff = static_cast<uint32_t>(h2 * 24.0f);
    const int ringRows = 2 * t + 1;
    const int spanL = x0 - t - 1;
    const int spanR = x1 + t;
    const int span = spanR - spanL + 1;

    // 条带内源列的越界裁剪表（spanL..spanR 对所有偏移固定，只算一次）。
    std::vector<int> colIdx(static_cast<size_t>(span));
    for (int i = 0; i < span; ++i) colIdx[i] = clampi(spanL + i, 0, W - 1);

    std::vector<uint32_t> prefixRow(static_cast<size_t>(ringRows) * span);

    for (int dy = -s; dy <= s; ++dy) {
        if (g_nlmAbort.load(std::memory_order_relaxed)) {
            aborted.store(true, std::memory_order_relaxed);
            return;
        }
        for (int dx = -s; dx <= s; ++dx) {
            if (dx == 0 && dy == 0) continue;  // 自权重 = 1，已在累加器预置
            if (g_nlmAbort.load(std::memory_order_relaxed)) {
                aborted.store(true, std::memory_order_relaxed);
                return;
            }

            // 逐输出行推进；nextRow = 尚未算入环形缓冲的下一个源行。
            int nextRow = -t;
            // 行指针表：rowPtrs[(y+u+t) % ringRows] → 该源行的前缀行。
            // 在每个 y 处刷新一次（刷新只发生在行号推进时，见下）。
            const uint32_t *rows[16];  // ringRows ≤ 16（templateSize ≤ 15 的防御上限）

            for (int y = 0; y < H; ++y) {
                // 补齐 y-t .. y+t 的前缀行。
                while (nextRow <= y + t) {
                    const int slot = ((nextRow % ringRows) + ringRows) % ringRows;
                    uint32_t *prow = &prefixRow[static_cast<size_t>(slot) * span];
                    const int br = clampi(nextRow, 0, H - 1);
                    const int br2 = clampi(nextRow + dy, 0, H - 1);
                    const uint8_t *ra = A + static_cast<size_t>(br) * W;
                    const uint8_t *rb = A + static_cast<size_t>(br2) * W;
                    const uint8_t *ca = Bp + static_cast<size_t>(br) * W;
                    const uint8_t *cb = Bp + static_cast<size_t>(br2) * W;
                    uint32_t sum = 0;
                    for (int i = 0; i < span; ++i) {
                        const int ac = colIdx[i];
                        const int ac2 = clampi(spanL + i + dx, 0, W - 1);
                        const int d = static_cast<int>(ra[ac]) - static_cast<int>(rb[ac2]);
                        uint32_t dd = static_cast<uint32_t>(d * d);
                        if (kPair) {
                            const int e = static_cast<int>(ca[ac]) - static_cast<int>(cb[ac2]);
                            dd += static_cast<uint32_t>(e * e);
                        }
                        sum += dd;
                        prow[i] = sum;
                    }
                    rows[slot] = prow;
                    ++nextRow;
                }

                // 本输出行要用的 ringRows 个前缀行指针（一次性取好，内层 x 循环零取模）。
                const uint32_t *needed[16];
                for (int u = -t; u <= t; ++u) {
                    needed[u + t] = rows[(((y + u) % ringRows) + ringRows) % ringRows];
                }

                const int qyr = clampi(y + dy, 0, H - 1);
                const uint8_t *qa = A + static_cast<size_t>(qyr) * W;
                const uint8_t *qb = Bp + static_cast<size_t>(qyr) * W;
                float *accAr = accA + static_cast<size_t>(y) * W;
                float *accBr = accB + static_cast<size_t>(y) * W;
                float *sumWr = sumW + static_cast<size_t>(y) * W;

                for (int x = x0; x < x1; ++x) {
                    const int ir = x + t - spanL;  // ≥ ringRows（窗口右缘在前缀域内恒成立）
                    uint32_t ssd = 0;
                    for (int u = 0; u < ringRows; ++u) {
                        const uint32_t *prow = needed[u];
                        ssd += prow[ir] - prow[ir - ringRows];
                    }
                    if (ssd < cutoff) {
                        const int qxc = clampi(x + dx, 0, W - 1);
                        const float w = fastNegExp(static_cast<float>(ssd) / h2);
                        sumWr[x] += w;
                        accAr[x] += w * qa[qxc];
                        if (kPair) accBr[x] += w * qb[qxc];
                    }
                }
            }
        }
    }
}

// 单次 NLM 平面降噪（起线程 + 汇总）。返回 false = 被中止。
//   outA/outB：0..255 浮点结果（kPair=false 时 outB 不写）。
bool nlmPass(const std::vector<uint8_t> &A, const std::vector<uint8_t> *B, int W, int H, float h,
             int t, int s, int nThreads, std::vector<float> &outA, std::vector<float> &outB) {
    const bool pair = B != nullptr;
    const size_t plane = static_cast<size_t>(W) * H;

    // 累加器：自权重 (0,0) 预置（SSD=0 → w=1）—— sumW≥1，绝不除零。
    std::vector<float> accA(plane);
    std::vector<float> accB;
    std::vector<float> sumW(plane, 1.0f);
    for (size_t i = 0; i < plane; ++i) accA[i] = static_cast<float>(A[i]);
    if (pair) {
        accB.resize(plane);
        for (size_t i = 0; i < plane; ++i) accB[i] = static_cast<float>((*B)[i]);
    }

    std::atomic<bool> aborted{false};
    std::vector<std::thread> threads;
    const int stripe = (W + nThreads - 1) / nThreads;
    for (int tid = 0; tid < nThreads; ++tid) {
        const int x0 = tid * stripe;
        if (x0 >= W) break;
        const int x1 = std::min(x0 + stripe, W);
        threads.emplace_back([&, x0, x1]() {
            if (pair) {
                nlmWorker<true>(A.data(), B->data(), W, H, accA.data(), accB.data(), sumW.data(),
                                h, t, s, x0, x1, aborted);
            } else {
                nlmWorker<false>(A.data(), nullptr, W, H, accA.data(), nullptr, sumW.data(), h, t,
                                 s, x0, x1, aborted);
            }
        });
    }
    for (auto &th : threads) th.join();
    if (aborted.load(std::memory_order_relaxed)) return false;

    outA.resize(plane);
    if (pair) outB.resize(plane);
    for (size_t i = 0; i < plane; ++i) {
        outA[i] = accA[i] / sumW[i];
        if (pair) outB[i] = accB[i] / sumW[i];
    }
    return true;
}

// RGBA → Y/Cb/Cr（BT.601），alpha 另存。
void extractPlanes(const uint8_t *src, int W, int H, std::vector<uint8_t> &Y,
                   std::vector<uint8_t> &Cb, std::vector<uint8_t> &Cr,
                   std::vector<uint8_t> &alpha) {
    const size_t plane = static_cast<size_t>(W) * H;
    Y.resize(plane);
    Cb.resize(plane);
    Cr.resize(plane);
    alpha.resize(plane);
    for (size_t i = 0; i < plane; ++i) {
        const int r = src[i * 4 + 0];
        const int g = src[i * 4 + 1];
        const int b = src[i * 4 + 2];
        const int y = static_cast<int>(0.299f * r + 0.587f * g + 0.114f * b + 0.5f);
        const int cb = static_cast<int>(-0.168736f * r - 0.331264f * g + 0.5f * b + 128.0f + 0.5f);
        const int cr = static_cast<int>(0.5f * r - 0.418688f * g - 0.081312f * b + 128.0f + 0.5f);
        Y[i] = static_cast<uint8_t>(clampi(y, 0, 255));
        Cb[i] = static_cast<uint8_t>(clampi(cb, 0, 255));
        Cr[i] = static_cast<uint8_t>(clampi(cr, 0, 255));
        alpha[i] = src[i * 4 + 3];
    }
}

}  // namespace

extern "C" JNIEXPORT jobject JNICALL
Java_eu_kanade_tachiyomi_util_MihonSyEnhancer_nativeNlmDenoise(JNIEnv *env, jobject thiz,
                                                               jobject bitmap, jfloat h,
                                                               jfloat hColor, jint templateSize,
                                                               jint searchSize) {
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("AndroidBitmap_getInfo failed");
        return bitmap;
    }
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        LOGE("Unsupported bitmap format %d", info.format);
        return bitmap;
    }
    const int W = static_cast<int>(info.width);
    const int H = static_cast<int>(info.height);
    if (W <= 0 || H <= 0) return bitmap;

    // templateSize 防御上限：rows[]/needed[] 定长 16，ringRows = 2*(size/2)+1 ≤ 16
    // ⇒ size ≤ 15（Kotlin 恒传 7，这里是给 JNI 直调的护栏）。
    const int tsize = std::min(templateSize, 15);
    const int t = std::max(1, tsize / 2);
    const int s = std::max(1, searchSize / 2);

    void *pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("Failed to lock input pixels");
        return bitmap;
    }

    // 行紧致化（Android Bitmap stride 允许大于 W*4，见 lanczos3.cpp 同款处理）。
    std::vector<uint8_t> packed;
    const uint8_t *srcPixels = static_cast<const uint8_t *>(pixels);
    if (info.stride != static_cast<size_t>(W) * 4) {
        packed.resize(static_cast<size_t>(W) * H * 4);
        for (int y = 0; y < H; ++y) {
            std::memcpy(packed.data() + static_cast<size_t>(y) * W * 4,
                        srcPixels + static_cast<size_t>(y) * info.stride,
                        static_cast<size_t>(W) * 4);
        }
        srcPixels = packed.data();
    }

    const auto start = std::chrono::steady_clock::now();

    std::vector<uint8_t> planeY, planeCb, planeCr, planeA;
    extractPlanes(srcPixels, W, H, planeY, planeCb, planeCr, planeA);
    AndroidBitmap_unlockPixels(env, bitmap);  // 源位图像素已提取完毕

    // 每线程列条带数：min(8, 硬件线程)。NLM 是内存带宽敏感型，超过 8 收益递减。
    int nThreads = static_cast<int>(std::thread::hardware_concurrency());
    if (nThreads <= 0) nThreads = 1;
    nThreads = std::min(nThreads, 8);

    g_nlmAbort.store(false, std::memory_order_relaxed);

    // 亮度：h 较强；色度：Cb+Cr 联合、hColor 适中（OpenCV Colored 的 Lab 配方对应物）。
    std::vector<float> outY, outCb, outCr;
    if (!nlmPass(planeY, nullptr, W, H, h, t, s, nThreads, outY, outCr)) {
        LOGI("aborted (luma pass)");
        return nullptr;
    }
    std::vector<uint8_t>().swap(planeY);  // 亮度平面已消费，先释放再跑色度
    if (!nlmPass(planeCb, &planeCr, W, H, hColor, t, s, nThreads, outCb, outCr)) {
        LOGI("aborted (chroma pass)");
        return nullptr;
    }

    const auto ms =
        std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - start)
            .count();
    LOGI("denoised %dx%d h=%.1f hColor=%.1f t=%d s=%d threads=%d in %lldms", W, H, h, hColor, t, s,
         nThreads, static_cast<long long>(ms));

    // 建输出位图（与 lanczos3.cpp 同款 JNI 流程）。
    jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
    if (!bitmapClass) return bitmap;
    jmethodID createBitmapMethod = env->GetStaticMethodID(
        bitmapClass, "createBitmap", "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    jclass configClass = env->FindClass("android/graphics/Bitmap$Config");
    if (!createBitmapMethod || !configClass) return bitmap;
    jfieldID configField =
        env->GetStaticFieldID(configClass, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
    if (!configField) return bitmap;
    jobject config = env->GetStaticObjectField(configClass, configField);
    jobject outBitmap =
        env->CallStaticObjectMethod(bitmapClass, createBitmapMethod, W, H, config);
    if (env->ExceptionCheck() || !outBitmap) {
        env->ExceptionClear();
        LOGE("Failed to create output bitmap");
        return bitmap;
    }

    void *outPixels = nullptr;
    if (AndroidBitmap_lockPixels(env, outBitmap, &outPixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("Failed to lock output pixels");
        return bitmap;
    }

    AndroidBitmapInfo outInfo;
    if (AndroidBitmap_getInfo(env, outBitmap, &outInfo) != ANDROID_BITMAP_RESULT_SUCCESS ||
        outInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        AndroidBitmap_unlockPixels(env, outBitmap);
        LOGE("Failed to get output bitmap info");
        return bitmap;
    }

    // Y'Cb'Cr' → RGB，alpha 原样保留。
    uint8_t *dst = static_cast<uint8_t *>(outPixels);
    const size_t dstStride = outInfo.stride;
    for (int y = 0; y < H; ++y) {
        uint8_t *row = dst + static_cast<size_t>(y) * dstStride;
        const size_t base = static_cast<size_t>(y) * W;
        for (int x = 0; x < W; ++x) {
            const size_t i = base + x;
            const float yy = outY[i];
            const float cb = outCb[i] - 128.0f;
            const float cr = outCr[i] - 128.0f;
            const int r = static_cast<int>(yy + 1.402f * cr + 0.5f);
            const int g =
                static_cast<int>(yy - 0.344136f * cb - 0.714136f * cr + 0.5f);
            const int b = static_cast<int>(yy + 1.772f * cb + 0.5f);
            row[x * 4 + 0] = static_cast<uint8_t>(clampi(r, 0, 255));
            row[x * 4 + 1] = static_cast<uint8_t>(clampi(g, 0, 255));
            row[x * 4 + 2] = static_cast<uint8_t>(clampi(b, 0, 255));
            row[x * 4 + 3] = planeA[i];
        }
    }
    AndroidBitmap_unlockPixels(env, outBitmap);
    return outBitmap;
}

extern "C" JNIEXPORT void JNICALL
Java_eu_kanade_tachiyomi_util_MihonSyEnhancer_nativeNlmAbort(JNIEnv *env, jobject thiz) {
    g_nlmAbort.store(true, std::memory_order_relaxed);
}
