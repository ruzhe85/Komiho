package eu.kanade.presentation.reader.appbars

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AppBar
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun ReaderTopBar(
    mangaTitle: String?,
    chapterTitle: String?,
    navigateUp: () -> Unit,
    // SY: 顶栏只保留书签按钮。WebView / 浏览器 / 分享三项对 Komga 内部 scheme 源与
    // 本地源都没有真实网页地址，继续隐藏（AppBarActions 溢出菜单整体不启用）。
    bookmarked: Boolean,
    onToggleBookmarked: () -> Unit,
    // SY --> Komiho: 长按书签按钮弹出本书签列表（跳转 / 删除）。
    onOpenBookmarks: () -> Unit,
    // SY <--
    modifier: Modifier = Modifier,
) {
    AppBar(
        modifier = modifier,
        backgroundColor = Color.Transparent,
        title = mangaTitle,
        subtitle = chapterTitle,
        navigateUp = navigateUp,
        actions = {
            // SY --> Komiho: 单击=当前页加/取消书签；长按=打开书签列表。
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .combinedClickable(
                        onClick = onToggleBookmarked,
                        onLongClick = onOpenBookmarks,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (bookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkAdd,
                    contentDescription = stringResource(
                        if (bookmarked) MR.strings.action_remove_bookmark else MR.strings.action_bookmark,
                    ),
                    // 已加书签时用主题色高亮，避免和未加状态时视觉上分不开
                    tint = if (bookmarked) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                )
            }
            // SY <--
        },
    )
}
