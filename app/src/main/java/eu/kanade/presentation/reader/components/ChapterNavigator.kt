package eu.kanade.presentation.reader.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalSlider
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.layout.ContentScale
import coil3.compose.SubcomposeAsyncImage
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import eu.kanade.presentation.util.isTabletUi
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import kotlinx.coroutines.flow.collect
import kotlin.math.roundToInt

enum class ChapterNavigatorType {
    HORIZONTAL_LTR,
    HORIZONTAL_RTL,
    VERTICAL_LEFT,
    VERTICAL_RIGHT,
    ;

    fun isHorizontal() = this in setOf(HORIZONTAL_LTR, HORIZONTAL_RTL)
}

@Composable
fun ChapterNavigator(
    type: ChapterNavigatorType,
    onNextChapter: () -> Unit,
    enabledNext: Boolean,
    onPreviousChapter: () -> Unit,
    enabledPrevious: Boolean,
    currentPage: Int,
    // SY -->
    currentPageText: String,
    // SY <--
    totalPages: Int,
    onPageIndexChange: (Int) -> Unit,
    onPageIndexChangeFinished: () -> Unit,
    // SY --> Komiho: 进度气泡缩略图——给定 0-based 页码返回 Coil data 模型（MangaCover，带 Komga 鉴权），
    // 非 Komga 源或未开启（count=0）时由调用方传 null，气泡不显示。
    thumbnailModelForPage: ((Int) -> Any?)? = null,
    // SY --> Komiho: 缩略图条显示数量（0–5，0 = 关闭），由设置页控制。
    thumbnailCount: Int = 0,
    // SY <--
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current

    // SY --> Komiho: 进度条预览模式——默认不出图，按压进度条才展开缩略图条；
    // 展开期间拖动只移动预览位置、不跳转章节，点击缩略图才提交跳转。
    // previewSupported：来源能提供缩略图模型且数量 > 0；不支持则完全保持原生「拖动即时跳转」行为。
    val previewSupported = thumbnailModelForPage != null && totalPages > 1 && thumbnailCount > 0
    var previewOpen by remember { mutableStateOf(false) }
    // SY <--

    // Komiho (2026-09-23): guard the slider state against a not-yet-loaded page count.
    // With totalPages < 1 `valueRange` becomes reversed (1f..0f), and SliderState's value
    // setter coerces into it — Float.coerceIn throws when min > max, so the assignment below
    // used to crash on every recomposition (the Slider itself is already gated on
    // totalPages > 1, but the assignment was not). Clamping is a no-op for totalPages >= 2,
    // i.e. every case where the navigator actually renders.
    val state = remember(totalPages) {
        SliderState(
            value = currentPage.toFloat(),
            steps = (totalPages - 2).coerceAtLeast(0),
            valueRange = 1f..totalPages.coerceAtLeast(1).toFloat(),
        )
    }

    // SY --> Komiho: 拖动过程中只记录目标页，**不做任何跳转/加载**——
    // 此前 onValueChange 每帧都调用 onPageIndexChange，拖动途中会疯狂解码加载每一页；
    // 现在无论是否开启缩略图，都统一为「停下来才跳转加载」。
    var previewPage by remember { mutableIntStateOf(-1) }
    // SY --> Komiho: 统一的提交跳转入口（松手提交 / 点击缩略图提交都走这里）。
    // 拖动过程中绝不跳转加载；目标就是当前页时也不需要任何加载。
    // isScrollingThroughPages 的复位交给下方 LaunchedEffect(currentPage)，等页面真正到位后才结束——
    // 否则 jump 触发 PagerViewer.onPageSelected 时会立刻把菜单收掉。
    val commitJump: (Int) -> Unit = { page ->
        previewOpen = false
        if (page == currentPage) {
            previewPage = -1
        } else {
            previewPage = page
            onPageIndexChange(page)
        }
    }
    state.onValueChange = {
        previewPage = (it.roundToInt() - 1).coerceIn(0, (totalPages - 1).coerceAtLeast(0))
    }
    state.onValueChangeFinished = {
        val target = previewPage
        // 开启缩略图时处于「预览模式」：松手不跳转，等用户点击目标缩略图再提交。
        if (target >= 0 && !previewSupported) {
            commitJump(target)
        }
    }
    // SY <--
    // SY --> Komiho: 滑块位置每帧同步——有预览/待跳转目标时跟随该目标，否则跟随 currentPage。
    // 这里是推动滑块移动的唯一入口：拖动期间不再跳转 ⇒ currentPage 不变，若跟随 currentPage 或不赋值，
    // 滑块都会停在原地不动。
    if (totalPages > 1) {
        state.value = (if (previewPage >= 0) previewPage else currentPage).toFloat()
    }
    // SY <--

    val interactionSource = remember { MutableInteractionSource() }
    val sliderDragged by interactionSource.collectIsDraggedAsState()
    LaunchedEffect(currentPage) {
        if (sliderDragged) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    // SY --> Komiho: 按压进度条（点击轨道或开始拖动）才展开缩略图条。
    LaunchedEffect(interactionSource, previewSupported) {
        interactionSource.interactions.collect { interaction ->
            if (previewSupported && interaction is PressInteraction.Press) {
                previewOpen = true
            }
        }
    }
    // SY --> Komiho: 页面真正发生变化后收尾：结束「滚动中」状态、清掉待跳转目标，
    // 预览条也一并收起（恢复「默认不出图」），滑块随之恢复跟随 currentPage。
    LaunchedEffect(currentPage) {
        if (previewPage >= 0) {
            onPageIndexChangeFinished()
            previewPage = -1
        }
        if (previewOpen) {
            previewOpen = false
        }
    }
    // SY <--

    val isTabletUi = isTabletUi()
    val mainAxisPadding = if (isTabletUi) 24.dp else 8.dp

    // Match with toolbar background color set in ReaderActivity
    val backgroundColor = MaterialTheme.colorScheme
        .surfaceColorAtElevation(3.dp)
        .copy(alpha = if (isSystemInDarkTheme()) 0.9f else 0.95f)
    val buttonColor = IconButtonDefaults.filledIconButtonColors(
        containerColor = backgroundColor,
        disabledContainerColor = backgroundColor,
    )

    // SY --> Komiho: 用 Box 包裹；水平模式把预览条排在进度条上方（不遮挡），垂直模式浮在顶部。
    // 拖动期间不跳转 ⇒ currentPage / currentPageText 都不会变，页码会「卡住不更新」，
    // 所以这里按「目标页」实时生成页码文本。
    val pageLabel = if (previewPage >= 0) previewPageLabel(currentPageText, previewPage) else currentPageText
    // SY <--
    Box(modifier) {
        if (type.isHorizontal()) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // SY --> 预览条在进度条上方（不遮挡进度条）；默认不显示，按压进度条后才展开。
                if (thumbnailModelForPage != null && previewSupported && previewOpen) {
                    val activePage = if (previewPage >= 0) previewPage else currentPage
                    PagePreviewStrip(
                        centerPage = activePage,
                        count = thumbnailCount,
                        totalPages = totalPages,
                        modelForPage = thumbnailModelForPage,
                        // 拖动中不生成缩略图（只占位），松手后才加载。
                        loadImages = !sliderDragged,
                        onPageClick = commitJump,
                        modifier = Modifier,
                    )
                }
                // SY <--
                HorizontalChapterNavigator(
                    isRtl = type == ChapterNavigatorType.HORIZONTAL_RTL,
                    state = state,
                    onNextChapter = onNextChapter,
                    enabledNext = enabledNext,
                    onPreviousChapter = onPreviousChapter,
                    enabledPrevious = enabledPrevious,
                    // SY -->
                    currentPageText = pageLabel,
                    // SY <--
                    totalPages = totalPages,
                    interactionSource = interactionSource,
                    mainAxisPadding = mainAxisPadding,
                    backgroundColor = backgroundColor,
                    buttonColor = buttonColor,
                    modifier = Modifier,
                )
            }
        } else {
            VerticalChapterNavigator(
                state = state,
                onNextChapter = onNextChapter,
                enabledNext = enabledNext,
                onPreviousChapter = onPreviousChapter,
                enabledPrevious = enabledPrevious,
                // SY -->
                currentPageText = pageLabel,
                // SY <--
                totalPages = totalPages,
                interactionSource = interactionSource,
                mainAxisPadding = mainAxisPadding,
                backgroundColor = backgroundColor,
                buttonColor = buttonColor,
                modifier = Modifier,
            )
            // SY --> 竖向模式预览条浮在顶部居中（不挡侧边竖向滑块）；默认不显示，按压进度条后才展开。
            if (thumbnailModelForPage != null && previewSupported && previewOpen) {
                val activePage = if (previewPage >= 0) previewPage else currentPage
                PagePreviewStrip(
                    centerPage = activePage,
                    count = thumbnailCount,
                    totalPages = totalPages,
                    modelForPage = thumbnailModelForPage,
                    // 拖动中不生成缩略图（只占位），松手后才加载。
                    loadImages = !sliderDragged,
                    onPageClick = commitJump,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
            // SY <--
        }
    }
}

// SY --> Komiho: 进度条上方的预览条——以 centerPage 为中心显示共 count 张缩图（左右尽量对称），
// 每张上方标注页码；边缘页自动收敛。
// loadImages=false（正在拖动）时只画占位框，完全不发起 Coil 请求：
// 实现「快速滑动时不生成图，等待滑动停止后再生成」，避免边拖边解码造成的卡顿与整本拉取。
// 点击某张缩略图 → 提交跳转到该页（onPageClick）。
@Composable
private fun PagePreviewStrip(
    centerPage: Int,
    count: Int,
    totalPages: Int,
    modelForPage: (Int) -> Any?,
    loadImages: Boolean,
    onPageClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val itemSize = (screenWidth * 0.26f).coerceIn(96.dp, 160.dp)
    val half = count / 2
    val pages = buildList {
        for (p in (centerPage - half)..(centerPage + (count - 1 - half))) {
            if (p in 0 until totalPages) add(p)
        }
    }
    Row(
        modifier = modifier
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.96f))
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (p in pages) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable { onPageClick(p) },
            ) {
                Text(
                    text = (p + 1).toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (loadImages) {
                    SubcomposeAsyncImage(
                        model = modelForPage(p),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .size(itemSize)
                            .clip(RoundedCornerShape(6.dp)),
                        loading = {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        },
                    )
                } else {
                    // 拖动中：同尺寸占位，保持布局稳定且不触发任何解码/网络请求。
                    Box(modifier = Modifier.size(itemSize))
                }
            }
        }
    }
}
// SY <--

