// Komiho (2026-10-27): MTK NPU（LiteRT + NeuroPilot）后端实现。
//
// 分工：
//   - 本文件只管「引擎 + 分块推理」，不含任何 JNI 符号（见 litert_jni.cpp）；
//   - 与 waifu2x-jni 的全局状态完全隔离（单独 .so，见 CMakeLists）；
//   - 运行期 dlopen：未初始化 / 非 MTK 设备不会打开任何 LiteRT 库。
//
// 状态机：shutdown → initialize（= 编译/加载）→ process*（可多次）→ shutdown。
// NPU 模式下 initialize 会**同步编译**模型（首次可达数十秒），调用方必须在后台线程。

#include "litert_backend.h"

#include "litert_api.h"

#include <android/log.h>
#include <dlfcn.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstring>
#include <mutex>

namespace litert_backend {

using namespace litert_api;

namespace {

constexpr const char *kTag = "LiteRtBackend";
constexpr const char *kRuntimeLibrary = "libLiteRt.so";

constexpr int kTileSize = 256;
constexpr int kDefaultPadding = 10;

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, kTag, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, kTag, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, kTag, __VA_ARGS__)

using Clock = std::chrono::steady_clock;

int64_t elapsedMs(Clock::time_point from) {
    return std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now() - from).count();
}

struct Runtime {
    void *library = nullptr;
    Api api{};

    LiteRtEnvironment env = nullptr;
    LiteRtOptions options = nullptr;
    LiteRtModel model = nullptr;
    LiteRtCompiledModel compiled = nullptr;

    LiteRtTensorBuffer input_buffer = nullptr;
    LiteRtTensorBuffer output_buffer = nullptr;

    size_t input_bytes = 0;
    size_t output_bytes = 0;

    int tile = kTileSize;
    int scale = 2;
    int padding = kDefaultPadding;
    bool cpu_only = false;
    bool npu_active = false;
    bool fully_accelerated = false;
    bool initialized = false;

    // 复用缓冲：同一页内每个 tile 复用，避免每块重新分配（scale=3 时输出块 ≈ 7MB 浮点）。
    std::vector<float> input_host;
    std::vector<float> output_host;

    std::string error;
    std::string error_detail;
    std::atomic<bool> abort{false};
    std::atomic<int> progress{0};
    Timing timing{};
};

Runtime g_runtime;
std::mutex g_mutex;

void setError(const char *message, const std::string &detail = std::string()) {
    g_runtime.error = message != nullptr ? message : "";
    g_runtime.error_detail = detail;
    if (detail.empty()) {
        LOGE("%s", g_runtime.error.c_str());
    } else {
        LOGE("%s: %s", g_runtime.error.c_str(), detail.c_str());
    }
}

/** 把 LiteRtStatus 与符号名拼成可读细节（排查时最有用的一条）。 */
std::string where(const char *symbol, LiteRtStatus status) {
    std::string s(symbol);
    s += " -> status=";
    s += std::to_string(status);
    return s;
}

Any stringOption(const char *value) {
    Any any{};
    any.type = ANY_STRING;
    any.str_value = value;
    return any;
}

template <typename T>
bool resolve(void *library, const char *name, T *out) {
    void *symbol = dlsym(library, name);
    if (symbol == nullptr) {
        LOGW("missing symbol: %s", name);
        return false;
    }
    *out = reinterpret_cast<T>(symbol);
    return true;
}

