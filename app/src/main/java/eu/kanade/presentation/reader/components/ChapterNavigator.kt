package eu.kanade.presentation.reader.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
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
    // 非 Komga 源或未开启时由调用方传 null，气泡不显示。
    thumbnailModelForPage: ((Int) -> Any?)? = null,
    // SY <--
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current

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
    if (totalPages > 1) {
        state.value = currentPage.toFloat()
    }
    // SY --> Komiho: 拖动时记录当前预览页码，用于进度气泡缩略图。
    var previewPage by remember { mutableIntStateOf(-1) }
    state.onValueChange = {
        onPageIndexChange(it.roundToInt() - 1)
        previewPage = (it.roundToInt() - 1).coerceIn(0, (totalPages - 1).coerceAtLeast(0))
    }
    state.onValueChangeFinished = {
        onPageIndexChangeFinished()
        previewPage = -1
    }
    // SY <--

    val interactionSource = remember { MutableInteractionSource() }
    val sliderDragged by interactionSource.collectIsDraggedAsState()
    LaunchedEffect(currentPage) {
        if (sliderDragged) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

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

    // SY --> Komiho: 用 Box 包裹，便于在滑块上方/侧边叠加进度气泡缩略图。
    Box(modifier) {
        if (type.isHorizontal()) {
            HorizontalChapterNavigator(
                isRtl = type == ChapterNavigatorType.HORIZONTAL_RTL,
                state = state,
                onNextChapter = onNextChapter,
                enabledNext = enabledNext,
                onPreviousChapter = onPreviousChapter,
                enabledPrevious = enabledPrevious,
                // SY -->
                currentPageText = currentPageText,
                // SY <--
                totalPages = totalPages,
                interactionSource = interactionSource,
                mainAxisPadding = mainAxisPadding,
                backgroundColor = backgroundColor,
                buttonColor = buttonColor,
                modifier = Modifier,
            )
        } else {
            VerticalChapterNavigator(
                state = state,
                onNextChapter = onNextChapter,
                enabledNext = enabledNext,
                onPreviousChapter = onPreviousChapter,
                enabledPrevious = enabledPrevious,
                // SY -->
                currentPageText = currentPageText,
                // SY <--
                totalPages = totalPages,
                interactionSource = interactionSource,
                mainAxisPadding = mainAxisPadding,
                backgroundColor = backgroundColor,
                buttonColor = buttonColor,
                modifier = Modifier,
            )
        }
        // SY --> Komiho: 拖动进度条时显示当前页缩略图气泡（仅 Komga 远程 + 开关开启）。
        if (thumbnailModelForPage != null && previewPage >= 0) {
            val model = thumbnailModelForPage.invoke(previewPage)
            if (model != null) {
                PagePreviewBubble(
                    model = model,
                    modifier = if (type.isHorizontal()) {
                        Modifier.align(Alignment.TopCenter)
                    } else {
                        Modifier.align(Alignment.CenterEnd)
                    },
                )
            }
        }
        // SY <--
    }
}

// SY --> Komiho: 进度气泡缩略图——自适应尺寸小图，拖动时跟随显示当前页。
@Composable
private fun PagePreviewBubble(model: Any?, modifier: Modifier = Modifier) {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val size = (screenWidth * 0.42f).coerceAtMost(340.dp).coerceAtLeast(120.dp)
    Box(
        modifier = modifier
            .padding(8.dp)
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.96f)),
        contentAlignment = Alignment.Center,
    ) {
        SubcomposeAsyncImage(
            model = model,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
            loading = {
                CircularProgressIndicator(strokeWidth = 2.dp)
            },
        )
    }
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
