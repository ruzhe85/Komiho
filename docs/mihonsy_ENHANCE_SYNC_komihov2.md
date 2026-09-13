# MihonSY 图像增强最新代码整理（供 komihov2 同步）

整理时间：2026-08-22
基准：MihonSY 1.0.7（commit add2c9317，已发布 v1.0.7 正式版）
目标：komihoV2 同步到与 MihonSY 1.0.7 一致的图像增强状态

## 当前策略（MihonSY 1.0.7 已落地的状态）

- **只保留轻量 CPU 重采样**：Lanczos3（index 2）、Catmull-Rom（index 3）。
- **Anime4K（GPU shader）已屏蔽**：native 源不再参与编译，Kotlin 调用路径全部注释，UI 隐藏。
- **Spline36 已移除**：kernel 注释掉、resizeWithKernel 的 case 2 注释掉、UI 隐藏（用户结论「效果不佳」）。
- 磁盘上 anime4k/*.glsl 资源文件**保留不删**（向后兼容，但不编译、不加载）。
- 索引 0/1/4 仍保留在枚举与 index map 中，仅用于**兼容旧存储值**，UI 全部隐藏，运行时不会进入这些分支。

## 需同步的 6 个文件（MihonSY 最新 → komihov2）

### 1. `app/src/main/cpp/CMakeLists.txt`
- 注释掉 `anime4k.cpp`、`mihonsy_jni.cpp`（移出 ENHANCE_SOURCES）。
- 注释掉 `GLESv3`、`EGL` 链接（仅 Anime4K 需要）。
- 顶部注释改为「Lanczos3 / Catmull-Rom CPU resampling only」。

### 2. `app/src/main/cpp/lanczos3.cpp`
- `spline36Kernel` 函数体整体注释（保留为参考，不参与编译）。
- `resizeWithKernel` 内 `case 2`（Spline36）整体注释。
- 活跃 kernel：`lanczosKernel`(22)、`catmullRomKernel`(29)；活跃 case：0=Lanczos3、1=Catmull-Rom。

### 3. `app/src/main/java/eu/kanade/tachiyomi/util/MihonSyEnhancer.kt`
- 注释 `import android.app.Application` / `import android.content.Context`（仅 A4K 用）。
-  Kopf 注释说明 Anime4K 已禁用；算法列表改为「Lanczos3 / Catmull-Rom」。
- 注释 `ANIME4K_ASSET_DIR` 常量、A4K 状态字段（`isAnime4kInitialized`/`anime4kInitializedMode`）、JNI 声明（`nativeInitAnime4K`/`nativeGetMaxTextureSize`/`nativeProcessAnime4K`）、`initAnime4K()`/`anime4kSupportsSize()` 函数。
- `enhance()` 选择器：index 1（A4K）分支整体注释；`in 2..3` 覆盖 Lanczos3/Catmull-Rom；删除 `4 -> nativeResample(..., 2)`。

### 4. `app/src/main/java/eu/kanade/tachiyomi/ui/reader/setting/ReaderPreferences.kt`
- `enhancementAlgorithm` 注释说明 0/1/4 中 1、4 为 disabled，仅保留索引兼容。
- 注释 `anime4kMode` Preference 声明。
- `entries` 列表中 `enhancement_anime4k`(1)、`enhancement_spline36`(4) 标注「hidden in UI」，实际由 ReadingModePage 隐藏。
- 注释 `Anime4kModes` 列表。

### 5. `app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsReaderScreen.kt`
- 注释掉「Anime4K 画质选择」ListPreference（enabled = enhancementMode == 1 那段）。

### 6. `app/src/main/java/eu/kanade/presentation/reader/settings/ReadingModePage.kt`
- 隐藏规则：`if (index == 1 || index == 4) return@forEachIndexed`（同时隐藏 A4K 与 Spline36）。
- 芯片显示逻辑：`enhancementMode == 1 || enhancementMode == 4` 不显示额外 chip；`enhancementMode in 2..3` 显示缩放选择。

## LZ3 速度大优化（已对齐，komihov2 无需改动）

用户要求「包括 lz3 速度大优化的代码」——经核对，这部分**两边已经一致**，无需同步。MihonSY 的速度优化来自以下提交，komihov2 当前代码均已包含：

- `3cc8f57de` perf: LZ3 ~12x faster — decode at view size + separable convolution
- `4348ee416` perf(cpp): adopt precomputed-plan resampler + opaque fast path
- `06e8bb612` perf(cpp): sync resampler optimizations from KomihoV2（互相同步过）

具体已对齐的内容：

1. **cpp 层 `lanczos3.cpp`**（除 Spline36 注释外字节一致）：
   - `KernelLUT` 查表替代实时 kernel 计算（int16 定点权重）。
   - `ResamplePlan` 预计算每行/列的采样索引与权重（separable 可分离卷积，x/y 方向各算一次）。
   - `resizeOpaque` opaque fast path：漫画最常见的全不透明 RGBA_8888 走分支无关热循环，跳过 alpha 归一化。
   - 按视图尺寸解码后再放大（见下方第 3 点），避免大图无效重采样。

2. **Kotlin `MihonSyEnhancer.kt` 速度相关**（两边均有）：
   - 单线程 `executor` 串行化增强，避免 CPU 争用。
   - `submit()` + `SystemClock` 毫秒计时 + 500ms ticker（阅读器显示 stopwatch）。
   - HARDWARE bitmap 直接 skip、isRecycled 早退。
   - （差异仅在 A4K 分支：MihonSY 注释、komihov2 启用，属上一节同步范围。）

3. **Kotlin `TachiyomiImageDecoder.kt`**（字节一致）：
   - `enhanceTarget()`：增强时仅按视图尺寸（封顶）解码，Lanczos3 自身再放大到目标倍率——避免先解超大多余像素再缩小，**约 4x 更快**。
   - `options.enhanced` 时对所有图片格式走此解码器，解码后同步调用 `MihonSyEnhancer.enhance()`。

> 结论：LZ3 速度优化在 komihov2 已完整存在，本次同步只需处理上一节的「A4K 屏蔽 + Spline36 移除」6 个文件差异。

## 无需改动（两边已一致）
- `app/src/main/cpp/lanczos3.cpp`：除 Spline36 注释外字节一致（LZ3 速度优化已对齐）。
- `app/src/main/cpp/anime4k.cpp` / `anime4k.h`：字节一致，保留即可。
- `app/src/main/java/eu/kanade/tachiyomi/data/coil/TachiyomiImageDecoder.kt`：字节一致（含 decode-at-view-size 速度优化）。
- `app/src/main/java/eu/kanade/tachiyomi/data/coil/Utils.kt`：一致。
- `app/src/main/assets/anime4k/*.glsl`：保留，不编译。

## 同步后 komihov2 应达成的效果
- 构建不再链接 GLESv3/EGL，APK 体积减小，无 GPU shader 初始化路径。
- 阅读器图像增强仅剩 Lanczos3 / Catmull-Rom 两个 CPU 选项。
- 旧用户若存过 enhancementMode=1 或 4，UI 不显示对应 chip，运行时因分支被注释不会崩溃（MihonSyEnhancer 选择器 1/4 无匹配 → 走默认 Off）。
