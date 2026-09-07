package app.mihonsy.komga.data

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Komiho: 聚合页（来源仪表盘）的来源显示开关。
 *
 * 只记录「被隐藏」的来源 id——新增来源与存量来源天然可见，升级后不影响老用户，
 * 也无需为每个来源写默认值。
 *
 * 开关**只影响聚合页卡片是否展示**，不影响顶栏来源菜单：隐藏的来源仍可在菜单里
 * 切换过去（否则一旦关掉就彻底无法访问，只能回管理页再开）。
 *
 * id 与 KomgaMainActivity 的 SOURCE_ID_* 同源（该处常量引用本对象的常量），
 * 避免两处硬编码漂移导致开关失效。
 */
object SourceVisibilityStore {

    const val ID_LOCAL = "local"
    const val ID_KOMGA = "komga"
    const val ID_WEBDAV_PREFIX = "webdav:"
    const val ID_SMB_PREFIX = "smb:"
    /**
     * Komga 连接级开关 id 前缀。聚合页 Komga 只有一张卡，但管理页按连接列出，
     * 故按连接记录：任一条连接可见 → 聚合页 Komga 卡片显示（全部关掉才隐藏）。
     */
    const val ID_KOMGA_CONN_PREFIX = "komga:"

    private val prefs: PreferenceStore by lazy { Injekt.get() }

    private val hidden: Preference<Set<String>> by lazy {
        prefs.getStringSet(Preference.appStateKey("source_hidden_ids_v1"), emptySet())
    }

    fun hiddenIds(): Set<String> = hidden.get()

    /** 默认可见：未被记录为隐藏即显示。 */
    fun isVisible(sourceId: String): Boolean = sourceId !in hidden.get()

    fun setVisible(sourceId: String, visible: Boolean) {
        val cur = hidden.get().toMutableSet()
        if (visible) cur.remove(sourceId) else cur.add(sourceId)
        hidden.set(cur)
    }
}
