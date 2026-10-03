package tachiyomi.presentation.core.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.tooling.preview.Preview

/**
 * A combined [CircularProgressIndicator] that always rotates.
 *
 * By always rotating we give the feedback to the user that the application isn't 'stuck'.
 *
 * Komiho: [reducedMotion] 为 true（E-Ink 模式关动画）时不跑**无限循环**动画 ——
 * `rememberInfiniteTransition` 读的是帧时钟而非 `MotionDurationScale`，动画总闸盖不住，
 * 只会让墨水屏持续刷新留残影。此时改画一个固定进度的静态环，保留「在加载」的语义。
 */
@Composable
fun CombinedCircularProgressIndicator(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    reducedMotion: Boolean = false,
) {
    AnimatedContent(
        targetState = progress() == 0f,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "progressState",
        modifier = modifier,
    ) { indeterminate ->
        if (indeterminate) {
            // Indeterminate
            if (reducedMotion) {
                CircularProgressIndicator(progress = { STATIC_INDETERMINATE_PROGRESS })
            } else {
                CircularProgressIndicator()
            }
        } else {
            // Determinate
            val rotation = if (reducedMotion) {
                0f
            } else {
                val infiniteTransition = rememberInfiniteTransition(label = "infiniteRotation")
                val value by infiniteTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(2000, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart,
                    ),
                    label = "rotation",
                )
                value
            }
            val animatedProgress by animateFloatAsState(
                targetValue = progress(),
                animationSpec = ProgressIndicatorDefaults.ProgressAnimationSpec,
                label = "progress",
            )
            CircularProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier.rotate(rotation),
            )
        }
    }
}

/**
 * 静态「加载中」环的固定进度（约 1/4 圈）。纯展示用，不代表真实进度，
 * 只是为了在 E-Ink 无动画模式下仍有「非空环」表示忙碌。
 */
private const val STATIC_INDETERMINATE_PROGRESS = 0.25f

@Preview
@Composable
private fun CombinedCircularProgressIndicatorPreview() {
    var progress by remember { mutableFloatStateOf(0f) }
    MaterialTheme {
        Scaffold(
            bottomBar = {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        progress = when (progress) {
                            0f -> 0.15f
                            0.15f -> 0.25f
                            0.25f -> 0.5f
                            0.5f -> 0.75f
                            0.75f -> 0.95f
                            else -> 0f
                        }
                    },
                ) {
                    Text("change")
                }
            },
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(it),
            ) {
                CombinedCircularProgressIndicator(progress = { progress })
            }
        }
    }
}
