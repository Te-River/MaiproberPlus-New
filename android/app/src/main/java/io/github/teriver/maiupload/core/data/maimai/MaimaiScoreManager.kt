package io.github.teriver.maiupload.core.data.maimai

import androidx.lifecycle.viewModelScope
import io.github.teriver.maiupload.Application.Companion.application
import io.github.teriver.maiupload.GlobalViewModel
import io.github.teriver.maiupload.core.database.entity.MaimaiScoreEntity
import io.github.teriver.maiupload.ui.compose.scores.maimai.refreshMaimaiScore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

object MaimaiScoreManager {
    suspend fun writeMaimaiScoreCache(data: List<MaimaiScoreEntity>) {
        val threshold = application.configManager.config.lxnsRomVersionThreshold
        val dao = application.db.maimaiScoreDao()
        if (dao.getMusicScoreCount() == 0) {
            dao.insertAll(data.map { it.withIsOld(threshold) })
        } else {
            data.forEach {
                val tagged = it.withIsOld(threshold)
                if (!dao.exists(tagged.title, tagged.diff, tagged.type, tagged.achievement, tagged.dxScore)) {
                    dao.insert(tagged)
                } else {
                    return@forEach
                }
            }
        }
        refreshMaimaiScore()
    }

    private fun MaimaiScoreEntity.withIsOld(threshold: Int): MaimaiScoreEntity =
        this.copy(isOld = this.version < threshold)

    fun createMaimaiScore(score: MaimaiScoreEntity) {
        GlobalViewModel.viewModelScope.launch(Dispatchers.IO) {
            val dao = application.db.maimaiScoreDao()
            if (!dao.exists(score.title, score.diff, score.type, score.achievement, score.dxScore)) {
                dao.insert(score)
                refreshMaimaiScore()
            }
        }
    }

    fun getMaimaiScoreByScoreId(scoreId: Int): MaimaiScoreEntity? {
        var score: MaimaiScoreEntity? = null
        runBlocking {
            GlobalViewModel.viewModelScope.launch(Dispatchers.IO) {
                val dao = application.db.maimaiScoreDao()
                score = dao.getMusicScoreByScoreId(scoreId)
            }.join()
        }
        return score
    }

    fun getMaimaiScoreCache(): List<MaimaiScoreEntity> {
        var scores: List<MaimaiScoreEntity> = emptyList()
        runBlocking {
            GlobalViewModel.viewModelScope.launch(Dispatchers.IO) {
                val dao = application.db.maimaiScoreDao()
                scores = dao.getAllHighestMusicScore()
            }.join()
        }
        return scores
    }

    fun deleteScore(score: MaimaiScoreEntity) {
        GlobalViewModel.viewModelScope.launch(Dispatchers.IO) {
            val dao = application.db.maimaiScoreDao()
            dao.deleteWithScoreId(score.scoreId)
            refreshMaimaiScore()
        }
    }

    fun deleteAllScore() {
        GlobalViewModel.viewModelScope.launch(Dispatchers.IO) {
            val dao = application.db.maimaiScoreDao()
            dao.deleteAll()
            refreshMaimaiScore()
        }
    }
}