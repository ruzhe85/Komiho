// Komiho (2026-10-27): MTK NPU（LiteRT + NeuroPilot）后端 —— 进程内单例。
//
// 与 waifu2x-jni 的引擎**完全隔离**：这里没有 ncnn、没有 Vulkan、没有 QNN 的全局状态，
// 出问题只影响 MTK 这条路（见 CMakeLists 里为什么单独编一个 .so）。
//
// 模型契约（与上游 litert 后端一致，来自发布包的 tflite）：
//   输入  [1, 256, 256, 3] float32，NHWC，RGB 归一化到 [0,1]
//   输出  [1, 256*scale, 256*scale, 3] float32，scale ∈ {2,3}
// 分块：每次喂 256×256 的窗口，只用输出的中央 (256-2*padding) 区域拼回去，
// 边缘 halo 被丢弃 —— padding 与网络感受野不匹配就会出现可见拼缝。

#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace litert_backend {

/** 单页推理的耗时拆分（Kotlin 侧用来填增强角标，口径与 Waifu2x.Timing 一致）。 */
struct Timing {
    /** 纯 NPU/CPU 推理耗时（不含分块搬运与格式转换）。 */
    int64_t inference_ms = -1;
    /** 整页墙钟（含分块、格式转换）。 */
    int64_t total_ms = -1;
};

struct Config {
    /** 解包后的 .tflite 绝对路径。 */
    std::string model_path;
    /** LiteRT 编译缓存目录（NPU 首次编译产物落这里，必须可写）。 */
    std::string cache_dir;
    /** 插件库目录 = ApplicationInfo.nativeLibraryDir。 */
    std::string library_dir;
    /** 放大倍数，必须与模型一致（2 或 3）。 */
    int scale = 2;
    /** tile halo（px），按模型族给（见 Kotlin 侧 LiteRtNative.paddingFor）。 */
    int padding = 10;
    /**
     * true = 强制 CPU 加速器（分块/进度/取消的正确性验证通道，不需要 MTK 设备）；
     * false = 请求 NPU，且**要求全量委托**，否则初始化失败（绝不把 CPU 回退冒充 NPU）。
     */
    bool cpu_only = false;
};

/** 初始化引擎。成功返回 true；失败返回 false 并写 [error]（可为 nullptr）。 */
bool initialize(const Config &config, std::string *error);

bool is_initialized();

/** 引擎实际跑在 NPU 上（CPU 验证模式恒为 false）。 */
bool is_npu_active();

int tile_size();
int scale_value();

/** 请求中止：分块之间检查，同时注册给 LiteRT 作为单个 tile 内部的取消回调。 */
void request_abort();
void clear_abort();

/** 0~100 的整页进度（供 Kotlin 侧轮询）。 */
int progress();

/** 最近一次 [process] 的耗时拆分。 */
Timing last_timing();

/**
 * 整页放大：输入 RGBA_8888（`width*height*4`），输出同样格式、尺寸为 `width*scale × height*scale`。
 * 失败返回 false 并写 [error]；被 abort 打断同样返回 false（error 为 "aborted"）。
 */
bool process(const uint8_t *rgba, int width, int height, std::vector<uint8_t> *out, int *out_width,
             int *out_height, std::string *error);

void shutdown();

/** 最近一次错误的文本（供 JNI 查询，避免错误信息只进 logcat）。 */
const char *last_error();

const char *last_error_detail();

}  // namespace litert_backend