// SY --> Komiho: 按「拖动目标页」生成页码文本。拖动期间不跳转，currentPageText 恒定不变，
// 若直接显示它会表现为「滑动时页数不动」。这里用 0-based 目标下标换算 1-based 页码；
// 双页模式沿用当前文本的形制（含 RTL 的降序 "n+1-n"），避免拖动时格式来回跳变。
private fun previewPageLabel(currentPageText: String, targetIndex: Int): String {
    val n = targetIndex + 1
    val parts = currentPageText.split("-")
    if (parts.size != 2) return n.toString()
    val a = parts[0].trim().toIntOrNull() ?: return n.toString()
    val b = parts[1].trim().toIntOrNull() ?: return n.toString()
    return if (a > b) "${n + 1}-$n" else "$n-${n + 1}"
}
// SY <--

@Composable
fun HorizontalChapterNavigator(
    isRtl: Boolean,
    state: SliderState,
    onNextChapter: () -> Unit,
    enabledNext: Boolean,
    onPreviousChapter: () -> Unit,
    enabledPrevious: Boolean,
    // SY -->
    currentPageText: String,
    // SY <--
    totalPages: Int,
    interactionSource: MutableInteractionSource,
    mainAxisPadding: Dp,
    backgroundColor: Color,
    buttonColor: IconButtonColors,
    modifier: Modifier = Modifier,
) {
    val layoutDirection = if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr

    // We explicitly handle direction based on the reader viewer rather than the system direction
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = mainAxisPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledIconButton(
                enabled = if (isRtl) enabledNext else enabledPrevious,
                onClick = if (isRtl) onNextChapter else onPreviousChapter,
                colors = buttonColor,
            ) {
                Icon(
                    imageVector = Icons.Outlined.SkipPrevious,
                    contentDescription = stringResource(
                        if (isRtl) MR.strings.action_next_chapter else MR.strings.action_previous_chapter,
                    ),
                )
            }

            if (totalPages > 1) {
                CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(24.dp))
                            .background(backgroundColor)
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // SY -->
                        Text(text = currentPageText)
                        // SY <--

                        Slider(
                            state = state,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp),
                            interactionSource = interactionSource,
                        )

                        Text(text = totalPages.toString())
                    }
                }
            } else {
                Spacer(Modifier.weight(1f))
            }

            FilledIconButton(
                enabled = if (isRtl) enabledPrevious else enabledNext,
                onClick = if (isRtl) onPreviousChapter else onNextChapter,
                colors = buttonColor,
            ) {
                Icon(
                    imageVector = Icons.Outlined.SkipNext,
                    contentDescription = stringResource(
                        if (isRtl) MR.strings.action_previous_chapter else MR.strings.action_next_chapter,
                    ),
                )
            }
        }
    }
}

