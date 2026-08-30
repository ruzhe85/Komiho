package tachiyomi.domain.storage.service

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.storage.FolderProvider

class StoragePreferences(
    folderProvider: FolderProvider,
    preferenceStore: PreferenceStore,
) {

    val baseStorageDirectory: Preference<String> = preferenceStore.getString(
        Preference.appStateKey("storage_dir"),
        folderProvider.path(),
    )

    /**
     * Komiho: 本地源的**漫画根目录**（SAF tree URI）。
     *
     * 用户选哪个文件夹，哪个就是漫画根目录，不再强制 Mihon 的 `<base>/local`
     * 子目录——否则为了看漫画还得先把目录搬进 local/，不符合实际使用。
     *
     * 与 [baseStorageDirectory] **分开存**是刻意的：StorageManager 会在自己的
     * 根目录下创建 autobackup/、downloads/、logs/ 等目录，若把用户的漫画目录
     * 直接设成它的根，就会往漫画目录里塞这些杂物。留空则回退到
     * [StorageManager.getLocalSourceDirectory]（Mihon 原行为）。
     */
    val localSourceRoot: Preference<String> = preferenceStore.getString(
        Preference.appStateKey("local_source_root"),
        "",
    )

    /**
     * Komiho: 本地文件浏览器的显示模式，存 [app.mihonsy.komga.ui.LibraryDisplayMode]
     * 的 `prefValue`（LIST / COMPACT_GRID / COMFORTABLE_GRID）。默认列表。
     */
    val localBrowseDisplayMode: Preference<String> = preferenceStore.getString(
        Preference.appStateKey("local_browse_display_mode"),
        "LIST",
    )

    /** Komiho: 本地文件浏览器排序（`<字段>,<asc|desc>`），字段见 [LocalFileSortBy]。默认按名称升序。 */
    val localBrowseSort: Preference<String> = preferenceStore.getString(
        Preference.appStateKey("local_browse_sort"),
        "name,asc",
    )

    /** Komiho: 本地文件浏览器网格模式是否尝试显示封面缩略图（默认关，避免 SAF 里读归档拖慢）。 */
    val localBrowseShowCover: Preference<Boolean> = preferenceStore.getBoolean(
        Preference.appStateKey("local_browse_show_cover"),
        false,
    )
}