bool loadRuntime(Runtime &rt) {
    if (rt.library != nullptr) return true;
    rt.library = dlopen(kRuntimeLibrary, RTLD_NOW | RTLD_LOCAL);
    if (rt.library == nullptr) {
        const char *err = dlerror();
        setError("dlopen libLiteRt.so failed", err != nullptr ? err : "");
        return false;
    }

    Api &api = rt.api;
    bool ok = true;
    ok &= resolve(rt.library, "LiteRtCreateEnvironment", &api.create_environment);
    ok &= resolve(rt.library, "LiteRtDestroyEnvironment", &api.destroy_environment);
    ok &= resolve(rt.library, "LiteRtCreateOptions", &api.create_options);
    ok &= resolve(rt.library, "LiteRtDestroyOptions", &api.destroy_options);
    ok &= resolve(rt.library, "LiteRtSetOptionsHardwareAccelerators", &api.set_hw_accelerators);
    ok &= resolve(rt.library, "LiteRtCreateModelFromFile", &api.create_model_from_file);
    ok &= resolve(rt.library, "LiteRtDestroyModel", &api.destroy_model);
    ok &= resolve(rt.library, "LiteRtCreateCompiledModel", &api.create_compiled_model);
    ok &= resolve(rt.library, "LiteRtDestroyCompiledModel", &api.destroy_compiled_model);
    ok &= resolve(rt.library, "LiteRtCompiledModelIsFullyAccelerated", &api.is_fully_accelerated);
    ok &= resolve(rt.library, "LiteRtSetCompiledModelCancellationFunction", &api.set_cancellation);
    ok &= resolve(rt.library, "LiteRtRunCompiledModel", &api.run_compiled_model);
    ok &= resolve(rt.library, "LiteRtCreateManagedTensorBuffer", &api.create_managed_tensor_buffer);
    ok &= resolve(rt.library, "LiteRtLockTensorBuffer", &api.lock_tensor_buffer);
    ok &= resolve(rt.library, "LiteRtUnlockTensorBuffer", &api.unlock_tensor_buffer);
    ok &= resolve(rt.library, "LiteRtDestroyTensorBuffer", &api.destroy_tensor_buffer);
    ok &= resolve(rt.library, "LiteRtGetTensorBufferSize", &api.get_tensor_buffer_size);

    if (!ok) {
        const char *missing = api.firstMissing();
        setError("LiteRT runtime is missing symbols", missing != nullptr ? missing : "");
        return false;
    }
    return true;
}

/** 构造 NHWC 静态张量类型。 */
RankedTensorType makeNhwcType(int batch, int height, int width, int channels) {
    RankedTensorType type{};
    type.element_type = ELEMENT_FLOAT32;
    type.layout.rank = 4;
    type.layout.has_strides = 0;
    type.layout.dimensions[0] = batch;
    type.layout.dimensions[1] = height;
    type.layout.dimensions[2] = width;
    type.layout.dimensions[3] = channels;
    return type;
}

/** 注册给 LiteRT 的取消回调：单个 tile 推理内部也能被打断。 */
bool checkCancelled(void *) {
    return g_runtime.abort.load(std::memory_order_relaxed);
}

void releaseBuffers(Runtime &rt) {
    if (rt.output_buffer != nullptr) {
        rt.api.destroy_tensor_buffer(rt.output_buffer);
        rt.output_buffer = nullptr;
    }
    if (rt.input_buffer != nullptr) {
        rt.api.destroy_tensor_buffer(rt.input_buffer);
        rt.input_buffer = nullptr;
    }
}

/** 释放句柄（保留 dlopen 的库句柄，下次初始化省一次开销）。 */
void releaseHandles(Runtime &rt) {
    releaseBuffers(rt);
    if (rt.compiled != nullptr && rt.api.destroy_compiled_model != nullptr) {
        rt.api.destroy_compiled_model(rt.compiled);
        rt.compiled = nullptr;
    }
    if (rt.model != nullptr && rt.api.destroy_model != nullptr) {
        rt.api.destroy_model(rt.model);
        rt.model = nullptr;
    }
    if (rt.options != nullptr && rt.api.destroy_options != nullptr) {
        rt.api.destroy_options(rt.options);
        rt.options = nullptr;
    }
    if (rt.env != nullptr && rt.api.destroy_environment != nullptr) {
        rt.api.destroy_environment(rt.env);
        rt.env = nullptr;
    }
    rt.input_host.clear();
    rt.output_host.clear();
    rt.input_bytes = 0;
    rt.output_bytes = 0;
    rt.initialized = false;
    rt.npu_active = false;
    rt.fully_accelerated = false;
}

