package eu.kanade.tachiyomi.ui.reader.viewer

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import androidx.annotation.IntRange
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material3.Text
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.isVisible
import com.google.android.material.progressindicator.CircularProgressIndicator
import eu.kanade.presentation.theme.TachiyomiTheme
import tachiyomi.presentation.core.components.CombinedCircularProgressIndicator

/**
 * A wrapper for [CircularProgressIndicator] that always rotates.
 *
 * By always rotating we give the feedback to the user that the application isn't 'stuck'.
 */
class ReaderProgressIndicator @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AbstractComposeView(context, attrs, defStyleAttr) {

    init {
        layoutParams = FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER)
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
    }

    private var progress by mutableFloatStateOf(0f)
    private var label by mutableStateOf<String?>(null)

    @Composable
    override fun Content() {
        TachiyomiTheme {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CombinedCircularProgressIndicator(progress = { progress })
                label?.let {
                    Spacer(Modifier.height(12.dp))
                    // 文字颜色随主题（TachiyomiTheme 提供的 content color），不硬编码。
                    Text(
                        text = it,
                        fontSize = 13.sp,
                    )
                }
            }
        }
    }

    fun show() {
        isVisible = true
    }

    fun hide() {
        isVisible = false
    }

    fun setProgress(@IntRange(from = 0, to = 100) progress: Int) {
        this.progress = progress / 100f
    }

    /** 进度环下方文案（如「正在缓存」）；传 null 隐藏。 */
    fun setLabel(label: String?) {
        this.label = label
    }
}
