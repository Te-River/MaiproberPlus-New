package io.github.skydynamic.maiproberplus.core.prober.rival

import android.util.Log
import io.github.skydynamic.maiproberplus.Application.Companion.application
import io.github.skydynamic.maiproberplus.GlobalViewModel
import io.github.skydynamic.maiproberplus.core.data.maimai.MaimaiData
import io.github.skydynamic.maiproberplus.core.data.maimai.MaimaiEnums
import io.github.skydynamic.maiproberplus.core.database.entity.MaimaiScoreEntity
import io.github.skydynamic.maiproberplus.core.config.RivalSyncConfig
import io.github.skydynamic.maiproberplus.core.prober.ProberPlatform
import io.github.skydynamic.maiproberplus.core.prober.client
import io.github.skydynamic.maiproberplus.core.prober.sendMessageToUi
import io.github.skydynamic.maiproberplus.ui.compose.sync.SyncViewModel
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 类型一（Rival 同步）核心逻辑，参考 Mizuki-plugin-Maimai-sync 的 lib_auth + lib_game。
 *
 * 流程：
 *  1. [authByQr] QR 鉴权：POST authServerUrl，拿 userId/token 存本地；
 *  2. [fetchRivalScores] 调 GetUserRivalMusicApi 拉对手成绩（AES-CBC + zlib deflate），
 *     转成本地 MaimaiScoreEntity 格式返回。
 *
 * 全部配置（机台号/地区号/鉴权网址/服务器网址/加密参数）从
 * [io.github.skydynamic.maiproberplus.core.config.RivalSyncConfig] 读，不内置。
 */
object RivalSyncUtil {
    private const val TAG = "RivalSyncUtil"
    private val json = Json { ignoreUnknownKeys = true }

    /** 鉴权响应（AIME_DB_URL）。 */
    @Serializable
    private data class AuthResponse(
        val userID: Int = 0,
        val token: String? = null,
        val errorID: Int? = null
    )

    /** GetUserRivalMusicApi 响应顶层。 */
    @Serializable
    private data class RivalMusicResponse(
        val userRivalMusicList: List<UserRivalMusic> = emptyList(),
        val nextIndex: Int = 0
    )

    @Serializable
    private data class UserRivalMusic(
        val musicId: Int = 0,
        val userRivalMusicDetailList: List<UserRivalMusicDetail> = emptyList()
    )

    @Serializable
    private data class UserRivalMusicDetail(
        val level: Int = 0,
        val achievement: Float = 0f,
        val comboStatus: Int = 0,
        val syncStatus: Int = 0,
        val deluxscoreMax: Int = 0
    )

    /**
     * 类型一完整流程：拉对手成绩 → 上传到当前选定查分器（落雪 OAuth / 水鱼 Token / 本地）。
     * 对齐 Mizuki lib_sync_core._upload_to_platforms：拉 Rival 成绩后转查分器格式上传。
     * 上传走现成 IProberUtil.uploadMaimaiProberData，externalScores 传 Rival 拉的成绩跳过 VPN 抓包。
     */
    suspend fun uploadToProber() {
        val platform = GlobalViewModel.proberPlatform
        if (platform == ProberPlatform.LOCAL) {
            sendMessageToUi("Rival 同步不支持本地查分器，请选落雪或水鱼")
            return
        }
        sendMessageToUi("开始拉对手成绩并上传到${platform.proberName}")
        val scores = fetchRivalScores()
        if (scores.isEmpty()) {
            sendMessageToUi("通过 Rival 同步失败：未拉到对手成绩，请检查 Rival 设置")
            return
        }
        val config = application.configManager.config
        val importToken = when (platform) {
            ProberPlatform.LXNS -> {
                if (SyncViewModel.tokenInputMode == 1) "" else config.lxnsToken
            }
            ProberPlatform.DIVING_FISH -> config.divingfishToken
            ProberPlatform.LOCAL -> ""
        }
        platform.factory.uploadMaimaiProberData(
            importToken = importToken,
            authUrl = "",
            externalScores = scores
        )
    }

