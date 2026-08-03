package io.github.skydynamic.maiproberplus.core.prober

import android.util.Log
import io.github.skydynamic.maiproberplus.Application.Companion.application
import io.github.skydynamic.maiproberplus.GlobalViewModel
import io.github.skydynamic.maiproberplus.core.data.chuni.ChuniData
import io.github.skydynamic.maiproberplus.core.data.chuni.ChuniEnums
import io.github.skydynamic.maiproberplus.core.data.chuni.ChuniScoreManager.writeChuniScoreCache
import io.github.skydynamic.maiproberplus.core.data.maimai.MaimaiData
import io.github.skydynamic.maiproberplus.core.data.maimai.MaimaiEnums
import io.github.skydynamic.maiproberplus.core.data.maimai.MaimaiScoreManager.writeMaimaiScoreCache
import io.github.skydynamic.maiproberplus.core.database.entity.ChuniScoreEntity
import io.github.skydynamic.maiproberplus.core.database.entity.MaimaiScoreEntity
import io.github.skydynamic.maiproberplus.core.prober.models.lxns.LxnsChuniRequestBody
import io.github.skydynamic.maiproberplus.core.prober.models.lxns.LxnsChuniResponse
import io.github.skydynamic.maiproberplus.core.prober.models.lxns.LxnsChuniScoreBody
import io.github.skydynamic.maiproberplus.core.prober.models.lxns.LxnsGetChuniScoreBestsResponse
import io.github.skydynamic.maiproberplus.core.prober.models.lxns.LxnsGetChuniScoreResponse
import io.github.skydynamic.maiproberplus.core.prober.models.lxns.LxnsGetMaimaiScoreResponse
import io.github.skydynamic.maiproberplus.core.prober.models.lxns.LxnsGetSiteConfigResponse
import io.github.skydynamic.maiproberplus.core.prober.models.lxns.LxnsMaimaiRequestBody
import io.github.skydynamic.maiproberplus.core.prober.models.lxns.LxnsMaimaiResponse
import io.github.skydynamic.maiproberplus.core.prober.models.lxns.LxnsMaimaiScoreBody
import io.github.skydynamic.maiproberplus.core.prober.models.lxns.LxnsUserInfoResponse
import io.github.skydynamic.maiproberplus.core.prober.lxns.LxnsOAuthUtil
import io.github.skydynamic.maiproberplus.core.utils.DebugLog
import io.github.skydynamic.maiproberplus.core.utils.ErrorLog
import io.github.skydynamic.maiproberplus.ui.compose.sync.SyncViewModel
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.floor

class LxnsProberUtil : IProberUtil {

    private val baseApiUrl = "https://maimai.lxns.net"

    /**
     * 按当前 Token 输入模式解析请求头：OAuth 模式（tokenInputMode==1）走
     * `Authorization: Bearer <access_token>`（必要时刷新），Token 模式沿用
     * `X-User-Token: <personal_token>`。返回 null 表示 OAuth 失效需重新授权。
     */
    private suspend fun resolveAuthHeader(importToken: String): Pair<String, String>? {
        return if (SyncViewModel.tokenInputMode == 1) {
            val accessToken = LxnsOAuthUtil.ensureValidAccessToken()
                ?: return null
            "Authorization" to "Bearer $accessToken"
        } else {
            "X-User-Token" to importToken
        }
    }

    override suspend fun updateUserInfo(importToken: String) {
        val auth = resolveAuthHeader(importToken) ?: run {
            sendMessageToUi("落雪授权已失效，请重新授权")
            return
        }
        val resp = client.get("https://maimai.lxns.net/api/v0/user/maimai/player") {
            header(auth.first, auth.second)
        }
        if (resp.status.value == 200) {
            val data = resp.body<LxnsUserInfoResponse>().data
            val info = application.configManager.config.userInfo
            info.name = data.name
            info.shougou = data.trophy.name
            info.shougouColor = data.trophy.color?.lowercase() ?: "normal"
            info.maimaiIcon = data.icon.id
            info.maimaiPlate = data.namePlate.id
            info.maimaiDan = data.courseRank
            info.maimaiClass = data.classRank
            
        } else {
            sendMessageToUi("同步用户信息失败: ${resp.bodyAsText()}")
        }
    }