@Composable
fun VerticalChapterNavigator(
    state: SliderState,
    onNextChapter: () -> Unit,
    enabledNext: Boolean,
    onPreviousChapter: () -> Unit,
    enabledPrevious: Boolean,
    // SY -->
    currentPageText: String,
    // SY <--
    totalPages: Int,
    interactionSource: MutableInteractionSource,
    mainAxisPadding: Dp,
    backgroundColor: Color,
    buttonColor: IconButtonColors,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .padding(vertical = mainAxisPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        FilledIconButton(
            enabled = enabledPrevious,
            onClick = onPreviousChapter,
            colors = buttonColor,
        ) {
            Icon(
                imageVector = Icons.Outlined.SkipPrevious,
                contentDescription = stringResource(MR.strings.action_previous_chapter),
                modifier = Modifier.rotate(90f),
            )
        }

        if (totalPages > 1) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(backgroundColor)
                    .padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // SY -->
                Text(text = currentPageText)
                // SY <--

                VerticalSlider(
                    state = state,
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 8.dp),
                    interactionSource = interactionSource,
                )

                Text(text = totalPages.toString())
            }
        } else {
            Spacer(Modifier.weight(1f))
        }

        FilledIconButton(
            enabled = enabledNext,
            onClick = onNextChapter,
            colors = buttonColor,
        ) {
            Icon(
                imageVector = Icons.Outlined.SkipNext,
                contentDescription = stringResource(MR.strings.action_next_chapter),
                modifier = Modifier.rotate(90f),
            )
        }
    }
}

@Preview
@Composable
private fun ChapterNavigatorPreview() {
    var currentPage by remember { mutableIntStateOf(1) }
    TachiyomiPreviewTheme {
        ChapterNavigator(
            type = ChapterNavigatorType.VERTICAL_RIGHT,
            onNextChapter = {},
            enabledNext = true,
            onPreviousChapter = {},
            enabledPrevious = true,
            currentPage = currentPage,
            totalPages = 10,
            onPageIndexChange = { currentPage = (it + 1) },
            onPageIndexChangeFinished = {},
            // SY -->
            currentPageText = "1",
            // SY <--
        )
    }
}