    /** QR 鉴权：POST authServerUrl，拿 userId/token 存本地。返回是否成功。 */
    suspend fun authByQr(qrCode: String): Boolean {
        val cfg = application.configManager.config.rivalSyncConfig
        if (qrCode.isBlank()) {
            sendMessageToUi("二维码不能为空")
            return false
        }
        // SGWCMAID(8字节前缀) + 12位时间戳 = 20字节，剥离
        val finalQr = if (qrCode.startsWith("SGWCMAID")) qrCode.drop(20) else qrCode
        val timeStamp = SimpleDateFormat("yyMMddHHmmss", Locale.CHINA)
            .format(Date())
        if (cfg.keychip.isBlank() || cfg.authSalt.isBlank() || cfg.authServerUrl.isBlank()) {
            sendMessageToUi("类型一配置缺失：keychip/authSalt/authServerUrl")
            return false
        }
        val rawSig = "${cfg.keychip}${timeStamp}${cfg.authSalt}"
        val authKey = sha256Hex(rawSig).uppercase()
        val payload = mapOf(
            "chipID" to cfg.keychip,
            "openGameID" to "MAID",
            "key" to authKey,
            "qrCode" to finalQr,
            "timestamp" to timeStamp
        )
        return try {
            val resp = client.post(cfg.authServerUrl) {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.UserAgent, "WC_AIME_LIB")
                setBody(mapToJson(payload))
            }
            val respText = resp.bodyAsText()
            if (resp.status.value != 200) {
                Log.e(TAG, "鉴权 HTTP ${resp.status}: $respText")
                sendMessageToUi("鉴权 HTTP 错误: ${resp.status}")
                return false
            }
            val data = try { json.decodeFromString<AuthResponse>(respText) } catch (_: Exception) {
                sendMessageToUi("鉴权响应解析失败: $respText")
                return false
            }
            if (data.userID == 0 || data.errorID != 0) {
                val msg = when (data.errorID) {
                    1 -> "二维码并非最新，请检查是否输入错误"
                    2 -> "二维码已过期，请重新生成最新二维码"
                    else -> "鉴权拒绝: ${data.errorID}"
                }
                sendMessageToUi(msg)
                return false
            }
            cfg.userId = data.userID.toString()
            cfg.token = data.token ?: ""
            application.configManager.save()
            sendMessageToUi("鉴权成功，userId=${data.userID}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "鉴权异常: ${e.message}", e)
            sendMessageToUi("鉴权异常: ${e.message}")
            false
        }
    }

    /** 调 GetUserRivalMusicApi 拉对手成绩，转本地 MaimaiScoreEntity 格式返回。 */
    suspend fun fetchRivalScores(): List<MaimaiScoreEntity> {
        val cfg = application.configManager.config.rivalSyncConfig
        val userId = cfg.userId.toIntOrNull() ?: run {
            sendMessageToUi("未保存 userId，请先 QR 鉴权")
            return emptyList()
        }
        if (cfg.gameServerUrl.isBlank() || cfg.apiHash.isBlank() || cfg.cryptKey.isBlank() || cfg.cryptIv.isBlank()) {
            sendMessageToUi("Rival 设置缺失必填项：游戏服务器网址/哈希/加密 Key/加密 IV")
            return emptyList()
        }
        // gameServerUrl（含尾斜杠）+ apiHash 拼成完整请求 URL；host 仅作 Header 用
        val url = "${cfg.gameServerUrl}${cfg.apiHash}"
        val host = cfg.gameServerUrl
            .removePrefix("https://").removePrefix("http://").substringBefore('/')

        val all = mutableListOf<MaimaiScoreEntity>()
        var nextIndex = 0
        do {
            val data = mapOf(
                "userId" to 0,
                "rivalId" to userId,
                "nextIndex" to nextIndex,
                "userRivalMusicLevelList" to (0..4).map { mapOf("level" to it) } +
                    listOf(mapOf("level" to 10)),
                "maxCount" to 100
            )
            val resp = try {
                client.post(url) {
                    contentType(ContentType.Application.Json)
                    header(HttpHeaders.ContentEncoding, "deflate")
                    header("Mai-Encoding", cfg.cryptEncoding)
                    header(HttpHeaders.Host, host)
                    header(HttpHeaders.UserAgent, "${cfg.apiHash}#${cfg.keychip}")
                    setBody(encrypt(mapToJson(data), cfg))
                }
            } catch (e: Exception) {
                Log.e(TAG, "拉取对手成绩异常: ${e.message}", e)
                sendMessageToUi("拉取对手成绩异常: ${e.message}")
                return emptyList()
            }
            if (resp.status.value != 200) {
                sendMessageToUi("拉取对手成绩 HTTP ${resp.status}")
                return emptyList()
            }
            val decrypted = try { decrypt(resp.bodyAsBytes(), cfg) } catch (e: Exception) {
                Log.e(TAG, "解密响应失败: ${e.message}", e)
                sendMessageToUi("解密响应失败: ${e.message}")
                return emptyList()
            }
            val page = try { json.decodeFromString<RivalMusicResponse>(decrypted) } catch (_: Exception) {
                sendMessageToUi("响应解析失败: $decrypted")
                return emptyList()
            }
            for (m in page.userRivalMusicList) {
                for (d in m.userRivalMusicDetailList) {
                    all += toEntity(m.musicId, d)
                }
            }
            nextIndex = page.nextIndex
        } while (nextIndex != 0)
        sendMessageToUi("拉取对手成绩完成，共 ${all.size} 条")
        return all
    }

