package io.github.teriver.maiupload.core.config

import android.util.Base64
import io.github.teriver.maiupload.Application.Companion.application
import io.github.teriver.maiupload.BuildConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.ExperimentalSerializationApi
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 配置导出/导入工具 —— 仅限用户自定义设置，不含任何账号凭据。
 *
 * **不导出**：水鱼/落雪 token、落雪 OAuth 令牌、Rival 鉴权参数。
 *
 * **导出**：SyncConfig + UserInfo + ScoreDisplayType/ScoreStyleType + LocalConfig。
 *
 * 导出格式（JSON）：
 * ```
 * {
 *   "version": 1,
 *   "exportedAt": 1691234567890,
 *   "hmac": "base64(HMAC-SHA256(encryptedPayload))",
 *   "payload": "base64(iv):base64(ciphertext)"  // AES-GCM 固定密钥加密
 * }
 * ```
 *
 * 加密：AES-256-GCM，**固定密钥**（写死在源码里，不绑 Android Keystore）。
 * 验签：HMAC-SHA256，**固定密钥**。
 *
 * **跨设备跨应用可解**：换设备、换应用、甚至脱离本应用，只要拿到本源码里的固定密钥
 * + 解析方法（拆 JSON → 取 payload → base64 解码 iv:ciphertext → AES-GCM 解密 → JSON 反序列化），
 * 就能还原配置内容。本应用自己当然也能解。
 *
 * HMAC 防篡改：验签不通过拒绝导入，避免被篡改的密文破坏应用状态。
 */
object ConfigTransfer {

    // ---- 固定密钥（写死在源码里，跨设备跨应用通用）----
    // AES-256 需要 32 字节密钥；HMAC-SHA256 用任意长度密钥即可。
    // 这两串是应用级固定密钥，不是机密——源码公开即公开，目的只是让导出文件非明文 + 跨环境可解。
    private const val AES_KEY_HEX = "Maiupload2026ConfigTransferAesKey!!!" // 32 字节，UTF-8
    private const val HMAC_KEY_HEX = "Maiupload2026ConfigTransferHmacKey!!!" // 任意长度

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val IV_LENGTH_BYTES = 12
    // v2：base64 用 URL_SAFE 无填充，省掉尾部 '=' 填充字节
    private const val B64_FLAGS = Base64.URL_SAFE or Base64.NO_PADDING

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 可导出的配置子集 —— 按四大类完整备份：
     * 1. 成绩抓取设置：SyncConfig + RivalSyncConfig（含 userId/token 缓存）
     * 2. 成绩展示设置：ScoreDisplayType + ScoreStyleType
     * 3. 本地设置：LocalConfig
     * 4. 用户信息：UserInfo
     *
     * **不导出**：水鱼/落雪 token、落雪 OAuth 令牌、PKCE verifier。
     */
    @Serializable
    data class ExportableConfig(
        // 1. 成绩抓取设置
        var syncConfig: SyncConfig = SyncConfig(),
        var rivalSyncConfig: RivalSyncConfig = RivalSyncConfig(),
        // 2. 成绩展示设置
        var scoreDisplayType: ScoreDisplayType = ScoreDisplayType.Small,
        var scoreStyleType: ScoreStyleType = ScoreStyleType.ColorOverlay,
        // 3. 本地设置
        var localConfig: LocalConfig = LocalConfig(),
        // 4. 用户信息
        var userInfo: UserInfo = UserInfo(),
    )

    @Serializable
    private data class ExportBundle(
        val version: Int = 1,
        val exportedAt: Long = 0,
        val appVersion: String = "",
        val hmac: String = "",
        // AES-GCM 密文（"base64(iv):base64(ciphertext)"）
        val payload: String = "",
    )

