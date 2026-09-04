package app.mihonsy.komga.data.webdav

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec

// SY --> Komiho Phase4：WebDAV 凭据加密存储（Android Keystore AES-CBC-PKCS7）。
//
// Phase3 的测试密码明文落在 DataStore（webdav_test_pass），Phase4 起所有 WebDAV 凭据
// 经本对象加解密后落盘。实现与 CbzCrypto 同模式：Keystore 按别名持钥、IV 前置、Base64；
// 密文统一带 "enc1:" 前缀以便识别。
//
// 兼容策略（D2.A 双格式兼容的凭据版）：
// - [decryptStored] 遇到无 "enc1:" 前缀的历史明文 → 原样返回（调用方下次保存时会加密）；
// - Keystore 密钥丢失（清除数据/备份恢复后）→ 解密失败返回空串并告警，不崩溃。
object WebDavCredentialCrypto {

    /** 密文标记前缀：带此前缀的值才走解密，否则按历史明文透传。 */
    private const val MARKER = "enc1:"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "webdavPw"
    private const val IV_SIZE = 16

    private val keyStore by lazy {
        KeyStore.getInstance(KEYSTORE).apply { load(null) }
    }

    private fun getKey(): SecretKey {
        val entry = keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry
        return entry?.secretKey ?: generateKey()
    }

    private fun generateKey(): SecretKey {
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES).apply {
            init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_CBC)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_PKCS7)
                    .setRandomizedEncryptionRequired(true)
                    .setUserAuthenticationRequired(false)
                    .build(),
            )
        }.generateKey()
    }

    /** 明文 → "enc1:" + Base64(IV + 密文)。 */
    fun encrypt(plain: String): String {
        if (plain.isBlank()) return ""
        val cipher = Cipher.getInstance(
            "${KeyProperties.KEY_ALGORITHM_AES}/${KeyProperties.BLOCK_MODE_CBC}/${KeyProperties.ENCRYPTION_PADDING_PKCS7}",
        ).apply { init(Cipher.ENCRYPT_MODE, getKey()) }
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val out = ByteArray(cipher.iv.size + encrypted.size)
        cipher.iv.copyInto(out)
        encrypted.copyInto(out, cipher.iv.size)
        return MARKER + Base64.encodeToString(out, Base64.NO_WRAP)
    }

    /**
     * 落盘值 → 明文。空串原样；无标记（历史明文）原样透传（下次保存自动加密）；
     * 解密失败（Keystore 密钥丢失）返回空串并告警——凭据不可用表现为 401，可重新录入。
     */
    fun decryptStored(stored: String): String {
        if (stored.isBlank()) return ""
        if (!stored.startsWith(MARKER)) return stored
        return try {
            val data = Base64.decode(stored.removePrefix(MARKER), Base64.NO_WRAP)
            val spec = IvParameterSpec(data, 0, IV_SIZE)
            val cipher = Cipher.getInstance(
                "${KeyProperties.KEY_ALGORITHM_AES}/${KeyProperties.BLOCK_MODE_CBC}/${KeyProperties.ENCRYPTION_PADDING_PKCS7}",
            ).apply {
                init(Cipher.DECRYPT_MODE, getKey(), spec)
            }
            String(cipher.doFinal(data, IV_SIZE, data.size - IV_SIZE), Charsets.UTF_8)
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "[WebDav] 凭据解密失败（Keystore 密钥丢失？），按空凭据处理" }
            ""
        }
    }
}
// SY <--
