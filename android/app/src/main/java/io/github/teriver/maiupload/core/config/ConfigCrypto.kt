package io.github.teriver.maiupload.core.config

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 配置加密工具：基于 Android Keystore 的 AES-GCM 加密。
 *
 * 主密钥存储在 Android Keystore 中（应用卸载即销毁），敏感字段加密后写入 config.json。
 * 加密格式：base64(iv) + ":" + base64(ciphertext)，iv 为 12 字节 GCM nonce。
 *
 * 安全性：
 * - 密钥不经应用进程，硬件隔离（设备支持 TEE/HSM 时）；
 * - GCM 模式提供机密性 + 完整性，防篡改；
 * - 无需用户密码或生物认证（应用内配置，无登录态）。
 */
object ConfigCrypto {

    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "maiupload_config_master_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val IV_LENGTH_BYTES = 12

    private fun getOrCreateMasterKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        keyStore.getKey(KEY_ALIAS, null)?.let { return it as SecretKey }

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            KEYSTORE_PROVIDER
        )
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    /**
     * 加密明文：返回 "base64(iv):base64(ciphertext)"，失败返回空串。
     */
    fun encrypt(plaintext: String): String {
        if (plaintext.isEmpty()) return ""
        return try {
            val key = getOrCreateMasterKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            val ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP)
            val ctB64 = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
            "$ivB64:$ctB64"
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * 解密 [encrypt] 产出的密文，返回明文；格式不符或解密失败返回空串。
     */
    fun decrypt(ciphertext: String): String {
        if (ciphertext.isEmpty()) return ""
        val parts = ciphertext.split(":")
        if (parts.size != 2) return ""
        return try {
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            if (iv.size != IV_LENGTH_BYTES) return ""
            val ct = Base64.decode(parts[1], Base64.NO_WRAP)
            val key = getOrCreateMasterKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            cipher.init(Cipher.DECRYPT_MODE, key, spec)
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }
}
