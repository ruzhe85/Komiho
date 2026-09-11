package app.mihonsy.komga.ui

import android.content.Intent
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import app.mihonsy.komga.data.KomgaPreferences

/** 与 [KomgaMainActivity] 约定的「目标 tab」extra（值为 [MainTab] 的 ordinal）。 */
internal const val EXTRA_TAB = "tab"

/**
 * Komiho: 二级页面（系列详情 / 收藏 / 阅读列表 / section 全列表）的导航外壳。
 *
 * 这几页是独立 Activity，原本完全没有导航 —— 在平板（或用户显式把导航栏设在左/右）上
 * 从主界面进来后 rail 会消失，页面看起来「全屏」，也没有回到其它 tab 的入口。
 *
 * 这里复用主界面**同一份**「导航栏位置」偏好与**同一个** [KomgaNavRail]：
 * - 位置口径与主界面一致：AUTO = 最小宽度 ≥ 600dp 用左侧 rail，否则底部；
 * - 底部形态下不额外画导航（与改动前一致，页面自行铺满）；
 * - 点 rail 上的 tab → 用 CLEAR_TOP + SINGLE_TOP 回到 [KomgaMainActivity] 并切到该 tab
 *   （二级页面不是 tab 容器，切换语义只能是「回到主界面的那个 tab」）。
 *
 * 用法：Activity 层包一层，把拿到的 modifier 传给页面自己的 Scaffold——
 * ```
 * KomgaSecondaryNavHost(prefs) { modifier -> KomgaSeriesScreen(seriesId, modifier) }
 * ```
 */
@Composable
internal fun KomgaSecondaryNavHost(
    prefs: KomgaPreferences,
    content: @Composable (Modifier) -> Unit,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current

    val navBarPosition = prefs.navBarPosition.let {
        if (it == "AUTO") {
            if (configuration.smallestScreenWidthDp >= 600) "LEFT" else "BOTTOM"
        } else {
            it
        }
    }

    if (navBarPosition == "BOTTOM") {
        content(Modifier.fillMaxSize())
        return
    }

    // 二级页面全部属于 Komga 来源（系列 / 收藏 / 阅读列表 / section 均为 Komga 语义），
    // 因此 tab 集合固定按「非文件型来源」过滤，与主界面在 Komga 来源下的集合一致。
    val tabs = MainTab.entries.filter { it.visibleFor(isFileSource = false) }

    fun openTab(tab: MainTab) {
        context.startActivity(
            Intent(context, KomgaMainActivity::class.java)
                .putExtra(EXTRA_TAB, tab.ordinal)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
    }

    Row(modifier = Modifier.fillMaxSize()) {
        if (navBarPosition == "LEFT") {
            // selectedOrdinal = -1：二级页面不属于任何主 tab，故不高亮任何条目。
            KomgaNavRail(tabs = tabs, selectedOrdinal = -1, onTabClick = { openTab(it) })
        }
        content(Modifier.weight(1f).fillMaxHeight())
        if (navBarPosition == "RIGHT") {
            KomgaNavRail(tabs = tabs, selectedOrdinal = -1, onTabClick = { openTab(it) })
        }
    }
}
