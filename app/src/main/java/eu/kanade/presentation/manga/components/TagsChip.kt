package eu.kanade.presentation.manga.components

import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.BorderStroke
import eu.kanade.presentation.components.ChipBorder
import eu.kanade.presentation.components.SuggestionChip
import eu.kanade.presentation.components.SuggestionChipDefaults
import androidx.compose.material3.SuggestionChipDefaults as SuggestionChipDefaultsM3

// Komiho (2026-10-02): 从 NamespaceTags.kt 迁出——TagsChip 为 Mihon 漫画详情在用，
// 其余 exh 元数据标签组件随阶段 3 删除。
@Composable
fun TagsChip(
    text: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    border: ChipBorder? = SuggestionChipDefaults.suggestionChipBorder(),
    borderM3: BorderStroke? = SuggestionChipDefaultsM3.suggestionChipBorder(enabled = true),
) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        if (onClick != null) {
            SuggestionChip(
                modifier = modifier,
                onClick = onClick,
                label = {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                border = borderM3,
            )
        } else {
            SuggestionChip(
                modifier = modifier,
                label = {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                border = border,
            )
        }
    }
}
