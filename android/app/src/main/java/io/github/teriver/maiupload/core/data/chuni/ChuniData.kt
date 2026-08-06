package io.github.teriver.maiupload.core.data.chuni

import android.content.Context
import io.github.teriver.maiupload.Application
import io.github.teriver.maiupload.core.database.entity.ChuniScoreEntity
import io.github.teriver.maiupload.core.prober.client
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

val JSON = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

class ChuniData {
    @Serializable
    data class Aliases(
        @SerialName("song_id") val songId: Int,
        val aliases: List<String>
    )

    @Serializable
    data class SongsAliases(val aliases: List<Aliases>)

    @Serializable
    data class SongDifficulty(
        val difficulty: Int,
        val level: String,
        @SerialName("level_value") val levelValue: Float,
        @SerialName("note_designer") val noteDesigner: String,
        val version: Int,
        val kanji: String = "",
        val star: Int = 0,
    )

    @Serializable
    data class SongInfo(
        val id: Int, val title: String, val artist: String, val genre: String,
        val bpm: Int, val version: Int, val difficulties: List<SongDifficulty>,
        val disabled: Boolean = false
    )

    @Serializable
    data class LxnsSongListResponse(val songs: List<SongInfo>)

    companion object {
        var CHUNI_SONG_LIST = readChuniSongList()
        var CHUNI_SONG_ALIASES = readChuniSongAliases()

        @OptIn(DelicateCoroutinesApi::class)
        suspend fun syncMaimaiSongList() {
            val context = Application.application

            try {
                val result = client.get("https://maimai.lxns.net/api/v0/chunithm/song/list?notes=true")
                context.openFileOutput("chuni_song_list.json", Context.MODE_PRIVATE).use { out ->
                    out.bufferedWriter().use { it.write(result.bodyAsText()) }
                }
                // 下载完成后再重读，确保 CHUNI_SONG_LIST 用的是最新表
                CHUNI_SONG_LIST = readChuniSongList()
            } catch (e: Exception) {
                // 网络失败保留旧表，不阻断后续流程
            }
        }

        private fun readChuniSongList(): List<SongInfo> {
            return try {
                JSON.decodeFromString<LxnsSongListResponse>(
                    Application.application.getFilesDirInputStream("chuni_song_list.json")
                        .bufferedReader().use { it.readText() }
                ).songs
            } catch (e: Exception) {
                // 文件不存在/为空/损坏时降级为空表，避免 companion object <clinit> 抛
                // ExceptionInInitializerError 导致整个 ChuniData 类不可用
                emptyList()
            }
        }

        private fun readChuniSongAliases(): List<Aliases> {
            return try {
                JSON.decodeFromString<SongsAliases>(
                    Application.application.getFilesDirInputStream("chuni_song_aliases.json")
                        .bufferedReader().use { it.readText() }
                ).aliases
            } catch (e: Exception) {
                emptyList()
            }
        }

        fun getSongIdFromTitle(title: String): Int {
            return CHUNI_SONG_LIST.find { it.title == title }?.id ?: -1
        }

        fun getLevelValue(title: String, difficulty: ChuniEnums.Difficulty): Float {
            val level= CHUNI_SONG_LIST.find { it.title == title }
                ?.difficulties[difficulty.diffIndex]
                ?.levelValue
            return level ?: 0F
        }

        fun getChartVersion(title: String, difficulty: ChuniEnums.Difficulty): Int {
            val version= CHUNI_SONG_LIST.find { it.title == title }
                ?.difficulties[difficulty.diffIndex]
                ?.version
            return version ?: 0
        }

        fun calcOverPower(score: ChuniScoreEntity): Int {
            val playScore = score.score
            val level = getLevelValue(score.title, score.diff)
            val isFullCombo = score.fullComboType == ChuniEnums.FullComboType.FC
            val isAJ = score.fullComboType == ChuniEnums.FullComboType.AJ
                    || score.fullComboType == ChuniEnums.FullComboType.AJC
            val isAJC = score.fullComboType == ChuniEnums.FullComboType.AJC

            var overPower = 0F

            when {
                playScore >= 1007500 -> {
                    overPower = (level + 2) * 5 + (playScore - 1007500) * 0.0015F
                    when {
                        isFullCombo -> overPower += 0.5F
                        isAJ -> overPower += 1.0F
                        isAJC -> overPower += 1.25F
                    }
                }
                playScore >= 975000 -> {
                    overPower = raCalculate(level, playScore) * 5
                }
                else -> {
                    overPower = 0F
                }
            }

            return overPower.toInt()
        }

        private fun raCalculate(ds: Float, score: Int): Float {
            var result = 0F

            result = when {
                score >= 1009000 -> ds + 2.15F
                score >= 1007500 -> ds + 2 + ((score - 1007500) / 100).toInt() * 0.01F
                score >= 1005000 -> ds + 1.5F + ((score - 1005000) / 500).toInt() * 0.1F
                score >= 1000000 -> ds + 1 + ((score - 1000000) / 1000).toInt() * 0.1F
                score >= 975000 -> ds + ((score - 975000) / 2500).toInt() * 0.1F
                score >= 925000 -> ds - 3F
                score >= 900000 -> ds - 5F
                score >= 800000 -> (ds - 5) / 2F
                else -> 0F
            }

            return result
        }
    }
}