void shutdownLocked() {
    releaseHandles(g_runtime);
    if (g_runtime.library != nullptr) {
        dlclose(g_runtime.library);
        g_runtime.library = nullptr;
    }
    g_runtime.api = Api{};
}

inline uint8_t toByte(float value) {
    if (!(value > 0.0f)) return 0;  // 同时挡掉 NaN
    if (value >= 1.0f) return 255;
    return static_cast<uint8_t>(value * 255.0f + 0.5f);
}

}  // namespace

bool initialize(const Config &config, std::string *error) {
    std::lock_guard<std::mutex> lock(g_mutex);
    shutdownLocked();
    g_runtime.abort.store(false);
    g_runtime.progress.store(0);
    g_runtime.timing = Timing{};
    g_runtime.error.clear();
    g_runtime.error_detail.clear();

    Runtime &rt = g_runtime;

    const auto fail = [&](const char *message, const std::string &detail) {
        setError(message, detail);
        releaseHandles(rt);
        if (error != nullptr) *error = detail.empty() ? rt.error : (rt.error + ": " + detail);
        return false;
    };

    if (config.scale != 2 && config.scale != 3) {
        return fail("unsupported scale", std::to_string(config.scale));
    }
    if (config.model_path.empty() || config.cache_dir.empty() || config.library_dir.empty()) {
        return fail("initialize requires model_path / cache_dir / library_dir", std::string());
    }

    rt.scale = config.scale;
    rt.padding = config.padding > 0 ? config.padding : kDefaultPadding;
    rt.tile = kTileSize;
    rt.cpu_only = config.cpu_only;

    if (!loadRuntime(rt)) {
        const std::string detail = rt.error_detail;
        if (error != nullptr) *error = detail.empty() ? rt.error : (rt.error + ": " + detail);
        return false;
    }

    // 1) 环境：三个目录选项是 NeuroPilot「编译器插件 / 调度器 / 编译缓存」的唯一入口。
    const EnvOption env_options[3] = {
        {ENV_COMPILER_PLUGIN_LIBRARY_DIR, stringOption(config.library_dir.c_str())},
        {ENV_DISPATCH_LIBRARY_DIR, stringOption(config.library_dir.c_str())},
        {ENV_COMPILER_CACHE_DIR, stringOption(config.cache_dir.c_str())},
    };
    LiteRtStatus status = rt.api.create_environment(3, env_options, &rt.env);
    if (status != STATUS_OK || rt.env == nullptr) {
        return fail("LiteRtCreateEnvironment failed", where("create_environment", status));
    }

    // 2) 选项：CPU 验证模式显式只要 CPU；其余请求 NPU。
    status = rt.api.create_options(&rt.options);
    if (status != STATUS_OK || rt.options == nullptr) {
        return fail("LiteRtCreateOptions failed", where("create_options", status));
    }
    const int32_t accelerators = rt.cpu_only ? ACCEL_CPU : ACCEL_NPU;
    status = rt.api.set_hw_accelerators(rt.options, accelerators);
    if (status != STATUS_OK) {
        return fail("LiteRtSetOptionsHardwareAccelerators failed",
                    where("set_hw_accelerators", status));
    }

    // 3) 模型 + 编译（NPU 首次同步编译，产物落 compiler-cache）。
    status = rt.api.create_model_from_file(rt.env, config.model_path.c_str(), &rt.model);
    if (status != STATUS_OK || rt.model == nullptr) {
        return fail("LiteRtCreateModelFromFile failed", where("create_model_from_file", status));
    }

    const auto compile_start = Clock::now();
    status = rt.api.create_compiled_model(rt.env, rt.model, rt.options, &rt.compiled);
    if (status != STATUS_OK || rt.compiled == nullptr) {
        return fail("LiteRtCreateCompiledModel failed", where("create_compiled_model", status));
    }
    const int64_t compile_ms = elapsedMs(compile_start);

    // 4) 全量委托校验：NPU 模式必须全量，否则按失败处理（绝不把 CPU 回退冒充 NPU）。
    bool fully = false;
    if (rt.api.is_fully_accelerated(rt.compiled, &fully) == STATUS_OK) {
        rt.fully_accelerated = fully;
    }
    if (!rt.cpu_only && !rt.fully_accelerated) {
        return fail("model is not fully delegated to NPU", std::string());
    }
    rt.npu_active = !rt.cpu_only && rt.fully_accelerated;

    // 5) 取消回调 —— 单个 tile 内部的长时间推理也能被打断。
    rt.api.set_cancellation(rt.compiled, nullptr, &checkCancelled);

    // 6) 张量缓冲（HostMemory；输入/输出各一块，整页复用）。
    const RankedTensorType input_type = makeNhwcType(1, rt.tile, rt.tile, 3);
    const int out_hw = rt.tile * rt.scale;
    const RankedTensorType output_type = makeNhwcType(1, out_hw, out_hw, 3);

    rt.input_bytes = static_cast<size_t>(rt.tile) * rt.tile * 3 * sizeof(float);
    rt.output_bytes = static_cast<size_t>(out_hw) * out_hw * 3 * sizeof(float);

    status = rt.api.create_managed_tensor_buffer(rt.env, BUFFER_HOST_MEMORY, &input_type,
                                                 rt.input_bytes, &rt.input_buffer);
    if (status != STATUS_OK || rt.input_buffer == nullptr) {
        return fail("create input tensor buffer failed",
                    where("create_managed_tensor_buffer", status));
    }
    status = rt.api.create_managed_tensor_buffer(rt.env, BUFFER_HOST_MEMORY, &output_type,
                                                 rt.output_bytes, &rt.output_buffer);
    if (status != STATUS_OK || rt.output_buffer == nullptr) {
        return fail("create output tensor buffer failed",
                    where("create_managed_tensor_buffer", status));
    }

    rt.input_host.assign(rt.input_bytes / sizeof(float), 0.0f);
    rt.output_host.assign(rt.output_bytes / sizeof(float), 0.0f);
    rt.initialized = true;

    LOGI("Ready: npu=%d fully=%d scale=%d padding=%d compileMs=%lld cache=%s",
         rt.npu_active ? 1 : 0,
         rt.fully_accelerated ? 1 : 0,
         rt.scale,
         rt.padding,
         static_cast<long long>(compile_ms),
         config.cache_dir.c_str());
    if (error != nullptr) error->clear();
    return true;
}

