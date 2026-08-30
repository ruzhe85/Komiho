package tachiyomi.source.local.io

import android.content.Context
import android.net.Uri
import com.hippo.unifile.UniFile
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.domain.storage.service.StoragePreferences

actual class LocalSourceFileSystem(
    private val context: Context,
    private val storageManager: StorageManager,
    private val storagePreferences: StoragePreferences,
) {

    // SY --> Komiho: 用户所选文件夹即漫画根目录，不再强制 <base>/local 子目录。
    actual fun getBaseDirectory(): UniFile? {
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