    override suspend fun uploadMaimaiProberData(
        importToken: String,
        authUrl: String,
        externalScores: List<MaimaiScoreEntity>?
    ): Boolean {
        val isCache = application.configManager.config.localConfig.cacheScore

        application.sendNotification("落雪查分器", "舞萌数据上传中")
        sendMessageToUi("开始获取舞萌数据并上传到落雪查分器")
        val scores = externalScores ?: getMaimaiScoreData(authUrl)

        if (scores.isEmpty()) {
            sendMessageToUi("通过 Rival 同步失败：未拉到成绩，请检查 Rival 设置")
            return false
        }

        val postScores = scores.map {
            LxnsMaimaiScoreBody(
                id = MaimaiData.getSongIdFromTitle(it.title),
                levelIndex = it.diff.diffIndex,
                // 对齐 Mizuki _transform_for_lxns：原值 /10000 转浮点
                achievements = it.achievement / 10000.0f,
                fc = it.fullComboType.typeName,
                fs = it.syncType.syncName,
                dxScore = it.dxScore,
                type = it.type.type
            )
        }

        val body = Json.encodeToString(LxnsMaimaiRequestBody(postScores))

        val postResponse = try {
            val auth = resolveAuthHeader(importToken) ?: run {
                sendMessageToUi("落雪授权已失效，请重新授权")
                return false
            }
            client.post("$baseApiUrl/api/v0/user/maimai/player/scores") {
                setBody(body)
                header(auth.first, auth.second)
                headers {
                    append(HttpHeaders.ContentType, "application/json")
                }
            }
        } catch (e: Exception) {
            ErrorLog.logError("LxnsProberUtil", "上传失败: $e", e)
            sendMessageToUi("上传失败: $e")
            return false
        }

        val postScoreResponseBody = postResponse.body<LxnsMaimaiResponse>()
        val ok = postScoreResponseBody.success
        if (ok) {
            sendMessageToUi("上传舞萌成绩到落雪查分器成功")
            DebugLog.log("D", "LxnsProberUtil", "上传完毕")
        } else {
            sendMessageToUi("舞萌成绩上传到落雪查分器失败: ${postScoreResponseBody.message}")
            ErrorLog.logError("LxnsProberUtil", "上传失败: ${postScoreResponseBody.message}")
        }
        GlobalViewModel.maimaiHooking = false
        application.sendNotification("落雪查分器", "舞萌数据上传完毕")
        if (isCache) {
            writeMaimaiScoreCache(scores)
        }
        return ok
    }

    override suspend fun uploadChunithmProberData(importToken: String, authUrl: String) {
        val isCache = application.configManager.config.localConfig.cacheScore

        application.sendNotification("落雪查分器", "中二节奏数据上传中")
        sendMessageToUi("开始获取中二节奏数据并上传到落雪查分器")
        val scores = getChuniScoreData(authUrl)

        if (scores.isEmpty()) {
            return
        }

        val postScores = scores.map {
            LxnsChuniScoreBody(
                id = it.songId,
                levelIndex = it.diff.diffIndex,
                score = it.score,
                clear = it.clearType.type,
                fullCombo = it.fullComboType.typeName,
                fullChain = it.fullChainType.typeName
            )
        }

        val body = Json.encodeToString(LxnsChuniRequestBody(postScores))

        val postResponse = try {
            val auth = resolveAuthHeader(importToken) ?: run {
                sendMessageToUi("落雪授权已失效，请重新授权")
                return
            }
            client.post("$baseApiUrl/api/v0/user/chunithm/player/scores") {
                setBody(body)
                headers {
                    append(auth.first, auth.second)
                    append(HttpHeaders.ContentType, "application/json")
                }
            }
        } catch (e: HttpRequestTimeoutException) {
            ErrorLog.logError("LxnsProberUtil", "上传失败: $e", e)
            sendMessageToUi("上传失败: $e")
            return
        }

        val postScoreResponseBody = postResponse.body<LxnsChuniResponse>()
        if (postScoreResponseBody.success) {
            sendMessageToUi("上传中二节奏成绩到落雪查分器成功")
            DebugLog.log("D", "LxnsProberUtil", "上传完毕")
        } else {
            sendMessageToUi("上传中二成绩成绩到落雪查分器失败: ${postScoreResponseBody.message}")
            ErrorLog.logError("LxnsProberUtil", "上传失败: ${postScoreResponseBody.message}")
        }
        GlobalViewModel.chuniHooking = false
        application.sendNotification("落雪查分器", "中二数据上传完毕")
        if (isCache) {
            writeChuniScoreCache(scores)
        }
    }

