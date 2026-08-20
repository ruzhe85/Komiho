# KomihoV2 — 图像增强（Image Enhancement）代码记忆

> 仿 `Komiho_阅读器_代码记忆.md` 风格整理。聚焦本仓库里「图像增强」相关代码：
> 它是一套 **Kotlin 调度层 + Coil 解码层 + C++/JNI native 实现** 的三层结构，
> 实现 4 种轻量算法（Anime4K-GPU、Lanczos3 / Catmull-Rom / Spline36-CPU），**不含任何重型 CNN 模型**。
>
> 路径基准：`komihoV2/`（仓库 `ruzhe85/komihoV2`）

---

## 1. 一句话结论

Komiho 的图像增强 = **MihonSY 自带能力**，直接复用，未自研。

- 阅读器在构造每页 Coil 请求时无条件打 `.enhanced(true)` 标记；
- `TachiyomiImageDecoder`（Coil 自定义 Decoder）读到该标记后，**解码后同步**调用 `MihonSyEnhancer.enhance()`；
- `MihonSyEnhancer` 按 `ReaderPreferences.enhancementMode` 选单一算法：
  - `1` = Anime4K（GPU/GLES 着色器，实时，最适合线稿/webtoon）
  - `2/3/4` = CPU 重采样（Lanczos3 / Catmull-Rom / Spline36）
  - `0` = 关闭（enhancer 直接跳过，原图返回）
- native 实现放在 `app/src/main/cpp/`，经 JNI 桥 `mihonsy_jni.cpp`，编译为 `libmihonsy-enhance.so`。

> ⚠️ 与全局记忆澄清：记忆里提到的「`mihon_img_upscale` 仓库 QNN/HTP NPU 超分调研」是**独立调研线**，**未落入本仓库**。本仓库增强是纯 CPU/GPU 轻量算法，无 NPU 路径。

---

## 2. 目录结构与关键文件

```
app/src/main/
├── java/eu/kanade/tachiyomi/
│   ├── util/MihonSyEnhancer.kt              # 【核心调度层】算法选择 + 执行 + 线程/进度
│   ├── ui/reader/setting/ReaderPreferences.kt   # 增强偏好（enhancementMode/anime4kMode/lanczosScale/showEnhancementStatus）
│   ├── data/coil/
│   │   ├── Utils.kt                          # Coil Options 扩展：enhanced()/customDecoder()/cropBorders()
│   │   └── TachiyomiImageDecoder.kt          # 【解码层】enhanced=true 时解码后同步跑增强
│   └── ui/reader/viewer/ReaderPageImageView.kt  # 阅读器页视图：构造 ImageRequest 时 .enhanced(true)
├── presentation/more/settings/screen/SettingsReaderScreen.kt  # 设置 UI：getEnhancementGroup()
└── cpp/
    ├── CMakeLists.txt        # 编 libmihonsy-enhance.so（链接 GLESv3/EGL/jnigraphics）
    ├── mihonsy_jni.cpp       # JNI 桥：nativeInitAnime4K / nativeProcessAnime4K / nativeLanczosProcess / nativeResample
    ├── anime4k.cpp / anime4k.h   # Anime4K GPU 实现（GLES 着色器管线）
    └── lanczos3.cpp          # CPU 重采样：Lanczos3 / Catmull-Rom / Spline36（可分离 1D 两趟）
    └── assets/anime4k/*.glsl # Anime4K 着色器（Clamp/Restore/Upscale 等）
```

---

## 3. 关键类与职责

| 类 / 文件 | 层 | 职责 |
|---|---|---|
| `MihonSyEnhancer` | Kotlin 调度 | 算法选择 (`enhance`)、Anime4K 初始化 (`initAnime4K`)、单线程串行执行、进度回调、HARDWARE/recycled 位图保护 |
| `TachiyomiImageDecoder` | Coil 解码 | enhanced=true 时：按需放大解码尺寸 → 解码 → 同步 `enhance()` → 失败回退原图；长条（webtoon）按宽度采样 |
| `Utils.enhanced` | Coil 扩展 | `ImageRequest.Builder.enhanced(true)` 把标记写入 `Options.extras`，Decoder 侧 `Options.enhanced` 读出 |
| `ReaderPreferences` | 设置 | 4 个增强偏好键（见 §5） |
| `SettingsReaderScreen.getEnhancementGroup` | UI | 「图像增强」设置分组：模式 / Anime4K 档位 / 缩放倍率 / 状态浮层开关 |
| `ReaderPageImageView` | 调用点 | 每页 `ImageRequest` 打 `.enhanced(true)` + `.customDecoder(true)` |
| `mihonsy_jni.cpp` | JNI | Kotlin ↔ native 桥接，GL 上下文线程安全 |
| `anime4k.cpp/.h` | native | Anime4K GLES 渲染管线（`load`/`process`/`get_output_size`/`get_max_texture_size`） |
| `lanczos3.cpp` | native | 可分离重采样内核实现（alpha 感知） |

