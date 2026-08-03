package io.github.skydynamic.maiproberplus.core.config

import android.content.Context
import android.util.Log
import io.github.skydynamic.maiproberplus.Application.Companion.application
import io.github.skydynamic.maiproberplus.core.data.chuni.ChuniEnums
import io.github.skydynamic.maiproberplus.core.data.maimai.MaimaiEnums
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.io.File

val JSON = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@OptIn(ExperimentalSerializationApi::class)
open class ConfigManager(context: Context) {
    private var configFile: File = File(context.filesDir, "config.json")

    var config: ConfigStorage = ConfigStorage()

    init {
        if (!configFile.exists()) {
            configFile.createNewFile()
            this.save()
        } else {
            this.read()
        }
    }

    private fun read() {
        val configInputStream = application.getFilesDirInputStream("config.json")
        try {
            config = JSON.decodeFromStream(configInputStream)
        } catch (e: Exception) {
            Log.e("ConfigManager", "读取 config.json 失败，使用默认配置: ${e.message}")
            config = ConfigStorage()
        } finally {
            configInputStream.close()
        }
    }

    fun save() {
        val configOutputStream = application.getFilesDirOutputStream("config.json")
        try {
            JSON.encodeToStream(config, configOutputStream)
        } catch (e: Exception) {
            Log.e("ConfigManager", "写入 config.json 失败: ${e.message}")
        } finally {
            configOutputStream.close()
        }
    }
}

@Serializable
enum class ScoreDisplayType(val displayName: String) {
    Small("小"),
    Middle("中"),
    Large("大"),
}

@Serializable
enum class ScoreStyleType(val displayName: String) {
    ColorOverlay("颜色覆盖"),
    TextShadow("文本阴影"),
}

@Serializable
data class ConfigStorage(
    var divingfishToken: String = "",
    var lxnsToken: String = "",
    // 落雪 OAuth 令牌：OAuth 模式下优先使用，与 personal lxnsToken 并存。
    var lxnsOAuthAccessToken: String = "",
    var lxnsOAuthRefreshToken: String = "",
    var lxnsOAuthAccessTokenExpireAt: Long = 0, // epoch ms，access_token 过期时间
    // 类型一（Rival 同步）配置：参考 Mizuki-plugin-Maimai-sync，全部留空由用户在设置页自填，
    // 不内置任何机台/鉴权/加密敏感信息。
    var rivalSyncConfig: RivalSyncConfig = RivalSyncConfig(),
    var syncConfig: SyncConfig = SyncConfig(),
    var localConfig: LocalConfig = LocalConfig(),
    var userInfo: UserInfo = UserInfo(),
    var scoreDisplayType: ScoreDisplayType = ScoreDisplayType.Small,
    var scoreStyleType: ScoreStyleType = ScoreStyleType.ColorOverlay,
    var lxnsRomVersionThreshold: Int = 25500,
)

/**
 * 类型一（Rival 同步）配置：对应 Mizuki 插件的 keychip + 游戏服务器 + 加密参数。
 * 全部字段留空，由用户在设置页"Rival 设置"大类自填，不内置。
 *
 * 字段说明（参考 Mizuki-plugin-Maimai-sync/plugins/maimai_sync）：
 *  - keychip：机台号（Mizuki keychip.csv 的 Keychip 列，作为 User-Agent 标识）
 *  - gameName：游戏名称（如 "maimai"）
 *  - gameServerUrl：RivalApi 调用端口，**直接拿来用不拼接**（如 "https://<game-server-url>"）
 *  - authServerUrl：Auth 服务器的鉴权节点，**直接拿来用不拼接**（如 "http://ai.sys-allnet.cn/wc_aime/api/get_data"）。
 *    **不强制**：仅在使用 QRcode 鉴权时要求填入；如果用户直接输入 userId 则不需要此字段。
 *  - cryptVersion / cryptKey / cryptIv / cryptEncoding / cryptObfuscate：加密参数（Mizuki CRYPT_VERSIONS）
 *  - authSalt / aesKey / aesIv / obfuscateParam：核心兼容层加密参数
 *  - userId / token：QR 鉴权后本地保存。userId 在设置页可编辑、星号隐私显示；
 *    直接填 userId 时跳过 QR 鉴权，authServerUrl 可不填。
 */
@Serializable
data class RivalSyncConfig(
    var keychip: String = "",
    var gameName: String = "",
    var gameServerUrl: String = "",
    var authServerUrl: String = "",
    var cryptVersion: String = "",
    var cryptKey: String = "",
    var cryptIv: String = "",
    var cryptEncoding: String = "",
    var cryptObfuscate: String = "",
    var authSalt: String = "",
    var aesKey: String = "",
    var aesIv: String = "",
    var obfuscateParam: String = "",
    // QR 鉴权后本地保存的 userId/token。userId 在设置页可编辑、星号隐私显示。
    // token 一般短期失效，留作缓存；过期重新 QR 鉴权刷新即可。
    var userId: String = "",
    var token: String = "",
)

@Serializable
data class SyncConfig(
    var maimaiIncrementalFetchScore: Boolean = true,
    var maimaiSyncDifficulty: List<Int> = MaimaiEnums.Difficulty.entries.map { it.diffIndex },
    var chuniSyncDifficulty: List<Int> = ChuniEnums.Difficulty.entries.map { it.diffIndex }
)

@Serializable
data class LocalConfig(
    var checkUpdate: Boolean = true,
    var checkSnapshotUpdate: Boolean = false,
    var cacheScore: Boolean = false,
    var parseMaimaiUserInfo: Boolean = false,
    var currentMaimaiVersion: Int = 0
)

@Serializable
data class UserInfo(
    var name: String = "MaiProberPlus",
    var maimaiDan: Int = 0,
    var maimaiIcon: Int = 1,
    var maimaiPlate: Int = 1,
    var maimaiClass: Int = 0,
    val chuniCharacter: Int = 0,
    var shougou: String = "Generated by MaiProberPlus-New",
    var shougouColor: String = "normal",
)