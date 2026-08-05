package io.github.teriver.maiupload.core

import io.github.teriver.maiupload.core.config.ConfigStorage

interface ProberContext {
    fun requireConfig(): ConfigStorage
}