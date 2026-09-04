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

    /** Komiho: 本地文件浏览器网格模式每行列数（0 = 自动 Adaptive 列密度）。 */
    val localBrowseColumns: Preference<Int> = preferenceStore.getInt(
        Preference.appStateKey("local_browse_columns"),
        0,
    )

    /**
     * Komiho: 本地浏览的**存储卷根**（真实路径，如 `/storage/1234-5678`）。
     * 仅当持有 MANAGE_EXTERNAL_STORAGE 时生效，空串 = 内部存储根。
     * 注意：这里只能是「卷根」（内部存储 / SD 卡），不能是任意子目录——否则
     * 面包屑之上无路可走，等于把全盘权限关进笼子（原「漫画根」的教训）。
     * 存的路径不存在（SD 卡拔出）时由 [tachiyomi.source.local.io.LocalSourceFileSystem]
     * 回落到内部存储根。
     */
    val localBrowseRootPath: Preference<String> = preferenceStore.getString(
        Preference.appStateKey("local_browse_root_path"),
        "",
    )

    /**
     * Komiho: 全权限模式下「记住上次访问路径」——最后一次浏览所在的**完整目录路径**
     * （canonical，如 `/storage/emulated/0/漫画/某漫画`）。空串 = 尚未访问过（首次启用，
     * 浏览落在根目录）。仅全权限模式使用：进浏览 tab 时恢复到该目录（含跨卷自动切换）；
     * 每次下钻/面包屑跳层都会刷新此值。SAF 模式不写此值（两种模式的路径语义不同）。
     */
    val localBrowseLastPath: Preference<String> = preferenceStore.getString(
        Preference.appStateKey("local_browse_last_path"),
        "",
    )

    // SY --> Komiho Phase3/4: WebDAV 配置。
    // Phase4 起连接与凭据由 app.mihonsy.komga.data.webdav.WebDavConnectionStore 管理
    // （webdav_connections_v1，密码经 Keystore 加密）；以下三项仅作 Phase3 遗留迁移源
    // （首次进连接页自动转为「默认连接」后清空 user/pass），webdavTestUrl 另存「上次打开路径」。
    /** WebDAV 测试 CBZ/ZIP 的完整 http(s) URL（不含 `webdav:` 前缀）。迁移后复用为上次打开路径。 */
    val webdavTestUrl: Preference<String> = preferenceStore.getString(
        Preference.appStateKey("webdav_test_url"),
        "",
    )

    /** WebDAV 测试用户名（可空 = 匿名）。仅 Phase3 遗留迁移用，迁移后清空。 */
    val webdavTestUser: Preference<String> = preferenceStore.getString(
        Preference.appStateKey("webdav_test_user"),
        "",
    )

    /** WebDAV 测试密码（可空 = 匿名）。Phase4 起新写入为 enc1: 加密形态；迁移后清空。 */
    val webdavTestPass: Preference<String> = preferenceStore.getString(
        Preference.appStateKey("webdav_test_pass"),
        "",
    )

    /**
     * WebDAV 整本缓存（rar/7z 强制回退等）磁盘上限，字节。默认 1GB，可调 200MB~4GB
     * （设置-存储滑条，200MB 一档）。超限按 lastModified LRU 淘汰（WebDavRandomAccessSource）。
     */
    val webdavCacheMaxBytes: Preference<Long> = preferenceStore.getLong(
        Preference.appStateKey("webdav_cache_max_bytes"),
        1024L * 1024 * 1024,
    )

    // SY --> Komiho Phase4: WebDAV 浏览器偏好（独立于本地浏览的 localBrowse*，互不影响）。
    /** Komiho: WebDAV 浏览器显示模式（LIST / COMPACT_GRID / COMFORTABLE_GRID）。默认列表。 */
    val webdavBrowseDisplayMode: Preference<String> = preferenceStore.getString(
        Preference.appStateKey("webdav_browse_display_mode"),
        "LIST",
    )

    /** Komiho: WebDAV 浏览器排序（`<字段>,<asc|desc>`），字段同本地浏览 [LocalFileSortBy]。默认名称升序。 */
    val webdavBrowseSort: Preference<String> = preferenceStore.getString(
        Preference.appStateKey("webdav_browse_sort"),
        "name,asc",
    )

    /** Komiho: WebDAV 浏览器网格每行列数（0 = 自动 Adaptive 列密度）。 */
    val webdavBrowseColumns: Preference<Int> = preferenceStore.getInt(
        Preference.appStateKey("webdav_browse_columns"),
        0,
    )
    // SY <--

    /** 全局首页当前选中的来源 id（"komga" / "local" / "webdav:<connId>"）。空 = 默认 Komga。 */
    val browseSourceId: Preference<String> = preferenceStore.getString(
        Preference.appStateKey("browse_source_id"),
        "",
    )
    // SY <--
}
