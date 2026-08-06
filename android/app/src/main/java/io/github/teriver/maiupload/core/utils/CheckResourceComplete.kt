package io.github.teriver.maiupload.core.utils

import io.github.teriver.maiupload.Application
import io.github.teriver.maiupload.ui.compose.scores.resources
import io.github.teriver.maiupload.ui.compose.sync.FileDownloadMeta

fun checkResourceComplete(): List<FileDownloadMeta> {
    val returnList = arrayListOf<FileDownloadMeta>()
    resources.forEach {
        val file = Application.application.filesDir.resolve(it.fileSavePath).resolve(it.fileName)
        // 文件不存在或为 0 字节（下载中断留下的空文件）都算未完成，需重新下载
        if (!file.exists() || file.length() == 0L) {
            returnList.add(it)
        }
    }
    return returnList
}