---

## 4. 调用链（点开一页 → 增强生效）

```
ReaderPageImageView.loadImage()
  └─ ImageRequest.Builder
       .enhanced(true)            // Utils.kt 扩展，写入 Options.extras
       .customDecoder(true)
  └─ Coil ImageLoader
       └─ TachiyomiImageDecoder.Factory.create()   // options.enhanced || customDecoder → 接管
            └─ decode():
                 targetW/H = enhanceTarget(viewDim)   // 限 2048（不额外 2x，避免叠加放大浪费）
                 isTallStrip? 只按宽度采样（保整条高度）
                 bitmap = decoder.decode(sampleSize)
                 if (options.enhanced && enhancementMode != 0):
                     enhanced = MihonSyEnhancer.enhance(bitmap)   // 同步，后台线程
                     if (enhanced != null && enhanced !== bitmap) bitmap.recycle(); bitmap = enhanced
                 // 任何异常 → 保留原图（绝不黑屏）
            └─ DecodeResult(image = bitmap.asImage())
```

> 注意：`.enhanced(true)` 是**无条件**打的；是否真增强由 `enhancementMode != 0` 在 decoder 内决定。
> 即「关掉增强」= enhancer 立即返回原图，不走任何算法。

---

## 5. 增强模式与设置项

`ReaderPreferences.kt`（companion `EnhancementModes` 注释明确：`1 Anime4K (hidden)`）：

| 偏好键 | 类型/默认 | 含义 |
|---|---|---|
| `pref_enhancement_mode` | Int, 默认 `0` | `0` Off / `1` Anime4K / `2` Lanczos3 / `3` Catmull-Rom / `4` Spline36（单选择器，两算法互斥） |
| `pref_anime4k_mode` | Int, 默认 `0` | `0` Fast / `1` High / `2` Ultra（仅 mode==1 时启用） |
| `pref_lanczos_scale` | Int, 默认 `200` | `150/200/250/300` = `1.5x/2x/2.5x/3x`（仅 mode∈2..4 时启用） |
| `pref_show_enhancement_status` | Bool, 默认 `false` | 左下角「增强状态浮层」（耗时秒数 / OK / 跳过）独立开关 |

UI（`SettingsReaderScreen.getEnhancementGroup`）：
- 模式 `ListPreference`（entries = `EnhancementModes` 全 5 项）
- Anime4K 档位 `enabled = enhancementMode == 1`
- 缩放 `enabled = enhancementMode in 2..4`
- 状态浮层 `SwitchPreference`

---

## 6. 各算法实现要点

### 6.1 Anime4K（GPU / GLES，实时）