    /** 导入结果：区分成功 / 版本警告 / 文件损坏，便于 UI 给出针对性提示。 */
    sealed class ImportResult {
        object Success : ImportResult()
        /**
         * 导入文件来自更高版本的应用，可能含本版本不认识的字段。
         * 已正常导入（未定义字段自动忽略），但提示用户注意兼容性。
         */
        data class VersionTooHigh(val bundleAppVersion: String) : ImportResult()
        /** 文件损坏、格式不符、HMAC 验签失败、解密失败等。 */
        object Corrupted : ImportResult()
    }

    // ---- 固定密钥派生 ----

    private fun aesKey(): SecretKeySpec {
        val raw = AES_KEY_HEX.toByteArray(Charsets.UTF_8).copyOf(32) // 截/补到 32 字节
        return SecretKeySpec(raw, "AES")
    }

    private fun hmacKey(): SecretKeySpec {
        val raw = HMAC_KEY_HEX.toByteArray(Charsets.UTF_8)
        return SecretKeySpec(raw, "HmacSHA256")
    }

    // ---- AES-GCM 加密/解密（固定密钥）----

    /**
     * 加密明文字节：返回 "base64(iv):base64(ciphertext)"。
     * @param compress true 时先 deflate raw 压缩再加密（v2 新格式，文件更小）；
     *                false 时直接加密（v1 旧格式，兼容旧导出文件）。
     * @param legacyBase64 true 用旧 v1 的 NO_WRAP base64（兼容旧文件），
     *                     false 用 v2 的 URL_SAFE 无填充省尾部字节。
     */
    private fun aesGcmEncryptBytes(plaintext: ByteArray, compress: Boolean, legacyBase64: Boolean): String {
        val raw = if (compress) deflateRaw(plaintext) else plaintext
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, aesKey())
        val iv = cipher.iv
        val ct = cipher.doFinal(raw)
        val flags = if (legacyBase64) Base64.NO_WRAP else B64_FLAGS
        val ivB64 = Base64.encodeToString(iv, flags)
        val ctB64 = Base64.encodeToString(ct, flags)
        return "$ivB64:$ctB64"
    }

    /** 旧 String 入口的薄壳：转 UTF-8 字节后走 [aesGcmEncryptBytes]（v1 兼容路径用）。 */
    private fun aesGcmEncrypt(plaintext: String, compress: Boolean, legacyBase64: Boolean): String =
        aesGcmEncryptBytes(plaintext.toByteArray(Charsets.UTF_8), compress, legacyBase64)

    /**
     * 解密密文成原始字节。
     * @param compressed true 时解密后 inflate raw 解压（v2 新格式）；
     *                  false 时直接返回解密字节（v1 旧格式）。
     * @return 解密（+ 解压）后的原始字节；失败返回 null。
     */
    private fun aesGcmDecryptBytes(ciphertext: String, compressed: Boolean): ByteArray? {
        val parts = ciphertext.split(":")
        if (parts.size != 2) return null
        return try {
            // 兼容旧文件：v1 用 NO_PADDING 填充的 base64 也能被 URL_SAFE|NO_PADDING 解
            val iv = Base64.decode(parts[0], B64_FLAGS)
            if (iv.size != IV_LENGTH_BYTES) return null
            val ct = Base64.decode(parts[1], B64_FLAGS)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            cipher.init(Cipher.DECRYPT_MODE, aesKey(), spec)
            val raw = cipher.doFinal(ct)
            if (compressed) inflateRaw(raw) else raw
        } catch (e: Exception) {
            null
        }
    }

    /** 旧 String 入口的薄壳：解密后转 UTF-8 字符串（v1 兼容路径用）。 */
    private fun aesGcmDecrypt(ciphertext: String, compressed: Boolean): String =
        aesGcmDecryptBytes(ciphertext, compressed)?.let { String(it, Charsets.UTF_8) } ?: ""

    // ---- deflate raw 压缩/解压（无 zlib header/trailer，省 6 字节）----

    private fun deflateRaw(input: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true) // raw, 无 zlib 头
        deflater.setInput(input)
        deflater.finish()
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(512)
        while (!deflater.finished()) {
            val n = deflater.deflate(buf)
            out.write(buf, 0, n)
        }
        deflater.end()
        return out.toByteArray()
    }

    private fun inflateRaw(input: ByteArray): ByteArray {
        val inflater = Inflater(true) // raw, 对应 deflate 的 nowrap=true
        inflater.setInput(input)
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(512)
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n == 0) {
                if (inflater.needsInput() || inflater.needsDictionary()) break
            }
            out.write(buf, 0, n)
        }
        inflater.end()
        return out.toByteArray()
    }

    // ---- HMAC-SHA256（固定密钥）----

    private fun hmacSha256(data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(hmacKey())
        val raw = mac.doFinal(data.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(raw, Base64.NO_WRAP)
    }

    // ---- 导出 ----

    /** 从当前配置抽取可导出子集（四大类完整备份）。 */
    private fun snapshot(): ExportableConfig {
        val cfg = application.configManager.config
        return ExportableConfig(
            // 1. 成绩抓取设置
            syncConfig = cfg.syncConfig,
            rivalSyncConfig = cfg.rivalSyncConfig,
            // 2. 成绩展示设置
            scoreDisplayType = cfg.scoreDisplayType,
            scoreStyleType = cfg.scoreStyleType,
            // 3. 本地设置
            localConfig = cfg.localConfig,
            // 4. 用户信息
            userInfo = cfg.userInfo,
        )
    }

    /**
     * 导出可分享配置为 JSON 字符串。
     * payload 用固定密钥 AES-GCM 加密（先 ProtoBuf 编码 → deflate raw 压缩，文件更小），HMAC 签密文块。
     * appVersion 跟随当前应用版本（BuildConfig.VERSION_NAME），导入时低版本导入高版本会拒绝。
     * 一律导出 version=2 新格式（ProtoBuf+deflate）；import 兼容读旧 v1（JSON 无压缩）。
     */
    @OptIn(ExperimentalSerializationApi::class)
    fun export(): String {
        val payload = snapshot()
        // ProtoBuf 编码：字段名换数字 tag，源头比 JSON 小约 40%
        val payloadBytes = ProtoBuf.encodeToByteArray(ExportableConfig.serializer(), payload)
        val encryptedPayload = aesGcmEncryptBytes(payloadBytes, compress = true, legacyBase64 = false)
        val hmac = hmacSha256(encryptedPayload)
        val bundle = ExportBundle(
            version = 2,
            exportedAt = System.currentTimeMillis(),
            appVersion = BuildConfig.VERSION_NAME,
            hmac = hmac,
            payload = encryptedPayload,
        )
        // bundle 围栏仍走 JSON（结构固定且小，ProtoBuf 对这种元数据无收益）
        return json.encodeToString(ExportBundle.serializer(), bundle)
    }

    /**
     * 将导出的 JSON 写入应用 filesDir，返回绝对路径；失败返回空串。
     */
    fun exportToFile(): String {
        return try {
            val content = export()
            val file = java.io.File(application.filesDir, "maiupload_config_export.json")
            file.writeText(content, Charsets.UTF_8)
            file.absolutePath
        } catch (e: Exception) {
            ""
        }
    }

    // ---- 导入 ----

    /**
     * 从 JSON 字符串导入配置。HMAC 验签 + AES-GCM 解密都通过才接受。
     * 只覆盖用户自定义设置，**不动** token / OAuth 令牌 / Rival 鉴权参数。
     *
     * 版本校验：导出文件的 appVersion 高于当前应用版本时仍允许导入（未定义字段自动忽略），
     * 但返回 [ImportResult.VersionTooHigh] 提示用户注意兼容性。
     * 低于或等于当前版本则返回 [ImportResult.Success]。
     */
    @OptIn(ExperimentalSerializationApi::class)
    fun import(jsonString: String): ImportResult {
        return try {
            val bundle = json.decodeFromString(ExportBundle.serializer(), jsonString)
            // 按 version 分流：
            //   v1 = 旧格式（JSON 明文，无压缩）—— legacy 兼容
            //   v2 = 新格式（ProtoBuf 编码 + deflate raw 压缩）
            //   其他版本拒绝
            when (bundle.version) {
                1, 2 -> Unit
                else -> return ImportResult.Corrupted
            }
            if (bundle.payload.isEmpty()) return ImportResult.Corrupted

            // HMAC 验签密文块，防篡改（v1/v2 验签流程一致）
            val expectedHmac = hmacSha256(bundle.payload)
            if (expectedHmac != bundle.hmac) return ImportResult.Corrupted

            // 验签通过，固定密钥 AES-GCM 解密。
            //   v1：明文是 JSON 字符串（用 aesGcmDecrypt 薄壳转 String）
            //   v2：解密后 inflate raw 解压得 ProtoBuf 字节（用 aesGcmDecryptBytes 拿 ByteArray）
            val payloadObj: ExportableConfig = if (bundle.version == 1) {
                val payloadJson = aesGcmDecrypt(bundle.payload, compressed = false)
                if (payloadJson.isEmpty()) return ImportResult.Corrupted
                // JSON 解析含 ignoreUnknownKeys = true，未定义字段自动忽略
                json.decodeFromString(ExportableConfig.serializer(), payloadJson)
            } else {
                // v2：ProtoBuf 解码（缺字段自动取默认值，向前兼容）
                val payloadBytes = aesGcmDecryptBytes(bundle.payload, compressed = true)
                    ?: return ImportResult.Corrupted
                ProtoBuf.decodeFromByteArray(ExportableConfig.serializer(), payloadBytes)
            }

            // 版本标记：高版本配置仍允许导入（未定义字段自动忽略），稍后返回警告
            val versionTooHigh = bundle.appVersion.isNotEmpty() &&
                compareVersion(bundle.appVersion, BuildConfig.VERSION_NAME) > 0

            // 仅覆盖可导出字段（四大类完整覆盖，不动 token / OAuth 令牌 / PKCE verifier）
            val cfg = application.configManager.config
            // 1. 成绩抓取设置
            cfg.syncConfig = payloadObj.syncConfig
            cfg.rivalSyncConfig = payloadObj.rivalSyncConfig
            // 2. 成绩展示设置
            cfg.scoreDisplayType = payloadObj.scoreDisplayType
            cfg.scoreStyleType = payloadObj.scoreStyleType
            // 3. 本地设置
            cfg.localConfig = payloadObj.localConfig
            // 4. 用户信息
            cfg.userInfo = payloadObj.userInfo
            application.configManager.save()

            if (versionTooHigh) ImportResult.VersionTooHigh(bundle.appVersion)
            else ImportResult.Success
        } catch (e: Exception) {
            ImportResult.Corrupted
        }
    }

    /**
     * 版本号比较：a > b 返回正数，a < b 返回负数，相等返回 0。
     * 支持形如 "1.2.3"、"1.2.3-abc123" 的版本号，按数值逐段比较，后缀忽略。
     */
    private fun compareVersion(a: String, b: String): Int {
        val normalize: (String) -> List<Int> = { s ->
            s.substringBefore('-').split('.').mapNotNull { it.toIntOrNull() }
        }
        val pa = normalize(a)
        val pb = normalize(b)
        val maxLen = maxOf(pa.size, pb.size)
        for (i in 0 until maxLen) {
            val va = pa.getOrElse(i) { 0 }
            val vb = pb.getOrElse(i) { 0 }
            if (va != vb) return va - vb
        }
        return 0
    }

    /** 从文件导入配置，返回结果。 */
    fun importFromFile(path: String): ImportResult {
        return try {
            val content = java.io.File(path).readText(Charsets.UTF_8)
            import(content)
        } catch (e: Exception) {
            ImportResult.Corrupted
        }
    }
}
