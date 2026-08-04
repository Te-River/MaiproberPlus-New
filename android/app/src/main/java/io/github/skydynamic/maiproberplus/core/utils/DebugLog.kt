package io.github.skydynamic.maiproberplus.core.utils

import android.util.Log
import io.github.skydynamic.maiproberplus.Application
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全 app debug 级日志：像 logcat 那样把 app 所有活动（提示/网络/报错/崩溃）
 * 全量镜像到本地 debug.log，便于用户翻一个文件复盘整个 app 行为。
 *
 * 与 ErrorLog（error.log 报错专用）/ sync.log（Rival 同步专用）区分：
 * debug.log 是全量 debug 级，含所有 Log.* 镜像 + 网络请求/响应 + 生命周期。
 *
 * 文件结构：每条一行，含时间戳 / 级别 / 线程 / TAG / message / stack trace（多行缩进）。
 * 单文件最大 [MAX_FILE_BYTES]，超限自动回滚保留尾部一半重写避免无限膨胀。
 */
object DebugLog {
    private const val FILE_NAME = "debug.log"
    private const val MAX_FILE_BYTES = 5 * 1024 * 1024L  // 5MB，debug 级比 error 级宽容

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.CHINA)
    private val lock = Any()

    /** 记一条 debug 级日志到 debug.log + logcat。
     * @param level 日志级："V"/"D"/"I"/"W"/"E"（对应 Log.v/d/i/w/e）
     * @param tag 来源 TAG（如类名）
     * @param message 可读描述
     * @param throwable 异常（可选，会写完整 stack trace）
     */
    fun log(level: String = "D", tag: String, message: String, throwable: Throwable? = null) {
        try {
            when (level) {
                "V" -> Log.v(tag, message, throwable)
                "I" -> Log.i(tag, message, throwable)
                "W" -> Log.w(tag, message, throwable)
                "E" -> Log.e(tag, message, throwable)
                else -> Log.d(tag, message, throwable)
            }
            val entry = buildString {
                append("[")
                append(dateFormat.format(Date()))
                append("] [")
                append(level)
                append("] [")
                append(Thread.currentThread().name)
                append("] [")
                append(tag)
                append("] ")
                append(message)
                if (throwable != null) {
                    append("\n")
                    val sw = StringWriter()
                    throwable.printStackTrace(PrintWriter(sw))
                    sw.toString().lineSequence().forEach { line ->
                        append("  ").append(line).append("\n")
                    }
                } else {
                    append("\n")
                }
            }
            writeEntry(entry)
        } catch (_: Throwable) {
            // 记录本身报错不再向上抛，避免把 app 搞崩
        }
    }

    /** 网络请求/响应专用：带请求方法/URL/状态码/响应体摘要的结构化记录。 */
    fun logNetwork(method: String, url: String, statusCode: Int? = null, bodySummary: String? = null, throwable: Throwable? = null) {
        val msg = buildString {
            append(method).append(" ").append(url)
            if (statusCode != null) append(" → ").append(statusCode)
            if (!bodySummary.isNullOrBlank()) {
                // 响应体摘要限 500 字避免膨胀
                val trimmed = bodySummary.take(500)
                append("\n  body: ").append(trimmed)
                if (bodySummary.length > 500) append("… (${bodySummary.length - 500} 字截断)")
            }
        }
        log("D", "Network", msg, throwable)
    }

    private fun writeEntry(entry: String) {
        synchronized(lock) {
            try {
                // application 未初始化（早于 onCreate / 静态初始化期）时降级跳过文件写，
                // logcat 仍记录——避免 filesDir 访问崩把 app 搞崩
                val file = try { logFile() } catch (_: Throwable) { return }
                if (file.length() > MAX_FILE_BYTES) {
                    val bytes = file.readBytes()
                    val keep = bytes.copyOfRange((bytes.size / 2), bytes.size)
                    file.writeBytes(keep)
                }
                file.appendText(entry)
            } catch (_: Throwable) {
                // 写文件失败静默跳过，logcat 仍记录
            }
        }
    }

    private fun logFile(): File =
        File(Application.application.filesDir, FILE_NAME)

    /** 返回 debug.log 绝对路径（filesDir/debug.log），便用户翻全 app 活动复盘。 */
    fun logFilePath(): String = logFile().absolutePath
}
