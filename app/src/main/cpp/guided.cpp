// Komiho: Guided Filter 漫画降噪 —— CPU 实现，零外部依赖。
//
// 为什么不是 NLM：Fast NLM 实测 3.1MP 弱档单页 ~11s（真机 log 实锤，
// 算量 = 像素 × 441 搜索偏移 ≈ 79G ops/页，NLM 固有代价），远超 §17 红线，
// 判定「实时阅读不值得」→ 换 guided filter（He et al., ECCV 2010）。
// Guided filter 的代价与窗口大小无关（box filter 滑动窗口 O(1)/像素），
// 3.1MP 单页 ~100-200ms。
//
// 去噪原理：引导图 = 亮度 I，待滤信号 p = 各 RGB 通道独立滤波。
//   a = cov(I,p) / (var(I) + eps),  b = mean(p) − a·mean(I),  q = mean(a)·I + mean(b)
// 平坦区 var(I) << eps → a≈0 → q≈局部均值（颗粒/色噪被抹掉）；
// 线稿/文字区 var(I) 大 → a≈cov/var → 边缘结构保留（比 NLM 更稳，不会断线）。
// eps 越小平滑越强 —— 三档由 Kotlin 侧映射 (radius, eps)：
//   弱 (4, 400) / 中 (8, 120) / 强 (12, 50)，真机按画质实测再调。
//
// 内存：按 band 处理（band 高 64 行 + r 边界），中间平面只活在一个 band 里，
// 峰值 ~25MB，不随页高增长（对比：全图 float 平面方案要 100MB+）。
// 并行：band 间并行（min(4, cores) 个 worker，各拿各的 band、无锁无线程池）；
// box filter 用可分离滑动窗口 O(1)/像素。
// 失败语义：任何一步失败返回入参 bitmap（Kotlin 回落原图），绝不出黑屏/空图。

#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <thread>
#include <vector>

#define LOG_TAG "GuidedDenoise"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {

inline int clampi(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }

// 可分离滑动窗口 box filter（O(1)/像素）。src/dst 均为 W×H float 行紧致平面，
// tmp 为调用方提供的同尺寸工作缓冲。边界按复制（clamp）处理。
void boxBlur(const float *src, float *dst, float *tmp, int W, int H, int r) {
    const int win = 2 * r + 1;
    // 水平：tmp[y][x] = Σ_{v=-r..r} src[y][clamp(x+v)]
    for (int y = 0; y < H; ++y) {
        const float *row = src + static_cast<size_t>(y) * W;
        float *out = tmp + static_cast<size_t>(y) * W;
        float sum = 0.0f;
        for (int k = -r; k <= r; ++k) sum += row[clampi(k, 0, W - 1)];
        for (int x = 0; x < W; ++x) {
            out[x] = sum;
            sum += row[clampi(x + r + 1, 0, W - 1)] - row[clampi(x - r, 0, W - 1)];
        }
    }
    // 垂直：dst[y][x] = tmp 按列滑动窗口 / win²
    const float invArea = 1.0f / (static_cast<float>(win) * static_cast<float>(win));
    for (int x = 0; x < W; ++x) {
        float sum = 0.0f;
        for (int k = -r; k <= r; ++k) sum += tmp[static_cast<size_t>(clampi(k, 0, H - 1)) * W + x];
        for (int y = 0; y < H; ++y) {
            dst[static_cast<size_t>(y) * W + x] = sum * invArea;
            sum += tmp[static_cast<size_t>(clampi(y + r + 1, 0, H - 1)) * W + x] -
                   tmp[static_cast<size_t>(clampi(y - r, 0, H - 1)) * W + x];
        }
    }
}

// band 简易平面池：ext band 尺寸 W×Hb 的 float 平面，用完归还，避免反复 malloc。
struct PlanePool {
    std::vector<std::vector<float>> pool;
    std::vector<float> take(int W, int H) {
        if (!pool.empty()) {
            std::vector<float> p = std::move(pool.back());
            pool.pop_back();
            if (static_cast<int>(p.size()) >= W * H) return p;
        }
        return std::vector<float>(static_cast<size_t>(W) * H);
    }
    void give(std::vector<float> &&p) { pool.push_back(std::move(p)); }
};