bool is_initialized() {
    std::lock_guard<std::mutex> lock(g_mutex);
    return g_runtime.initialized;
}

bool is_npu_active() {
    std::lock_guard<std::mutex> lock(g_mutex);
    return g_runtime.npu_active;
}

int tile_size() {
    std::lock_guard<std::mutex> lock(g_mutex);
    return g_runtime.tile;
}

int scale_value() {
    std::lock_guard<std::mutex> lock(g_mutex);
    return g_runtime.scale;
}

void request_abort() {
    g_runtime.abort.store(true, std::memory_order_relaxed);
}

void clear_abort() {
    g_runtime.abort.store(false, std::memory_order_relaxed);
}

int progress() {
    return g_runtime.progress.load(std::memory_order_relaxed);
}

Timing last_timing() {
    std::lock_guard<std::mutex> lock(g_mutex);
    return g_runtime.timing;
}

const char *last_error() {
    return g_runtime.error.c_str();
}

const char *last_error_detail() {
    return g_runtime.error_detail.c_str();
}

bool process(const uint8_t *rgba, int width, int height, std::vector<uint8_t> *out, int *out_width,
             int *out_height, std::string *error) {
    std::lock_guard<std::mutex> lock(g_mutex);
    Runtime &rt = g_runtime;
    if (!rt.initialized) {
        setError("backend not initialized");
        if (error != nullptr) *error = rt.error;
        return false;
    }
    if (rgba == nullptr || width <= 0 || height <= 0 || out == nullptr) {
        setError("invalid input image");
        if (error != nullptr) *error = rt.error;
        return false;
    }

    const int scale = rt.scale;
    const int pad = rt.padding;
    const int tile = rt.tile;
    const int stride = tile - 2 * pad;  // 每块真正推进的输入像素数（中央有效区）
    if (stride <= 0) {
        setError("padding too large for tile", std::to_string(pad));
        if (error != nullptr) *error = rt.error + ": " + rt.error_detail;
        return false;
    }

    const int out_w = width * scale;
    const int out_h = height * scale;
    out->assign(static_cast<size_t>(out_w) * out_h * 4, 255);

    const int tiles_x = (width + stride - 1) / stride;
    const int tiles_y = (height + stride - 1) / stride;
    const int total_tiles = tiles_x * tiles_y;

    rt.abort.store(false, std::memory_order_relaxed);
    rt.progress.store(0, std::memory_order_relaxed);
    const auto page_start = Clock::now();
    int64_t inference_ms = 0;
    int done = 0;

    float *input_host = rt.input_host.data();
    const int out_hw = tile * scale;

    for (int ty = 0; ty < tiles_y; ++ty) {
        for (int tx = 0; tx < tiles_x; ++tx) {
            if (rt.abort.load(std::memory_order_relaxed)) {
                setError("aborted");
                if (error != nullptr) *error = rt.error;
                return false;
            }
            const int ox = tx * stride;
            const int oy = ty * stride;

            // 1) 灌入 tile×tile 窗口：左上角 = (ox-pad, oy-pad)，越界按边缘复制。
            for (int y = 0; y < tile; ++y) {
                const int sy = std::clamp(oy - pad + y, 0, height - 1);
                const uint8_t *src_row = rgba + static_cast<size_t>(sy) * width * 4;
                float *dst_row = input_host + static_cast<size_t>(y) * tile * 3;
                for (int x = 0; x < tile; ++x) {
                    const int sx = std::clamp(ox - pad + x, 0, width - 1);
                    const uint8_t *px = src_row + static_cast<size_t>(sx) * 4;
                    float *dst = dst_row + static_cast<size_t>(x) * 3;
                    dst[0] = px[0] / 255.0f;
                    dst[1] = px[1] / 255.0f;
                    dst[2] = px[2] / 255.0f;
                }
            }

            // 2) 上传
            void *host = nullptr;
            LiteRtStatus status = rt.api.lock_tensor_buffer(rt.input_buffer, &host, LOCK_WRITE);
            if (status != STATUS_OK || host == nullptr) {
                setError("lock input buffer failed", where("lock_tensor_buffer", status));
                if (error != nullptr) *error = rt.error + ": " + rt.error_detail;
                return false;
            }
            std::memcpy(host, input_host, rt.input_bytes);
            rt.api.unlock_tensor_buffer(rt.input_buffer);

            // 3) 推理
            LiteRtTensorBuffer inputs[1] = {rt.input_buffer};
            LiteRtTensorBuffer outputs[1] = {rt.output_buffer};
            const auto run_start = Clock::now();
            status = rt.api.run_compiled_model(rt.compiled, 0, 1, inputs, 1, outputs);
            inference_ms += elapsedMs(run_start);
            if (status != STATUS_OK) {
                setError("LiteRtRunCompiledModel failed", where("run_compiled_model", status));
                if (error != nullptr) *error = rt.error + ": " + rt.error_detail;
                return false;
            }

            // 4) 下载并只取中央有效区拼回输出（halo 丢弃，避免拼缝）。
            status = rt.api.lock_tensor_buffer(rt.output_buffer, &host, LOCK_READ);
            if (status != STATUS_OK || host == nullptr) {
                setError("lock output buffer failed", where("lock_tensor_buffer", status));
                if (error != nullptr) *error = rt.error + ": " + rt.error_detail;
                return false;
            }
            const float *out_floats = static_cast<const float *>(host);
            const int valid = stride * scale;
            const int origin = pad * scale;
            for (int dy = 0; dy < valid; ++dy) {
                const int dst_y = oy * scale + dy;
                if (dst_y >= out_h) break;
                const float *src_row =
                    out_floats + (static_cast<size_t>(origin + dy) * out_hw + origin) * 3;
                uint8_t *dst_row = out->data() + static_cast<size_t>(dst_y) * out_w * 4;
                for (int dx = 0; dx < valid; ++dx) {
                    const int dst_x = ox * scale + dx;
                    if (dst_x >= out_w) break;
                    const float *p = src_row + static_cast<size_t>(dx) * 3;
                    uint8_t *q = dst_row + static_cast<size_t>(dst_x) * 4;
                    q[0] = toByte(p[0]);
                    q[1] = toByte(p[1]);
                    q[2] = toByte(p[2]);
                    q[3] = 255;
                }
            }
            rt.api.unlock_tensor_buffer(rt.output_buffer);

            ++done;
            rt.progress.store(done * 100 / total_tiles, std::memory_order_relaxed);
        }
    }

    rt.timing.inference_ms = inference_ms;
    rt.timing.total_ms = elapsedMs(page_start);
    rt.progress.store(100, std::memory_order_relaxed);
    if (out_width != nullptr) *out_width = out_w;
    if (out_height != nullptr) *out_height = out_h;
    LOGI("process done: %dx%d -> %dx%d tiles=%d inferenceMs=%lld totalMs=%lld",
         width,
         height,
         out_w,
         out_h,
         total_tiles,
         static_cast<long long>(inference_ms),
         static_cast<long long>(rt.timing.total_ms));
    if (error != nullptr) error->clear();
    return true;
}

