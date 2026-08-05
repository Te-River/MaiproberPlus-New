package io.github.teriver.maiupload.core.utils

import io.github.teriver.maiupload.Application
import io.github.teriver.maiupload.ui.compose.scores.resources
import io.github.teriver.maiupload.ui.compose.sync.FileDownloadMeta

fun checkResourceComplete(): List<FileDownloadMeta> {
    val returnList = arrayListOf<FileDownloadMeta>()
    resources.forEach {
        if (!Application.application.filesDir.resolve(it.fileSavePath).resolve(it.fileName).exists()) {
            returnList.add(it)
        }
    }
    return returnList
}