// 对一个 ext band 跑完整 guided 流程，把输出行（band 内 [y0,y1) 对应的 ext 行
// [off, off+bandN)）写进 outQ（三通道打包进 outR/outG/outB 亦按 ext 行存）。
// I 与 pR/pG/pB 由调用方从源位图提取（ext 范围）。
void guidedBand(const float *I, const float *pR, const float *pG, const float *pB, int W, int Hb,
                int r, float eps, float *outR, float *outG, float *outB) {
    PlanePool pool;
    const size_t plane = static_cast<size_t>(W) * Hb;
    auto take = [&](std::vector<float> &v) { v = pool.take(W, Hb); };

    std::vector<float> meanI, corrII, varI, tmp, meanP, corrIP, a, b, meanA, meanB;
    take(meanI); take(tmp);
    // 引导统计（全通道共享）：mean_I、var_I = box(I²) − mean_I²
    boxBlur(I, meanI.data(), tmp.data(), W, Hb, r);
    take(corrII);
    for (size_t i = 0; i < plane; ++i) corrII[i] = I[i] * I[i];
    boxBlur(corrII.data(), varI.data(), tmp.data(), W, Hb, r);
    for (size_t i = 0; i < plane; ++i) {
        const float mi = meanI[i];
        varI[i] = varI[i] - mi * mi + eps;  // 就地存分母 var+eps
    }
    pool.give(std::move(corrII));

    std::vector<float> P, IP;
    take(P); take(IP);
    const float *srcCh[3] = {pR, pG, pB};
    float *dstCh[3] = {outR, outG, outB};
    for (int c = 0; c < 3; ++c) {
        std::memcpy(P.data(), srcCh[c], plane * sizeof(float));
        boxBlur(P.data(), meanP.data(), tmp.data(), W, Hb, r);
        for (size_t i = 0; i < plane; ++i) IP[i] = I[i] * P[i];
        boxBlur(IP.data(), corrIP.data(), tmp.data(), W, Hb, r);
        take(a); take(b);
        for (size_t i = 0; i < plane; ++i) {
            const float cov = corrIP[i] - meanI[i] * meanP[i];
            a[i] = cov / varI[i];
            b[i] = meanP[i] - a[i] * meanI[i];
        }
        pool.give(std::move(meanP));
        pool.give(std::move(corrIP));
        boxBlur(a.data(), meanA.data(), tmp.data(), W, Hb, r);
        boxBlur(b.data(), meanB.data(), tmp.data(), W, Hb, r);
        for (size_t i = 0; i < plane; ++i) {
            dstCh[c][i] = meanA[i] * I[i] + meanB[i];
        }
        pool.give(std::move(a));
        pool.give(std::move(b));
        pool.give(std::move(P));
        pool.give(std::move(IP));
        take(P); take(IP);
        take(meanP); take(corrIP);
    }
}

}  // namespace

