package io.github.teriver.maiupload.core.data.maimai

import android.content.Context
import io.github.teriver.maiupload.Application
import io.github.teriver.maiupload.R
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

class MaimaiData {
    @Serializable
    data class Aliases(
        @SerialName("song_id") val songId: Int,
        val aliases: List<String>
    )

    @Serializable
    data class SongsAliases(val aliases: List<Aliases>)

    @Serializable
    data class Notes(
        val total: Int = 0,
        val tap: Int = 0,
        val hold: Int = 0,
        val slide: Int = 0,
        val touch: Int = 0,
        @SerialName("break") val breakTotal: Int = 0,
    )

    @Serializable
    data class SongDiffculty(
        val type: MaimaiEnums.SongType,
        val difficulty: Int,
        val level: String,
        @SerialName("level_value") val levelValue: Float,
        @SerialName("note_designer") val noteDesigner: String,
        val version: Int,
        val notes: Notes
    )

    @Serializable
    data class SongDifficulties(
        val standard: List<SongDiffculty> = emptyList(),
        val dx: List<SongDiffculty> = emptyList(),
        val utage: List<SongDiffculty> = emptyList(),
    )

    @Serializable
    data class SongInfo(
        val id: Int, val title: String, val artist: String, val genre: String,
        val bpm: Int, val version: Int, val difficulties: SongDifficulties,
        val disabled: Boolean = false
    )

    @Serializable
    data class LxnsSongListResponse(val songs: List<SongInfo>)

    companion object {
        var MAIMAI_SONG_LIST = readMaimaiSongList()
        var MAIMAI_SONG_ALIASES = readMaimaiSongAliases()

        @OptIn(DelicateCoroutinesApi::class)
        suspend fun syncMaimaiSongList() {
            val context = Application.application
            val listFile = File(context.filesDir, "maimai_song_list.json")

            try {
                val result = client.get("https://maimai.lxns.net/api/v0/maimai/song/list?notes=true")
                context.openFileOutput("maimai_song_list.json", Context.MODE_PRIVATE).use { out ->
                    out.bufferedWriter().use { it.write(result.bodyAsText()) }
                }
                // 刷新成功后再重读，确保 MAIMAI_SONG_LIST 用的是最新表
                MAIMAI_SONG_LIST = readMaimaiSongList()
            } catch (e: Exception) {
                // 网络失败保留旧表，不阻断后续流程
            }
        }

        private fun readMaimaiSongList(): List<SongInfo> {
            return try {
                JSON.decodeFromString<LxnsSongListResponse>(
                    Application.application.getFilesDirInputStream("maimai_song_list.json")
                        .bufferedReader().use { it.readText() }
                ).songs
            } catch (e: Exception) {
                // 文件不存在/为空/损坏时降级为空表，避免 companion object <clinit> 抛
                // ExceptionInInitializerError 导致整个 MaimaiData 类不可用
                emptyList()
            }
        }

        private fun readMaimaiSongAliases(): List<Aliases> {
            return try {
                JSON.decodeFromString<SongsAliases>(
                    Application.application.getFilesDirInputStream("maimai_song_aliases.json")
                        .bufferedReader().use { it.readText() }
                ).aliases
            } catch (e: Exception) {
                emptyList()
            }
        }

        fun getSongIdFromTitle(title: String?): Int {
            return MAIMAI_SONG_LIST.find { it.title == title }?.id ?: -1
        }

        fun getLevelValue(
            title: String?,
            diffculty: MaimaiEnums.Difficulty,
            type: MaimaiEnums.SongType
        ): Float {
            val difficulties = MAIMAI_SONG_LIST.find { it.title == title }?.difficulties
            val idx = diffculty.diffIndex
            return when (type) {
                MaimaiEnums.SongType.DX -> {
                    val dx = difficulties?.dx
                    if (dx != null && idx < dx.size) dx[idx].levelValue else 0F
                }
                MaimaiEnums.SongType.STANDARD -> {
                    val std = difficulties?.standard
                    if (std != null && idx < std.size) std[idx].levelValue else 0F
                }
                MaimaiEnums.SongType.UTAGE -> {
                    val utg = difficulties?.utage
                    if (utg != null && idx < utg.size) utg[idx].levelValue else 0F
                }
            }
        }

        fun getChartVersion(
            title: String?,
            diffculty: MaimaiEnums.Difficulty,
            type: MaimaiEnums.SongType
        ): Int {
            val difficulties = MAIMAI_SONG_LIST.find { it.title == title }?.difficulties
            val idx = diffculty.diffIndex
            return when (type) {
                MaimaiEnums.SongType.DX -> {
                    val dx = difficulties?.dx
                    if (dx != null && idx < dx.size) dx[idx].version else 0
                }
                MaimaiEnums.SongType.STANDARD -> {
                    val std = difficulties?.standard
                    if (std != null && idx < std.size) std[idx].version else 0
                }
                MaimaiEnums.SongType.UTAGE -> {
                    val utg = difficulties?.utage
                    if (utg != null && idx < utg.size) utg[idx].version else 0
                }
            }
        }

        fun getNoteTotal(
            title: String,
            diffculty: MaimaiEnums.Difficulty,
            type: MaimaiEnums.SongType
        ): Int {
            val difficulties = MAIMAI_SONG_LIST.find { it.title == title }?.difficulties
            val idx = diffculty.diffIndex
            return when (type) {
                MaimaiEnums.SongType.DX -> {
                    val dx = difficulties?.dx
                    if (dx != null && idx < dx.size) dx[idx].notes?.total ?: 0 else 0
                }
                MaimaiEnums.SongType.STANDARD -> {
                    val std = difficulties?.standard
                    if (std != null && idx < std.size) std[idx].notes?.total ?: 0 else 0
                }
                MaimaiEnums.SongType.UTAGE -> {
                    val utg = difficulties?.utage
                    if (utg != null && idx < utg.size) utg[idx].notes?.total ?: 0 else 0
                }
            }
        }

        fun getDxStar(
            noteTotal: Int,
            dxScore: Int
        ): Int {
            val value = dxScore.toDouble() / (noteTotal * 3)
            return when {
                value < 0.85 -> 0
                value < 0.9 -> 1
                value < 0.93 -> 2
                value < 0.95 -> 3
                value < 0.97 -> 4
                else -> 5
            }
        }

        fun getDxStarBitmap(dxStar: Int): Int? {
            return when(dxStar) {
                1 -> return R.drawable.ic_maimai_dxscore_01
                2 -> return R.drawable.ic_maimai_dxscore_02
                3 -> return R.drawable.ic_maimai_dxscore_03
                4 -> return R.drawable.ic_maimai_dxscore_04
                5 -> return R.drawable.ic_maimai_dxscore_05
                else -> return null
            }
        }

        fun songHasTypeDifficulty(title: String, type: MaimaiEnums.SongType): Boolean {
            val difficulty = MAIMAI_SONG_LIST.find { it.title == title }?.difficulties
            if (type == MaimaiEnums.SongType.STANDARD) {
                return difficulty?.standard?.isNotEmpty() == true
            } else if (type == MaimaiEnums.SongType.DX) {
                return difficulty?.dx?.isNotEmpty() == true
            }
            return false
        }
    }
}