void shutdown() {
    std::lock_guard<std::mutex> lock(g_mutex);
    shutdownLocked();
    LOGI("shutdown");
}

}  // namespace litert_backend

namespace litert_api {

const char *Api::firstMissing() const {
    if (create_environment == nullptr) return "LiteRtCreateEnvironment";
    if (destroy_environment == nullptr) return "LiteRtDestroyEnvironment";
    if (create_options == nullptr) return "LiteRtCreateOptions";
    if (destroy_options == nullptr) return "LiteRtDestroyOptions";
    if (set_hw_accelerators == nullptr) return "LiteRtSetOptionsHardwareAccelerators";
    if (create_model_from_file == nullptr) return "LiteRtCreateModelFromFile";
    if (destroy_model == nullptr) return "LiteRtDestroyModel";
    if (create_compiled_model == nullptr) return "LiteRtCreateCompiledModel";
    if (destroy_compiled_model == nullptr) return "LiteRtDestroyCompiledModel";
    if (is_fully_accelerated == nullptr) return "LiteRtCompiledModelIsFullyAccelerated";
    if (set_cancellation == nullptr) return "LiteRtSetCompiledModelCancellationFunction";
    if (run_compiled_model == nullptr) return "LiteRtRunCompiledModel";
    if (create_managed_tensor_buffer == nullptr) return "LiteRtCreateManagedTensorBuffer";
    if (lock_tensor_buffer == nullptr) return "LiteRtLockTensorBuffer";
    if (unlock_tensor_buffer == nullptr) return "LiteRtUnlockTensorBuffer";
    if (destroy_tensor_buffer == nullptr) return "LiteRtDestroyTensorBuffer";
    if (get_tensor_buffer_size == nullptr) return "LiteRtGetTensorBufferSize";
    return nullptr;
}

}  // namespace litert_api
