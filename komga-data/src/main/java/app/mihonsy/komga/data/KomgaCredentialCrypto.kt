package app.mihonsy.komga.data

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

// SY --> Komiho：Komga 连接凭据静态加密（Android Keystore AES-CBC-PKCS7）。
//
// 与 WebDavCredentialCrypto 同模式、独立密钥别名（komgaCred），避免与 WebDAV 凭据混用
// 同一把设备密钥。Komga 连接里的 apiKey / username / password 此前以明文落盘
// （komga_connection SharedPreferences 的 connections JSON），本对象在持久化前加密、
// 读取后解密，关掉「设备内明文凭据」这一缺口。
//
// 兼容策略（与 D2.A 双格式兼容一致）：
// - [decryptStored] 遇到无 "enc1:" 前缀的历史明文 → 原样返回（下次保存自动加密）；
// - Keystore 密钥丢失（清除数据 / 换机恢复后）→ 解密失败返回空串并告警，不崩溃
//   （凭据不可用表现为 401，用户在端内重新录入即可）。
object KomgaCredentialCrypto {

    /** 密文标记前缀：带此前缀的值才走解密，否则按历史明文透传。 */
    private const val MARKER = "enc1:"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "komgaCred"
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
            logcat(LogPriority.WARN, e) { "[Komga] 凭据解密失败（Keystore 密钥丢失？），按空凭据处理" }
            ""
        }
    }
}
// SY <--
