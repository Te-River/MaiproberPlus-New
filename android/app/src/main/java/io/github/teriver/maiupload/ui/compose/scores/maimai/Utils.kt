package io.github.teriver.maiupload.ui.compose.scores.maimai

import androidx.lifecycle.viewModelScope
import io.github.teriver.maiupload.core.data.maimai.MaimaiScoreManager.getMaimaiScoreCache
import io.github.teriver.maiupload.core.database.entity.MaimaiScoreEntity
import io.github.teriver.maiupload.core.utils.compareByDescending
import io.github.teriver.maiupload.ui.compose.scores.ScoreManagerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

fun refreshMaimaiScore(
    clearSearch: Boolean = false
) {
    ScoreManagerViewModel.viewModelScope.launch(Dispatchers.IO) {
        ScoreManagerViewModel.maimaiLoadedScores.clear()
        ScoreManagerViewModel.maimaiLoadedScores.addAll(
            getMaimaiScoreCache().sortedWith(MaimaiScoreSortComparator)
        )
        if (clearSearch) {
            ScoreManagerViewModel.maimaiSearchScores.clear()
            ScoreManagerViewModel.maimaiSearchText.value = ""
        } else {
            ScoreManagerViewModel.searchMaimaiScore()
        }
    }
}

val MaimaiScoreSortComparator: Comparator<MaimaiScoreEntity>
    get() = when (ScoreManagerViewModel.maimaiScoreSortBy.value) {
        MaimaiScoreSortBy.Level -> compareByDescending { it.level }
        MaimaiScoreSortBy.Achievement -> compareByDescending { it.achievement }
        MaimaiScoreSortBy.DxScore -> compareByDescending { it.dxScore }
        MaimaiScoreSortBy.Rating -> compareByDescending { it.rating }
        MaimaiScoreSortBy.Difficulty -> compareByDescending(
            {it.diff}, {it.level}, {it.achievement}
        )
    }