extern "C" JNIEXPORT jobject JNICALL
Java_eu_kanade_tachiyomi_util_MihonSyEnhancer_nativeGuidedDenoise(JNIEnv *env, jobject thiz,
                                                                  jobject bitmap, jfloat eps,
                                                                  jint radius) {
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
    const int r = std::max(1, radius);
    if (W <= 0 || H <= 0) return bitmap;

    void *pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("Failed to lock input pixels");
        return bitmap;
    }
    const uint8_t *src = static_cast<const uint8_t *>(pixels);
    const size_t srcStride = info.stride;

    const auto start = std::chrono::steady_clock::now();

    // band 划分：band 高 64 行，上下各留 r 行边界（box 支撑半径），边界 clamp 到整图。
    const int bandH = 64;
    int nWorkers = static_cast<int>(std::thread::hardware_concurrency());
    if (nWorkers <= 0) nWorkers = 1;
    nWorkers = std::min(nWorkers, 4);
    const int nBands = (H + bandH - 1) / bandH;
    nWorkers = std::min(nWorkers, nBands);

    // 输出位图（与 lanczos3.cpp 同款 JNI 流程）。
    jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
    if (!bitmapClass) {
        AndroidBitmap_unlockPixels(env, bitmap);
        return bitmap;
    }
    jmethodID createBitmapMethod = env->GetStaticMethodID(
        bitmapClass, "createBitmap", "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    jclass configClass = env->FindClass("android/graphics/Bitmap$Config");
    if (!createBitmapMethod || !configClass) {
        AndroidBitmap_unlockPixels(env, bitmap);
        return bitmap;
    }
    jfieldID configField =
        env->GetStaticFieldID(configClass, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
    if (!configField) {
        AndroidBitmap_unlockPixels(env, bitmap);
        return bitmap;
    }
    jobject config = env->GetStaticObjectField(configClass, configField);
    jobject outBitmap = env->CallStaticObjectMethod(bitmapClass, createBitmapMethod, W, H, config);
    if (env->ExceptionCheck() || !outBitmap) {
        env->ExceptionClear();
        AndroidBitmap_unlockPixels(env, bitmap);
        LOGE("Failed to create output bitmap");
        return bitmap;
    }
    void *outPixels = nullptr;
    if (AndroidBitmap_lockPixels(env, outBitmap, &outPixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        AndroidBitmap_unlockPixels(env, bitmap);
        LOGE("Failed to lock output pixels");
        return bitmap;
    }
    uint8_t *dst = static_cast<uint8_t *>(outPixels);
    AndroidBitmapInfo outInfo;
    if (AndroidBitmap_getInfo(env, outBitmap, &outInfo) != ANDROID_BITMAP_RESULT_SUCCESS) {
        AndroidBitmap_unlockPixels(env, bitmap);
        AndroidBitmap_unlockPixels(env, outBitmap);
        LOGE("Failed to get output bitmap info");
        return bitmap;
    }
    const size_t dstStride = outInfo.stride;

    // band 间并行：每个 worker 独立取 band，互不共享中间缓冲。
    std::atomic<int> nextBand{0};
    auto worker = [&]() {
        for (;;) {
            const int band = nextBand.fetch_add(1);
            if (band >= nBands) return;
            const int y0 = band * bandH;
            const int y1 = std::min(y0 + bandH, H);
            const int n = y1 - y0;
            const int e0 = clampi(y0 - r, 0, H - 1);  // ext 起点（clamp 后可能不足 2r，够了）
            const int e1 = clampi(y1 + r - 1, 0, H - 1);
            const int Hb = e1 - e0 + 1;
            const int off = y0 - e0;  // band 首行在 ext 中的偏移

            // 提取 ext band：I（亮度）+ R/G/B（float 0..255），行紧致。
            std::vector<float> I(static_cast<size_t>(W) * Hb), pR(I.size()), pG(I.size()), pB(I.size());
            for (int y = 0; y < Hb; ++y) {
                const uint8_t *row =
                    src + static_cast<size_t>(clampi(e0 + y, 0, H - 1)) * srcStride;
                float *iRow = &I[static_cast<size_t>(y) * W];
                float *rRow = &pR[static_cast<size_t>(y) * W];
                float *gRow = &pG[static_cast<size_t>(y) * W];
                float *bRow = &pB[static_cast<size_t>(y) * W];
                for (int x = 0; x < W; ++x) {
                    const int ri = row[x * 4 + 0], gi = row[x * 4 + 1], bi = row[x * 4 + 2];
                    iRow[x] = 0.299f * ri + 0.587f * gi + 0.114f * bi;
                    rRow[x] = static_cast<float>(ri);
                    gRow[x] = static_cast<float>(gi);
                    bRow[x] = static_cast<float>(bi);
                }
            }

            std::vector<float> qR(I.size()), qG(I.size()), qB(I.size());
            guidedBand(I.data(), pR.data(), pG.data(), pB.data(), W, Hb, r, eps, qR.data(),
                       qG.data(), qB.data());

            // 写回 band 行（不含 ext 边界），alpha 从源行拷贝。
            for (int y = 0; y < n; ++y) {
                const int srcY = clampi(y0 + y, 0, H - 1);
                const uint8_t *srow = src + static_cast<size_t>(srcY) * srcStride;
                uint8_t *drow = dst + static_cast<size_t>(srcY) * dstStride;
                const size_t extBase = static_cast<size_t>(off + y) * W;
                for (int x = 0; x < W; ++x) {
                    drow[x * 4 + 0] = static_cast<uint8_t>(clampi(static_cast<int>(qR[extBase + x] + 0.5f), 0, 255));
                    drow[x * 4 + 1] = static_cast<uint8_t>(clampi(static_cast<int>(qG[extBase + x] + 0.5f), 0, 255));
                    drow[x * 4 + 2] = static_cast<uint8_t>(clampi(static_cast<int>(qB[extBase + x] + 0.5f), 0, 255));
                    drow[x * 4 + 3] = srow[x * 4 + 3];
                }
            }
        }
    };
    std::vector<std::thread> threads;
    for (int i = 1; i < nWorkers; ++i) threads.emplace_back(worker);
    worker();
    for (auto &th : threads) th.join();

    AndroidBitmap_unlockPixels(env, bitmap);
    AndroidBitmap_unlockPixels(env, outBitmap);

    const auto ms =
        std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - start)
            .count();
    LOGI("denoised %dx%d r=%d eps=%.0f workers=%d in %lldms", W, H, r, eps, nWorkers,
         static_cast<long long>(ms));
    return outBitmap;
}
