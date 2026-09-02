package tachiyomi.source.local.io

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import com.hippo.unifile.UniFile
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.domain.storage.service.StoragePreferences

actual class LocalSourceFileSystem(
    private val context: Context,
    private val storageManager: StorageManager,
    private val storagePreferences: StoragePreferences,
) {

    // SY --> Komiho: 用户所选文件夹即漫画根目录，不再强制 <base>/local 子目录。
    // SY --> Komiho: 真实路径模式——本函数是浏览根、openLocalFile、reader 解析、历史/书签
    // 续读的唯一锚点，computeLocalDir() 纯委托至此，两者天然同源；若各自算一套根，
    // relPath 会错位导致全部「找不到文件」。
    // 已授权 MANAGE 时浏览根恒为「内部存储根」：全权限下把根钉在某个子目录（原「漫画根」）
    // 会让面包屑只剩一个叶子名、且之上无路可走，等于把全盘权限关进笼子。现整盘可达，
    // 不记忆任何起始目录，每次进入浏览都从内部存储根开始。
    actual fun getBaseDirectory(): UniFile? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            Environment.isExternalStorageManager()
        ) {
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
