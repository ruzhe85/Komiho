package eu.kanade.tachiyomi.ui.reader.viewer.pager

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import coil3.BitmapImage
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.crossfade
import coil3.size.Precision
import eu.kanade.tachiyomi.data.coil.cropBorders
import eu.kanade.tachiyomi.data.coil.customDecoder
import eu.kanade.tachiyomi.data.coil.enhanced
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.viewer.isStandardImageStream
import logcat.LogPriority
import okio.Buffer
import okio.BufferedSource
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Pager 单页的「预处理结果」（SY: Page 模式流畅度优化 A+B+C）。
 *
 * Webtoon 流畅的核心是「解码好了等你滑」（RecyclerView 提前 bind）；Pager 过去是
 * 「你滑了才开始解码」（holder 在滑动当下才实例化并跑完整条 stream→处理→解码链）。
 * 本类把这条链的产物缓存下来：
 * - [source]：处理完成后的整图（内存 Buffer 系；回放用 [BufferedSource.peek]，消费不消耗本体）
 * - [isAnimated] / [background]：C 优化——动图/背景标志全链路只解析一次
 * - [decodedBitmap]：图像增强开启时的屏幕尺寸预解码结果（Lanczos 在滑动前完成），
 *   非空时 holder 直接走 bitmap 路径，不再二次解码
 *
 * [enhancementMode] / [cropBorders] 记录生成时的设置，读取时校验，设置变更即失效。
 */
class PagerPreparedPage(
    val source: BufferedSource,
    val isAnimated: Boolean,
    val background: Drawable?,
    val decodedBitmap: Bitmap?,
    val enhancementMode: Int,
    val cropBorders: Boolean,
)

/**
 * 预处理结果 LRU 缓存（键 = ReaderPage 对象同一性 + 双页配对；容量 3 ≈ 前页/当前页/后页）。
 * ReaderPage 未覆写 equals/hashCode → Pair 作键即身份键；章节重载会生成新 Page 对象，
 * 天然不会命中旧条目，旧条目由 LRU 淘汰。
 */
class PagerPreparedCache(private val maxSize: Int = 3) {

    private val lock = Any()
    private val map = LinkedHashMap<Pair<ReaderPage, ReaderPage?>, PagerPreparedPage>(8, 0.75f, true)

    fun key(page: ReaderPage, extraPage: ReaderPage?): Pair<ReaderPage, ReaderPage?> = page to extraPage

    fun get(key: Pair<ReaderPage, ReaderPage?>): PagerPreparedPage? = synchronized(lock) {
        val value = map[key] ?: return null
        if (value.enhancementMode != Injekt.get<ReaderPreferences>().enhancementMode.get()) {
            map.remove(key)
            return null
        }
        value
    }

    fun put(key: Pair<ReaderPage, ReaderPage?>, value: PagerPreparedPage) = synchronized(lock) {
        map[key] = value
        while (map.size > maxSize) {
            val it = map.entries.iterator()
            if (!it.hasNext()) break
            it.next()
            it.remove()
        }
    }

    fun clear() = synchronized(lock) { map.clear() }
}

/**
 * 纯 Prepare 管线（无任何副作用），holder 与后台预热共用。
 *
 * 与 holder 内旧管线的唯一区别：所有带副作用的分支（双页分割宽页的 onPageSplit、
 * 双页合并的 fullPage/splitDoublePages）在这里直接返回 null——预热方放弃、holder
 * 回退到旧管线。常规单页配置（绝大多数场景）完全走本管线。
 */
object PagerPagePreparer {

    /**
     * 处理一页并返回可缓存的预处理结果；返回 null 表示命中副作用分支（或流不可用）。
     * [viewHeight] 用于宽页居中边距计算；holder 传自身高度，预热传 pager 高度（两者等高）。
     */
    suspend fun preparePure(
        viewer: PagerViewer,
        page: ReaderPage,
        extraPage: ReaderPage?,
        viewHeight: Int,
    ): PagerPreparedPage? {
        val streamFn = page.stream ?: return null
        val config = viewer.config
        val enhancementMode = Injekt.get<ReaderPreferences>().enhancementMode.get()
        return withIOContext {
            try {
                // C：整图一次性物化进内存 Buffer（共享分段），后续所有 peek/回放零重复 IO
                val source1 = streamFn().buffered(16).use { Buffer().readFrom(it) }
                val source2 = extraPage?.stream?.invoke()?.buffered(16)?.use { Buffer().readFrom(it) }
                // C：动图标志只嗅探一次，handleWideImage 等下游直接复用（旧实现会重复嗅探）
                val isAnimated = ImageUtil.isAnimatedAndSupported(source1) ||
                    (source2 != null && ImageUtil.isAnimatedAndSupported(source2))

                val itemSource: BufferedSource = if (config.dualPageSplit) {
                    processPure(viewer, page, source1) ?: return@withIOContext null
                } else {
                    mergePure(viewer, page, extraPage, source1, source2, isAnimated, viewHeight)
                        ?: return@withIOContext null
                }

                val background = if (!isAnimated && config.automaticBackground) {
                    ImageUtil.chooseBackground(viewer.activity, itemSource.peek())
                } else {
                    null
                }

                // A：增强开启时预解码到屏幕尺寸（Lanczos 同步在解码器内完成），滑动前就绪
                val bitmap = maybePreDecodeEnhanced(viewer, itemSource, isAnimated, enhancementMode, config.imageCropBorders)

                PagerPreparedPage(itemSource, isAnimated, background, bitmap, enhancementMode, config.imageCropBorders)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Throwable) {
                logcat(LogPriority.INFO, e) { "pager preparePure failed, falling back to legacy path" }
                null
            }
        }
    }

