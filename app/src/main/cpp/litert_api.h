// Komiho (2026-10-27): LiteRT C API 的**最小 ABI 声明**（MTK NPU 后端用）。
//
// 为什么自己声明而不 include 官方头文件：
//  - 运行时的 `libLiteRt.so` 来自 Maven 依赖（com.google.ai.edge.litert:litert），**不带头文件**；
//  - 我们又是 dlopen + dlsym 惰性加载（非 MTK 设备永不加载），本来就不需要链接期符号；
//  - 因此只需要「与官方 ABI 逐字节一致的类型 + 函数指针签名」。
//
// 数值与结构逐条核对自 LiteRT C API 头文件（Apache-2.0）：
//   - 枚举：LiteRtHwAccelerator（CPU=1<<0 / GPU=1<<1 / NPU=1<<2）、LiteRtElementType
//     （Float32=1）、LiteRtTensorBufferType（HostMemory=1）、LiteRtTensorBufferLockMode
//     （Read=0 / Write=1 / ReadWrite=2）、LiteRtEnvOptionTag（CompilerPluginLibraryDir=0 /
//     DispatchLibraryDir=1 / CompilerCacheDir=18）、LiteRtStatusOk=0；
//   - 结构：LiteRtAny（16B）、LiteRtEnvOption（24B，value 偏移 8）、LiteRtRankedTensorType（72B，
//     layout 偏移 4）、LiteRtLayout（rank:7 + has_strides:1 + dimensions[8] + strides[8]）；
//   - 签名：LiteRtCreateEnvironment / LiteRtCreateOptions / LiteRtSetOptionsHardwareAccelerators /
//     LiteRtCreateModelFromFile / LiteRtCreateCompiledModel / LiteRtRunCompiledModel /
//     LiteRtCompiledModelIsFullyAccelerated / LiteRtCreateManagedTensorBuffer / LiteRtLockTensorBuffer。
// 上述符号在 libLiteRt.so 2.3.0 的导出表里已确认存在（strings 核对）。
//
// ⚠️ 改这里必须同步核对：
//   1) 上述头文件（google-ai-edge/LiteRT 的 litert/c/*.h）；
//   2) 升级 litert 版本后重跑一次符号表检查（缺符号只会在运行期暴露）。

#pragma once

#include <cstddef>
#include <cstdint>