- 入口：`MihonSyEnhancer.initAnime4K(ctx, mode)` 加载着色器（assets/anime4k/*.glsl）：
  - Fast：Clamp + Restore_CNN_M
  - High：Clamp + Restore_CNN_VL
  - Ultra：Clamp + Restore_CNN_VL + Upscale_CNN_x2_VL（2x 输出）
- 渲染：`nativeProcessAnime4K` 用全图 framebuffer 跑 GLES 着色器，输出新 Bitmap。
- 纹理上限：`nativeGetMaxTextureSize()` 保护 —— 输入或（Ultra）输出超出 GPU `MAX_TEXTURE_SIZE` 则跳过（返回原图），避免黑帧/花屏。
- native 实现：`anime4k.cpp` 的 `Anime4K::load/process/get_output_size/get_max_texture_size`，含多处理 `eglMakeCurrent` 上下文绑定/解绑。

### 6.2 Lanczos3 / Catmull-Rom / Spline36（CPU 重采样）

- native：`lanczos3.cpp`
  - `lanczosKernel(a=3)` / `catmullRomKernel` / `spline36Kernel`
  - `resizeGeneric()`：**可分离两趟**（先水平→临时缓冲→再垂直），核可分解故每输出像素仅 `2*radius`  taps，比 2D 卷积快 ~3x 且结果一致。
  - **alpha 感知**：RGB 按 alpha 加权（`r += p[0]*pa*w`），输出 alpha = Σpa，避免 RGB_565 无 alpha 导致全透明黑帧。
  - `resizeWithKernel()`：kernel id `0/1/2` → Lanczos3 / Catmull-Rom / Spline36（radius 3/2/3）。
- JNI：`nativeLanczosProcess(bitmap, scale)` → kernel 0；`nativeResample(bitmap, scale, kernel)` → 通用。
- 边界：`scale<=1` 直接返回；输出尺寸限 `dw<=16384, dh<=65536`；处理后做**空白检测**（全透明/全黑 → 回收输出，返回原图）。
- Kotlin 侧 `MihonSyEnhancer.enhance`：
  ```kotlin
  val scale = preferences.lanczosScale.get() / 100f   // 1.5/2/2.5/3
  val argb = ensureArgb(input)                          // 非 ARGB_8888 则拷贝并强制不透明
  when (mode) {
    3 -> nativeResample(argb, scale, 1)   // Catmull-Rom
    4 -> nativeResample(argb, scale, 2)   // Spline36
    else -> nativeLanczosProcess(argb, scale)
  }.takeUnless { it === argb }
  ```

---

## 7. MihonSY 关键修复（代码注释里的重要坑）

这些都在源码注释标了 `MihonSY fix`，移植/重构时务必保留：

1. **Anime4K 模式切换失效**：仅用 `isAnime4kInitialized` 布尔缓存，Fast→Ultra 后仍返回旧着色器（Ultra 看着跟 Fast 一样）。
   → 改追踪 `anime4kInitializedMode`，模式变化必重载。
2. **A4K 纹理上限只看输入**：Ultra 输出 2x 超 GPU 上限 → 花屏。
   → `anime4kSupportsSize` 对 mode>=2 用 `scale=2` 校验输出尺寸。
3. **A4K GLES 上下文跨线程**：Coil 解码在线程池，A4K 上下文绑创建线程，在调用线程 `eglMakeCurrent` 会 `EGL_BAD_ACCESS`。
   → mode==1 时把所有 A4K 工作（init+process）都丢进单线程 `executor` 并用 `CountDownLatch` 阻塞解码线程。
4. **HARDWARE 位图增强**：读像素不可靠会全黑。
   → `enhance()` 入口 `input.config == HARDWARE` 直接跳过（解码期已是软件位图）。
5. **RGB_565 无 alpha**：Lanczos alpha 加权输出全透明黑。
   → `ensureArgb()` 拷贝为 ARGB_8888 且 `setHasAlpha(false)`。
6. **解码尺寸叠加放大**：解码 2x 又 Lanczos 2x → 实际 3-6x，绝大部分被显示缩回。
   → `enhanceTarget()` 仅用视图尺寸（封顶 2048），最终放大倍率正好等于设置值，~4x 更快。
7. **长条（webtoon）按高度采样压扁宽度**：strip 宽成了 270px。
   → `isTallStrip`（高/宽>2.5）时高度取完整源高，仅按宽度采样。
8. **OOM 崩溃阅读器**：`catch (e: Throwable)` 包住增强，含 `OutOfMemoryError`（Exception 抓不到）。
9. **黑帧兜底**：A4K process 失败 / Lanczos 输出空白 → 回收输出、返回原图，绝不显示黑帧。

---

## 8. 构建（CMake / JNI）

`app/src/main/cpp/CMakeLists.txt`：
```cmake
project(mihonsy-enhance)
set(CMAKE_CXX_STANDARD 17)
set(ENHANCE_SOURCES anime4k.cpp lanczos3.cpp mihonsy_jni.cpp)
add_library(mihonsy-enhance SHARED ${ENHANCE_SOURCES})
target_link_libraries(mihonsy-enhance android log jnigraphics GLESv3 EGL)
```
- Kotlin 侧 `MihonSyEnhancer.init { System.loadLibrary("mihonsy-enhance") }`。
- JNI 函数名约定：`Java_eu_kanade_tachiyomi_util_MihonSyEnhancer_nativeXxx`。
- 库名 = `libmihonsy-enhance.so`，随 `komihoDebug` 变体进 APK（CI 构建产物）。

---

## 9. 已做 / 待做（增强维度）

- ✅ 已做：4 算法实现 + Coil 解码集成 + 设置 UI + 单线程串行 + 进度浮层 + 全量黑屏/花屏兜底 + 长条处理。
- ⚠️ 设计取舍：Anime4K 在 `EnhancementModes` 枚举注释标 `hidden`，实际默认推荐 Lanczos3 系列（视觉更稳）；UI 列表项仍含全 5 项，是否真正对终端用户隐藏取决于 UI 过滤（当前 `getEnhancementGroup` 用全量 `EnhancementModes`）。
- 🔲 未做（路线规划，未落本仓库）：NPU 超分（QNN/HTP，来自 `mihon_img_upscale` 调研）；重型 CNN（waifu2x/Real-ESRGAN）明确**不纳入**。

---

## 10. 关键约定与坑（速查）

- 增强偏好在 `tachiyomi.domain` 包（`ReaderPreferences`），不是 `eu.kanade.domain.*`。
- `enhanced` 是 Coil `Extras.Key`，经 `Options.enhanced` 在 Decoder 侧读出；不要在调用点直接判 `enhancementMode`。
- 增强**永远同步跑在 Coil 后台线程**，不阻塞 UI；A4K 额外收进单线程 `executor`。
- 任何失败路径都返回**原图**（或 null → reader 报「跳过」），**黑帧在架构层面被禁止**。
- 复用阅读器真实解码：`TachiyomiImageDecoder` 对**所有格式**生效（JPG/PNG 也增强），不止 system-unsupported。
- 本机无 Android SDK/gradle，增强 native 编译验证走 CI（GitHub Actions，`ci-apk` Release 资产）。
