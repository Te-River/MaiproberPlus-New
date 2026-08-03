package io.github.skydynamic.maiproberplus.core.prober

import io.github.skydynamic.maiproberplus.core.database.entity.ChuniScoreEntity
import io.github.skydynamic.maiproberplus.core.database.entity.MaimaiScoreEntity

interface IProberUtil {
    suspend fun updateUserInfo(importToken: String) {}
    /**
     * 上传舞萌DX成绩到查分器。
     * @param importToken 查分器 Token（水鱼 Import-Token / 落雪个人 Token；落雪 OAuth 模式可传空）
     * @param authUrl VPN 抓包的查分页面 URL（externalScores 为空时用它拉成绩）
     * @param externalScores 可选：外部已拉好的成绩（如 Rival 同步拉的对手成绩）。
     *        非空时跳过 VPN 抓包，直接上传这批成绩。
     */
    suspend fun uploadMaimaiProberData(
        importToken: String,
        authUrl: String,
        externalScores: List<MaimaiScoreEntity>? = null
    ) {}
    suspend fun uploadChunithmProberData(importToken: String, authUrl: String) {}
    suspend fun getMaimaiProberData(importToken: String): List<MaimaiScoreEntity> {
        return emptyList()
    }
    suspend fun getChuniProberData(importToken: String): List<ChuniScoreEntity> {
        return emptyList()
    }
    suspend fun getChuniScoreBests(importToken: String): List<ChuniScoreEntity> {
        return emptyList()
    }
}

