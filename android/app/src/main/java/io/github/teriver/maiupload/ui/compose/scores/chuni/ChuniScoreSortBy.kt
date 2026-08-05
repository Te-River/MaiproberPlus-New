package io.github.teriver.maiupload.ui.compose.scores.chuni

import kotlinx.serialization.Serializable

@Serializable
enum class ChuniScoreSortBy(val displayName: String) {
    Level("等级"),
    Score("分数"),
    Rating("Rating"),
    Difficulty("难度"),
}