    /** 对应 PagerPageHolder.process() 的纯版本：宽页分割需回调 onPageSplit → 纯管线放弃。 */
    private fun processPure(viewer: PagerViewer, page: ReaderPage, imageSource: BufferedSource): BufferedSource? {
        val config = viewer.config
        if (config.dualPageRotateToFit) {
            return if (ImageUtil.isWideImage(imageSource)) {
                val rotation = if (config.dualPageRotateToFitInvert) -90f else 90f
                ImageUtil.rotateImage(imageSource, rotation)
            } else {
                imageSource
            }
        }

        if (page is eu.kanade.tachiyomi.ui.reader.model.InsertPage) {
            return splitInHalfPure(viewer, page, imageSource)
        }

        if (!ImageUtil.isWideImage(imageSource)) {
            return imageSource
        }

        // 宽页 + 双页分割：旧管线会 onPageSplit 插入 InsertPage（副作用）→ 放弃
        return null
    }

    /** 对应 PagerPageHolder.mergePages() 的纯版本：双页合并全程有副作用 → 放弃。 */
    private fun mergePure(
        viewer: PagerViewer,
        page: ReaderPage,
        extraPage: ReaderPage?,
        imageSource: BufferedSource,
        imageSource2: BufferedSource?,
        isAnimated: Boolean,
        viewHeight: Int,
    ): BufferedSource? {
        if (imageSource2 == null) {
            return handleWideImagePure(viewer, imageSource, isAnimated, viewHeight)
        }
        // 原实现无副作用的唯一分支：fullPage 已置位时直接原样返回
        if (page.fullPage) return imageSource
        return null
    }

    /** 对应 PagerPageHolder.handleWideImage()，C：复用已解析的 isAnimated，不再重复嗅探。 */
    private fun handleWideImagePure(
        viewer: PagerViewer,
        imageSource: BufferedSource,
        isAnimated: Boolean,
        viewHeight: Int,
    ): BufferedSource {
        val config = viewer.config
        return if (
            !isAnimated &&
            viewHeight > 0 &&
            ImageUtil.isWideImage(imageSource) &&
            config.centerMarginType and PagerConfig.CenterMarginType.WIDE_PAGE_CENTER_MARGIN > 0 &&
            !config.imageCropBorders
        ) {
            ImageUtil.addHorizontalCenterMargin(imageSource, viewHeight, viewer.context)
        } else {
            imageSource
        }
    }

    /** 对应 PagerPageHolder.splitInHalf() 的纯版本（仅 InsertPage 分支会走到）。 */
    private fun splitInHalfPure(viewer: PagerViewer, page: ReaderPage, imageSource: BufferedSource): BufferedSource {
        var side = when {
            viewer is L2RPagerViewer && page is eu.kanade.tachiyomi.ui.reader.model.InsertPage -> ImageUtil.Side.RIGHT
            viewer !is L2RPagerViewer && page is eu.kanade.tachiyomi.ui.reader.model.InsertPage -> ImageUtil.Side.LEFT
            viewer is L2RPagerViewer -> ImageUtil.Side.LEFT
            else -> ImageUtil.Side.RIGHT
        }
        if (viewer.config.dualPageInvert) {
            side = when (side) {
                ImageUtil.Side.RIGHT -> ImageUtil.Side.LEFT
                ImageUtil.Side.LEFT -> ImageUtil.Side.RIGHT
            }
        }
        val sideMargin = if (
            (viewer.config.centerMarginType and PagerConfig.CenterMarginType.DOUBLE_PAGE_CENTER_MARGIN) > 0 &&
            viewer.config.doublePages &&
            !viewer.config.imageCropBorders
        ) {
            48
        } else {
            0
        }
        return ImageUtil.splitInHalf(imageSource, side, sideMargin)
    }

    /**
     * 增强预解码：与 ReaderPageImageView 增强路径同参（customDecoder + enhanced + 屏幕尺寸），
     * 用 [Buffer.clone] 喂数据不消耗缓存本体。失败静默返回 null，holder 回退原路径。
     */
    private suspend fun maybePreDecodeEnhanced(
        viewer: PagerViewer,
        source: BufferedSource,
        isAnimated: Boolean,
        enhancementMode: Int,
        cropBorders: Boolean,
    ): Bitmap? {
        if (isAnimated || enhancementMode == 0) return null
        if (!isStandardImageStream(source)) return null
        val width = viewer.pager.width
        val height = viewer.pager.height
        if (width <= 0 || height <= 0) return null
        return runCatching {
            val context = viewer.context
            val request = ImageRequest.Builder(context)
                .data(source.peek())
                .memoryCachePolicy(CachePolicy.DISABLED)
                .diskCachePolicy(CachePolicy.DISABLED)
                .enhanced(true)
                .customDecoder(true)
                .size(width, height)
                .precision(Precision.INEXACT)
                .cropBorders(cropBorders)
                .crossfade(false)
                .build()
            val result = context.imageLoader.execute(request) as? SuccessResult
            (result?.image as? BitmapImage)?.bitmap
        }
            .onFailure { logcat(LogPriority.INFO, it) { "pager pre-decode failed, holder will decode in-view" } }
            .getOrNull()
    }
}
