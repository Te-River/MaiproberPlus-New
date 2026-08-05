package io.github.teriver.maiupload.core.prober

import android.util.Log
import io.github.teriver.maiupload.Application.Companion.application
import io.github.teriver.maiupload.GlobalViewModel
import io.github.teriver.maiupload.core.data.chuni.ChuniScoreManager.writeChuniScoreCache
import io.github.teriver.maiupload.core.data.maimai.MaimaiScoreManager.writeMaimaiScoreCache
import io.github.teriver.maiupload.core.database.entity.MaimaiScoreEntity
import io.github.teriver.maiupload.core.utils.DebugLog

class LocalProberUtil : IProberUtil {
    override suspend fun uploadMaimaiProberData(
        importToken: String,
        authUrl: String,
        externalScores: List<MaimaiScoreEntity>?
    ): Boolean {
        application.sendNotification("本地查分器", "正在进行查分")
        sendMessageToUi("开始获取舞萌DX成绩并缓存到本地")
        if (externalScores != null) {
            writeMaimaiScoreCache(externalScores)
            sendMessageToUi("已缓存 ${externalScores.size} 条舞萌DX成绩到本地")
            application.sendNotification("本地查分器", "缓存完毕")
            return true
        }

        writeMaimaiScoreCache(getMaimaiScoreData(authUrl))

        sendMessageToUi("缓存舞萌DX成绩到本地完成")
        DebugLog.log("D", "LocalProberUtil", "缓存完成")
        GlobalViewModel.maimaiHooking = false
        application.sendNotification("本地查分器", "缓存完毕")
        return true
    }

    override suspend fun uploadChunithmProberData(importToken: String, authUrl: String) {
        application.sendNotification("本地查分器", "正在进行查分")
        sendMessageToUi("开始获取中二节奏成绩并缓存到本地")

        writeChuniScoreCache(getChuniScoreData(authUrl))

        sendMessageToUi("缓存中二节奏成绩到本地完成")
        DebugLog.log("D", "LocalProberUtil", "缓存完成")
        GlobalViewModel.chuniHooking = false
        application.sendNotification("本地查分器", "缓存完毕")
    }
}