    /** 转本地 entity：musicId + level + achievement → MaimaiScoreEntity。 */
    private fun toEntity(musicId: Int, d: UserRivalMusicDetail): MaimaiScoreEntity {
        val info = MaimaiData.MAIMAI_SONG_LIST.find { it.id == musicId }
        val title = info?.title ?: "Unknown($musicId)"
        val diff = MaimaiEnums.Difficulty.getDifficultyWithIndex(d.level)
        // type 优先按 difficulty 在 standard/dx 里能否找到对应 chart 决定
        val type = info?.let { song ->
            val std = song.difficulties.standard.getOrNull(d.level)
            if (std != null) MaimaiEnums.SongType.STANDARD
            else song.difficulties.dx.getOrNull(d.level)?.let { MaimaiEnums.SongType.DX }
        } ?: MaimaiEnums.SongType.STANDARD
        val version = info?.let { MaimaiData.getChartVersion(it.title, diff, type) } ?: 0
        return MaimaiScoreEntity(
            songId = musicId,
            title = title,
            level = MaimaiData.getLevelValue(title, diff, type),
            achievement = d.achievement,
            dxScore = d.deluxscoreMax,
            rating = 0,
            version = version,
            type = type,
            diff = diff,
            rankType = MaimaiEnums.RankType.getRankTypeByScore(d.achievement),
            syncType = MaimaiEnums.SyncType.getSyncTypeByName(if (d.syncStatus != 0) "fs" else ""),
            fullComboType = MaimaiEnums.FullComboType.getFullComboTypeByName(if (d.comboStatus != 0) "fc" else ""),
            // 无现成 isOld 字段，用 version < lxnsRomVersionThreshold 推（与 B35/B15 划分一致）
            isOld = version != 0 && version < application.configManager.config.lxnsRomVersionThreshold
        )
    }

    // ---- 加密工具（对齐 Mizuki MaimaiCrypt：AES-CBC + zlib deflate + JSON） ----

    private fun encrypt(plain: String, cfg: RivalSyncConfig): ByteArray {
        val jsonBytes = plain.toByteArray(Charsets.UTF_8)
        val compressed = deflate(jsonBytes)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE,
            SecretKeySpec(cfg.cryptKey.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(cfg.cryptIv.toByteArray(Charsets.UTF_8))
        )
        return cipher.doFinal(compressed)
    }

    private fun decrypt(content: ByteArray, cfg: RivalSyncConfig): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE,
            SecretKeySpec(cfg.cryptKey.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(cfg.cryptIv.toByteArray(Charsets.UTF_8))
        )
        val decrypted = cipher.doFinal(content)
        // 试 zlib decompress；失败则当短响应原样返回
        return try { inflate(decrypted) } catch (_: Exception) { String(decrypted, Charsets.UTF_8) }
    }

    private fun deflate(input: ByteArray): ByteArray {
        val def = Deflater()
        def.setInput(input)
        def.finish()
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!def.finished()) { out.write(buf, 0, def.deflate(buf)) }
        def.end()
        return out.toByteArray()
    }

    private fun inflate(input: ByteArray): String {
        val inf = Inflater()
        inf.setInput(input)
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!inf.finished()) { out.write(buf, 0, inf.inflate(buf)) }
        inf.end()
        return out.toString(Charsets.UTF_8.name())
    }

    private fun sha256Hex(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    /** 手写 JSON 拼接（Map 不是 Serializable，不能走 json.encodeToString）。支持嵌套 Map/List/Number/String。 */
    private fun mapToJson(v: Any?): String = when (v) {
        null -> "null"
        is String -> "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        is Number, is Boolean -> v.toString()
        is Map<*, *> -> v.entries.joinToString(prefix = "{", postfix = "}") { (k, vv) ->
            "\"$k\":${mapToJson(vv)}"
        }
        is List<*> -> v.joinToString(prefix = "[", postfix = "]") { mapToJson(it) }
        else -> "\"" + v.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }

    private fun md5Hex(s: String): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