    override suspend fun getMaimaiProberData(importToken: String): List<MaimaiScoreEntity> {
        try {
            val auth = resolveAuthHeader(importToken) ?: run {
                sendMessageToUi("落雪授权已失效，请重新授权")
                return emptyList()
            }
            val response = client.get("$baseApiUrl/api/v0/user/maimai/player/scores") {
                header(auth.first, auth.second)
            }
            if (response.status.value != 200) {
                sendMessageToUi("获取舞萌数据失败, API返回体: ${response.bodyAsText()}")
                return emptyList()
            }
            val body = response.body<LxnsGetMaimaiScoreResponse>()
            val parseList = arrayListOf<MaimaiScoreEntity>()
            body.data.forEach {
                val type = MaimaiEnums.SongType.getSongTypeByName(it.type)
                if (type == MaimaiEnums.SongType.UTAGE) return@forEach
                val diff = MaimaiEnums.Difficulty.getDifficultyWithIndex(it.levelIndex)
                val levelValue = MaimaiData.getLevelValue(it.songName, diff, type)
                val version = MaimaiData.getChartVersion(it.songName, diff, type)
                parseList.add(
                    MaimaiScoreEntity(
                        songId = MaimaiData.getSongIdFromTitle(it.songName),
                        title = it.songName ?: "",
                        level = levelValue,
                        achievement = it.achievements,
                        dxScore = it.dxScore,
                        rating = floor(it.dxRating ?: 0F).toInt(),
                        version = version,
                        type = type,
                        diff = diff,
                        rankType = MaimaiEnums.RankType.getRankTypeByScore(it.achievements),
                        syncType = MaimaiEnums.SyncType.getSyncTypeByName(it.fs ?: ""),
                        fullComboType = MaimaiEnums.FullComboType.getFullComboTypeByName(it.fc ?: "")
                    )
                )
            }
            return parseList
        } catch (e: Exception) {
            DebugLog.log("D", "LxnsProberUtil", "获取舞萌成绩失败: $e", e)
            sendMessageToUi("获取舞萌成绩失败: ${e.message}")
            return emptyList()
        }
    }

    override suspend fun getChuniProberData(importToken: String): List<ChuniScoreEntity> {
        try {
            val auth = resolveAuthHeader(importToken) ?: run {
                sendMessageToUi("落雪授权已失效，请重新授权")
                return emptyList()
            }
            val response = client.get("$baseApiUrl/api/v0/user/chunithm/player/scores") {
                header(auth.first, auth.second)
            }
            val body = response.body<LxnsGetChuniScoreResponse>()
            val parseList = addChuniScoreDataToList(
                scores = body.data,
                list = arrayListOf(),
                recent = false
            )
            return parseList
        } catch (e: Exception) {
            DebugLog.log("D", "LxnsProberUtil", "获取中二成绩失败: $e", e)
            sendMessageToUi("获取中二成绩失败: ${e.message}")
            return emptyList()
        }
    }

    override suspend fun getChuniScoreBests(importToken: String): List<ChuniScoreEntity> {
        try {
            val auth = resolveAuthHeader(importToken) ?: run {
                sendMessageToUi("落雪授权已失效，请重新授权")
                return emptyList()
            }
            val response = client.get("$baseApiUrl/api/v0/user/chunithm/player/bests") {
                header(auth.first, auth.second)
            }
            val body = response.body<LxnsGetChuniScoreBestsResponse>()
            var parseList = addChuniScoreDataToList(
                scores = body.data.bests,
                list = arrayListOf(),
                recent = false
            )
            parseList = addChuniScoreDataToList(
                scores = body.data.recents,
                list = parseList,
                recent = true
            )
            return parseList
        } catch (e: Exception) {
            DebugLog.log("D", "LxnsProberUtil", "获取中二成绩失败: $e", e)
            sendMessageToUi("获取中二成绩失败: ${e.message}")
            return emptyList()
        }
    }

    private fun addChuniScoreDataToList(
        scores: List<LxnsChuniScoreBody>,
        list:  ArrayList<ChuniScoreEntity>,
        recent: Boolean = false
    ): ArrayList<ChuniScoreEntity> {
        scores.forEach {
            val diff = ChuniEnums.Difficulty.getDifficultyWithIndex(it.levelIndex)
            val levelValue = ChuniData.getLevelValue(it.songName, diff)
            val version = ChuniData.getChartVersion(it.songName, diff)
            list.add(
                ChuniScoreEntity(
                    songId = it.id,
                    title = it.songName,
                    level = levelValue,
                    score = it.score,
                    rating = it.rating,
                    version = version,
                    diff = diff,
                    rankType = ChuniEnums.RankType.getRankTypeByScore(it.score),
                    fullComboType =
                        ChuniEnums.FullComboType.getFullComboTypeWithName(it.fullCombo ?: ""),
                    clearType = ChuniEnums.ClearType.getClearTypeWithName(it.clear ?: ""),
                    fullChainType =
                        ChuniEnums.FullChainType.getFullChainTypeWithName(it.fullChain ?: ""),
                    recent = recent
                )
            )
        }
        return list
    }

    suspend fun getMaimaiResourceVersion() : Int {
        return try {
            val response = client.get("$baseApiUrl/api/v0/site/config")
            val body = response.body<LxnsGetSiteConfigResponse>()
            body.data.resourceVersion.maimai
        } catch (e: Exception) {
            DebugLog.log("D", "LxnsProberUtil", "获取舞萌资源版本失败: $e", e)
            sendMessageToUi("获取舞萌资源版本失败: ${e.message}")
            0
        }
    }
}