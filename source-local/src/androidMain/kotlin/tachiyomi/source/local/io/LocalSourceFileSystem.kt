package tachiyomi.source.local.io

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
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
    // SY --> Komiho: 真实路径模式——已授权 MANAGE 时优先走真实路径，规则与 Komga
    // 本地浏览的 computeLocalDir() 完全同源（浏览根、openLocalFile、reader 解析、
    // 历史/书签续读共用本函数作唯一锚点），否则浏览根与打开锚点基准不一致，
    // relPath 错位导致全部「找不到文件」。已授权未设根时用内部存储根（供设漫画根）。
    actual fun getBaseDirectory(): UniFile? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            Environment.isExternalStorageManager()
        ) {
            val real = storagePreferences.localBrowseRealPath.get()
            if (real.isNotBlank() && File(real).exists()) {
                return UniFile.fromFile(File(real))
            }
            if (real.isBlank()) {
                // SAF→全权限自动迁移：旧 SAF 授权目录若是主存储（primary 卷 tree URI），
                // 推导为真实路径直接沿用，免去「转全权限后必须重选目录」。
                // 非主存储卷或解析失败时回落内部存储根。
                val migrated = runCatching {
                    val tree = storagePreferences.localSourceRoot.get()
                    if (tree.isNotBlank()) {
                        val docId = android.provider.DocumentsContract.getTreeDocumentId(
                            Uri.parse(tree),
                        )
                        if (docId.startsWith("primary:")) {
                            File(Environment.getExternalStorageDirectory(), docId.removePrefix("primary:"))
                        } else {
                            null
                        }
                    } else {
                        null
                    }
                }.getOrNull()
                if (migrated != null && migrated.exists()) {
                    return UniFile.fromFile(migrated)
                }
                return UniFile.fromFile(Environment.getExternalStorageDirectory())
            }
        }
        val root = storagePreferences.localSourceRoot.get()
        if (root.isNotBlank()) {
            // 用 android.net.Uri 解析，避免为了 toUri() 引入 androidx.core 依赖。
            return UniFile.fromUri(context, Uri.parse(root))?.takeIf { it.exists() }
        }
        return storageManager.getLocalSourceDirectory()
    }
    // SY <--

    actual fun getFilesInBaseDirectory(): List<UniFile> {
        return getBaseDirectory()?.listFiles().orEmpty().toList()
    }

    actual fun getMangaDirectory(name: String): UniFile? {
        return getBaseDirectory()
            ?.findFile(name)
            ?.takeIf { it.isDirectory }
    }

    actual fun getFilesInMangaDirectory(name: String): List<UniFile> {
        return getBaseDirectory()
            ?.findFile(name)
            ?.takeIf { it.isDirectory }
            ?.listFiles().orEmpty().toList()
    }
}
