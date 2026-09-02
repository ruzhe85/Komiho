package tachiyomi.source.local.io

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import com.hippo.unifile.UniFile
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.domain.storage.service.StoragePreferences
import java.io.File

actual class LocalSourceFileSystem(
    private val context: Context,
    private val storageManager: StorageManager,
    private val storagePreferences: StoragePreferences,
) {

    // SY --> Komiho: 用户所选文件夹即漫画根目录，不再强制 <base>/local 子目录。
    // SY --> Komiho: 真实路径模式——本函数是浏览根、openLocalFile、reader 解析、历史/书签
    // 续读的唯一锚点，computeLocalDir() 纯委托至此，两者天然同源；若各自算一套根，
    // relPath 会错位导致全部「找不到文件」。
    // 已授权 MANAGE 时浏览根为**存储卷根**（默认内部存储，可在浏览页切换到 SD 卡）：
    // 全权限下把根钉在任意子目录（原「漫画根」）会让面包屑只剩一个叶子名、且之上无路可走，
    // 等于把全盘权限关进笼子。卷根之间没有包含关系，切换只是换一个顶层，不会锁死路径。
    actual fun getBaseDirectory(): UniFile? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            Environment.isExternalStorageManager()
        ) {
            val custom = storagePreferences.localBrowseRootPath.get()
            if (custom.isNotBlank() && File(custom).isDirectory) {
                return UniFile.fromFile(File(custom))
            }
            return UniFile.fromFile(Environment.getExternalStorageDirectory())
        }
        val root = storagePreferences.localSourceRoot.get()
        if (root.isNotBlank()) {
            // 用 android.net.Uri 解析，避免为了 toUri() 引入 androidx.core 依赖。
            return UniFile.fromUri(context, Uri.parse(root))?.takeIf { it.exists() }
        }
        return storageManager.getLocalSourceDirectory()
    }
    // SY <--

    /**
     * 可用存储卷根（内部存储在前，其后是 SD 卡）。三种来源合并去重：
     * ①内部存储；②`getExternalFilesDirs` 反推的卷根（官方途径，含 SD 卡）；
     * ③兜底列 `/storage`（部分 OEM 的 SD 卡只挂在这里）。
     * 全部 runCatching：某个来源不可读时不影响其余，最多是那张卡不出现。
     */
    actual fun getStorageRoots(): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
            !Environment.isExternalStorageManager()
        ) {
            return emptyList()
        }
        val roots = LinkedHashMap<String, String>()
        fun add(dir: File) {
            if (!dir.isDirectory) return
            val canonical = runCatching { dir.canonicalPath }.getOrNull() ?: return
            // 不用 putIfAbsent：那是 Java 8 的 Map 默认方法，minSdk < 24 且无脱糖时会崩。
            if (!roots.containsKey(canonical)) roots[canonical] = canonical
        }
        runCatching { add(Environment.getExternalStorageDirectory()) }
        runCatching {
            context.getExternalFilesDirs(null).forEach { dir ->
                if (dir == null) return@forEach
                val path = dir.absolutePath
                val idx = path.indexOf("/Android/data/")
                if (idx > 0) add(File(path.substring(0, idx)))
            }
        }
        runCatching {
            File("/storage").listFiles()?.forEach { dir ->
                // emulated 内部已由 ① 覆盖，self 是指向 /storage/emulated/0 的软链。
                if (dir.isDirectory && dir.name != "emulated" && dir.name != "self") add(dir)
            }
        }
        return roots.values.toList()
    }

    actual fun getFilesInBaseDirectory(): List<UniFile> {
        return getBaseDirectory()?.listFiles().orEmpty().toList()
    }

    actual fun getMangaDirectory(name: String): UniFile? = resolveUnderBase(name)

    actual fun getFilesInMangaDirectory(name: String): List<UniFile> =
        resolveUnderBase(name)?.listFiles().orEmpty().toList()

    // SY --> Komiho: 真实路径模式——manga.url / chapter.url 统一为「真实绝对路径」，
    // 使 SAF 与全权限（MANAGE）两种模式对同一物理文件得到相同标识，书签/历史即可互认。
    // 下面三个函数负责在「真实绝对路径」与「当前浏览根下的 UniFile」之间互转。
    actual fun realPathOf(uni: UniFile): String? {
        val uri = uni.uri ?: return null
        return when (uri.scheme) {
            "file" -> runCatching { File(uri.path ?: return null).canonicalPath }.getOrNull()
            "content" -> runCatching {
                val docId = try {
                    DocumentsContract.getDocumentId(uri)
                } catch (_: Exception) {
                    DocumentsContract.getTreeDocumentId(uri)
                }
                safDocIdToRealPath(docId)
            }.getOrNull()
            else -> null
        }
    }

    private fun baseRealPath(): String? = getBaseDirectory()?.let { realPathOf(it) }

    actual fun resolveUnderBase(canonicalUrl: String): UniFile? {
        val baseRp = baseRealPath() ?: return null
        if (!canonicalUrl.startsWith(baseRp)) return null
        // 必须是「整段」前缀：避免 `/漫画社` 被误判为 `/漫画` 之下（否则下钻会拿到错误文件）。
        if (canonicalUrl.length > baseRp.length && canonicalUrl[baseRp.length] != '/') return null
        val rel = canonicalUrl.removePrefix(baseRp).trim('/')
        var cur = getBaseDirectory() ?: return null
        if (rel.isEmpty()) return cur
        for (seg in rel.split('/')) {
            if (seg.isBlank()) continue
            cur = cur.findFile(seg) ?: return null
        }
        return cur
    }

    actual fun relativeFromBase(canonicalUrl: String): String? {
        val baseRp = baseRealPath() ?: return null
        if (!canonicalUrl.startsWith(baseRp)) return null
        if (canonicalUrl.length > baseRp.length && canonicalUrl[baseRp.length] != '/') return null
        return canonicalUrl.removePrefix(baseRp).trim('/')
    }

    /** 把 SAF doc id（如 `primary:漫画/某漫画/vol1.cbz` 或树根 `primary:漫画`）解码为真实路径。 */
    private fun safDocIdToRealPath(docId: String): String {
        val decoded = Uri.decode(docId)
        val colon = decoded.indexOf(':')
        if (colon < 0) return File(File.separator, decoded).canonicalPath
        val volume = decoded.substring(0, colon)
        val rel = decoded.substring(colon + 1).trimStart('/')
        val root = when (volume) {
            "primary", "home" -> Environment.getExternalStorageDirectory()
            else -> File("/storage", volume)
        }
        return File(root, rel).canonicalPath
    }
    // SY <--
}