namespace litert_api {

// 所有 LiteRt* 句柄在 C API 里都是不透明指针。
using LiteRtEnvironment = void *;
using LiteRtOptions = void *;
using LiteRtModel = void *;
using LiteRtCompiledModel = void *;
using LiteRtTensorBuffer = void *;
using LiteRtStatus = int32_t;

constexpr LiteRtStatus STATUS_OK = 0;

/** 位掩码，可组合。 */
enum HwAccelerator : int32_t {
    ACCEL_CPU = 1 << 0,
    ACCEL_GPU = 1 << 1,
    ACCEL_NPU = 1 << 2,
};

enum EnvOptionTag : int32_t {
    ENV_COMPILER_PLUGIN_LIBRARY_DIR = 0,
    ENV_DISPATCH_LIBRARY_DIR = 1,
    ENV_COMPILER_CACHE_DIR = 18,
};

enum ElementType : int32_t {
    ELEMENT_FLOAT32 = 1,
};

enum TensorBufferType : int32_t {
    BUFFER_HOST_MEMORY = 1,
};

enum LockMode : int32_t {
    LOCK_READ = 0,
    LOCK_WRITE = 1,
    LOCK_READ_WRITE = 2,
};

enum AnyType : int32_t {
    ANY_NONE = 0,
    ANY_STRING = 8,
};

/** 通用值容器（16 字节）。 */
struct Any {
    int32_t type;
    union {
        bool bool_value;
        int64_t int_value;
        double real_value;
        const char *str_value;
        const void *ptr_value;
    };
};
static_assert(sizeof(Any) == 16, "LiteRtAny 必须是 16 字节");

/** 环境选项（24 字节，value 偏移 8）—— 与官方 static_assert 一致。 */
struct EnvOption {
    int32_t tag;
    Any value;
};
static_assert(sizeof(EnvOption) == 24, "LiteRtEnvOption 必须是 24 字节");
static_assert(offsetof(EnvOption, value) == 8, "LiteRtEnvOption.value 偏移必须是 8");

/** 张量形状（rank 7bit + has_strides 1bit，其余按 4 字节对齐）。 */
struct Layout {
    uint32_t rank : 7;
    uint32_t has_strides : 1;
    int32_t dimensions[8];
    int32_t strides[8];
};

/** 静态 rank 的张量类型（72 字节，layout 偏移 4）。 */
struct RankedTensorType {
    int32_t element_type;
    Layout layout;
};
static_assert(sizeof(RankedTensorType) == 72, "LiteRtRankedTensorType 必须是 72 字节");
static_assert(offsetof(RankedTensorType, layout) == 4, "layout 偏移必须是 4");

// ---------------------------------------------------------------------------
// 函数指针类型（dlsym 得到）。参数顺序/类型逐条照抄官方声明。
// ---------------------------------------------------------------------------

using FnCreateEnvironment = LiteRtStatus (*)(int32_t num_options, const EnvOption *options,
                                             LiteRtEnvironment *environment);
using FnDestroyEnvironment = void (*)(LiteRtEnvironment environment);
using FnCreateOptions = LiteRtStatus (*)(LiteRtOptions *options);
using FnDestroyOptions = void (*)(LiteRtOptions options);
using FnSetHwAccelerators = LiteRtStatus (*)(LiteRtOptions options,
                                             int32_t hardware_accelerators);
using FnCreateModelFromFile = LiteRtStatus (*)(LiteRtEnvironment environment, const char *path,
                                               LiteRtModel *model);
using FnDestroyModel = void (*)(LiteRtModel model);
using FnCreateCompiledModel = LiteRtStatus (*)(LiteRtEnvironment environment, LiteRtModel model,
                                               LiteRtOptions options,
                                               LiteRtCompiledModel *compiled_model);
using FnDestroyCompiledModel = void (*)(LiteRtCompiledModel compiled_model);
using FnIsFullyAccelerated = LiteRtStatus (*)(LiteRtCompiledModel compiled_model,
                                              bool *fully_accelerated);
using FnSetCancellation = LiteRtStatus (*)(LiteRtCompiledModel compiled_model, void *data,
                                           bool (*check_cancelled_func)(void *));
using FnRunCompiledModel = LiteRtStatus (*)(LiteRtCompiledModel compiled_model,
                                            int32_t signature_index, size_t num_input_buffers,
                                            LiteRtTensorBuffer *input_buffers,
                                            size_t num_output_buffers,
                                            LiteRtTensorBuffer *output_buffers);
using FnCreateManagedTensorBuffer = LiteRtStatus (*)(LiteRtEnvironment environment,
                                                     int32_t buffer_type,
                                                     const RankedTensorType *tensor_type,
                                                     size_t buffer_size,
                                                     LiteRtTensorBuffer *buffer);
using FnLockTensorBuffer = LiteRtStatus (*)(LiteRtTensorBuffer tensor_buffer,
                                            void **host_mem_addr, int32_t lock_mode);
using FnUnlockTensorBuffer = LiteRtStatus (*)(LiteRtTensorBuffer tensor_buffer);
using FnDestroyTensorBuffer = void (*)(LiteRtTensorBuffer buffer);
using FnGetTensorBufferSize = LiteRtStatus (*)(LiteRtTensorBuffer tensor_buffer, size_t *size);

/** dlsym 收集齐的函数表。任何一项为空 = 运行时不可用。 */
struct Api {
    FnCreateEnvironment create_environment = nullptr;
    FnDestroyEnvironment destroy_environment = nullptr;
    FnCreateOptions create_options = nullptr;
    FnDestroyOptions destroy_options = nullptr;
    FnSetHwAccelerators set_hw_accelerators = nullptr;
    FnCreateModelFromFile create_model_from_file = nullptr;
    FnDestroyModel destroy_model = nullptr;
    FnCreateCompiledModel create_compiled_model = nullptr;
    FnDestroyCompiledModel destroy_compiled_model = nullptr;
    FnIsFullyAccelerated is_fully_accelerated = nullptr;
    FnSetCancellation set_cancellation = nullptr;
    FnRunCompiledModel run_compiled_model = nullptr;
    FnCreateManagedTensorBuffer create_managed_tensor_buffer = nullptr;
    FnLockTensorBuffer lock_tensor_buffer = nullptr;
    FnUnlockTensorBuffer unlock_tensor_buffer = nullptr;
    FnDestroyTensorBuffer destroy_tensor_buffer = nullptr;
    FnGetTensorBufferSize get_tensor_buffer_size = nullptr;

    /** 返回第一个缺失的符号名（用于诊断日志）；全齐返回 nullptr。 */
    const char *firstMissing() const;
};

}  // namespace litert_api
