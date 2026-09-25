// Komiho: Guided Filter 漫画降噪 —— CPU 实现，零外部依赖。
//
// 为什么不是 NLM：Fast NLM 实测 3.1MP 弱档单页 ~11s（真机 log 实锤，
// 算量 = 像素 × 441 搜索偏移 ≈ 79G ops/页，NLM 固有代价），远超实时阅读红线，
// 判定「实时阅读不值得」→ 换 guided filter（He et al., ECCV 2010）。
// Guided filter 的代价与窗口大小无关（box filter 滑动窗口 O(1)/像素），
// 3.1MP 单页 ~100-200ms。
//
// 去噪原理：引导统计 = 预平滑亮度 gI，待滤信号 p = 各 RGB 通道独立滤波。
//   a = cov(gI,p) / (var(gI) + eps),  b = mean(p) − a·mean(gI),  q = mean(a)·p + mean(b)
// 平坦区 var(gI) << eps → a≈0 → q≈局部均值（颗粒/色噪被抹掉）；
// 线稿/文字区 var(gI) 大 → a≈cov/var → 边缘结构保留（比 NLM 更稳，不会断线）。
// ⚠️ 引导图必须先预平滑（GUIDE_SMOOTH_R）：含噪亮度直接当引导时，噪声方差顶高
// var(gI) → 噪区 a 偏大 → 噪声被当「结构」保留（第一版开强档也看不出降噪的根因之一）。
// ⚠️⚠️ 逐像素重建必须用原始像素 p，绝不能用 gI（第二版踩坑实录）：
//   q = a·gI + b 会在 a≈1 的所有细节区把输出换成 7×7 模糊后的亮度 → 整页全糊
//   （「开弱档图片都全糊了」的根因）。gI 只参与统计，细节永远来自 p 本身。
// eps 越大 a 越小、平滑越强（a = var(gI)/(var(gI)+eps)），但过大会软化中等对比
// 细节 —— 三档由 Kotlin 侧映射 (radius, eps)：
//   弱 (4, 8) / 中 (8, 24) / 强 (12, 64)，真机按画质实测再调。
// 预平滑后平坦区 var(gI) ≈ σ²/49（7×7 box），σ²≈100 → var(gI)≈2：
//   弱 a≈0.2（留 20% 噪声）/ 中 a≈0.08 / 强 a≈0.03；中等对比细节（var≈45）
//   三档 a≈0.85/0.65/0.41，纹理保留度可辨。
//
// 内存：按 band 处理（band 高 64 行 + r 边界），中间平面只活在一个 band 里，
// 峰值 ~30MB，不随页高增长（对比：全图 float 平面方案要 100MB+）。
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

// 对一个 ext band 跑完整 guided 流程，把输出（按 ext 行存）写进 outR/outG/outB。
// I 与 pR/pG/pB 由调用方从源位图提取（ext 范围）。
//
// ⚠️ 缓冲管理纪律（上一版在这里崩过）：所有平面在进入通道循环前**一次性分配**，
// 全函数只用不再归还 —— 空向量 .data() 是 nullptr，boxBlur 直接写 0x0 必 SEGV。
// 12 个 ext 平面 × ~0.72MB（64+2r 行 × 2048 宽）≈ 9MB/worker，可接受。
void guidedBand(const float *I, const float *pR, const float *pG, const float *pB, int W, int Hb,
                int r, float eps, float *outR, float *outG, float *outB) {
    constexpr int GUIDE_SMOOTH_R = 3;
    const size_t plane = static_cast<size_t>(W) * Hb;
    auto mk = [&]() { return std::vector<float>(plane); };
    std::vector<float> work = mk();   // boxBlur 的可分离工作缓冲
    std::vector<float> gI = mk(), meanI = mk(), sq = mk(), varI = mk();
    std::vector<float> P = mk(), meanP = mk(), IP = mk(), corrIP = mk();
    std::vector<float> a = mk(), b = mk(), meanA = mk(), meanB = mk();

    // 引导图 = 预平滑亮度（固定小半径，与档位 radius 无关）。
    boxBlur(I, gI.data(), work.data(), W, Hb, GUIDE_SMOOTH_R);

    // 引导统计（全通道共享）：mean_gI、分母 var_gI + eps = box(gI²) − mean_gI² + eps
    boxBlur(gI.data(), meanI.data(), work.data(), W, Hb, r);
    for (size_t i = 0; i < plane; ++i) sq[i] = gI[i] * gI[i];
    boxBlur(sq.data(), varI.data(), work.data(), W, Hb, r);
    for (size_t i = 0; i < plane; ++i) {
        const float mi = meanI[i];
        varI[i] = varI[i] - mi * mi + eps;
    }

    const float *srcCh[3] = {pR, pG, pB};
    float *dstCh[3] = {outR, outG, outB};
    for (int c = 0; c < 3; ++c) {
        std::memcpy(P.data(), srcCh[c], plane * sizeof(float));
        boxBlur(P.data(), meanP.data(), work.data(), W, Hb, r);
        for (size_t i = 0; i < plane; ++i) IP[i] = gI[i] * P[i];
        boxBlur(IP.data(), corrIP.data(), work.data(), W, Hb, r);
        for (size_t i = 0; i < plane; ++i) {
            const float cov = corrIP[i] - meanI[i] * meanP[i];
            a[i] = cov / varI[i];
            b[i] = meanP[i] - a[i] * meanI[i];
        }
        boxBlur(a.data(), meanA.data(), work.data(), W, Hb, r);
        boxBlur(b.data(), meanB.data(), work.data(), W, Hb, r);
        for (size_t i = 0; i < plane; ++i) {
            // 重建用原始像素 P（全分辨率细节），统计引导 gI 只到这里为止——
            // 用 gI 重建 = 输出细节被 7×7 box 模糊替换（全糊 bug，见文件头注释）。
            dstCh[c][i] = meanA[i] * P[i] + meanB[i];
        }
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

    // band 划分：band 高 64 行，上下各留 r 行边界（box 支撑半径），边界 clamp 到整图。
    const int bandH = 64;
    int nWorkers = static_cast<int>(std::thread::hardware_concurrency());
    if (nWorkers <= 0) nWorkers = 1;
    nWorkers = std::min(nWorkers, 4);
    const int nBands = (H + bandH - 1) / bandH;
    nWorkers = std::min(nWorkers, nBands);

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
