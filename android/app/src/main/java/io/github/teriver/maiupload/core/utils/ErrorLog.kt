package io.github.teriver.maiupload.core.utils

import android.content.Context
import android.os.Build
import android.util.Log
import io.github.teriver.maiupload.Application
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 报错记录到本地 log 文件（filesDir/error.log）。
 * 全 App 报错（网络错误、API 异常、未捕获崩溃）都经 [logError] 写入，
 * 便于用户反馈时附带日志复现问题。
 *
 * 文件结构：每条一行，含时间戳 / 线程 / TAG / message / stack trace（多行缩进）。
 * 单文件最大 [MAX_FILE_BYTES]，超限自动回滚重写避免无限膨胀。
 */
object ErrorLog {
    private const val TAG = "ErrorLog"
    private const val FILE_NAME = "error.log"
    private const val SYNC_FILE_NAME = "sync.log"
    private const val MAX_FILE_BYTES = 2 * 1024 * 1024L  // 2MB

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.CHINA)

    /**
     * 记录一条报错到本地 log 文件 + logcat。
     * @param tag 来源 TAG（如类名）
     * @param message 可读描述
     * @param throwable 异常（可选，会写完整 stack trace）
     */
    fun logError(tag: String, message: String, throwable: Throwable? = null) {
        try {
            Log.e(tag, message, throwable)
            val entry = buildEntry(tag, message, throwable)
            writeEntry(entry, logFile())
        } catch (_: Throwable) {
            // 记录本身报错不再向上抛，避免把 app 搞崩
        }
    }

    /**
     * 记一条同步 log 到本地 sync.log + logcat（Info 级）。
     * Rival 同步期间所有提示/进度/报错都经此写到独立 sync.log，
     * 便于用户直接翻这一个文件复盘同步流程。
     * @param tag 来源 TAG（如 "Rival"/"Lxns"/"DivingFish"）
     * @param message 可读描述
     * @param level 日志级（"I"/"W"/"E"，默认 "I"）
     * @param throwable 异常（可选，会写完整 stack trace）
     */
    fun logSync(tag: String, message: String, level: String = "I", throwable: Throwable? = null) {
        try {
            when (level) {
                "W" -> Log.w(tag, message, throwable)
                "E" -> Log.e(tag, message, throwable)
                else -> Log.i(tag, message, throwable)
            }
            val entry = buildEntry(tag, message, throwable, level)
            writeEntry(entry, syncLogFile())
        } catch (_: Throwable) {
            // 记录本身报错不再向上抛
        }
    }

    private fun buildEntry(tag: String, message: String, throwable: Throwable?, level: String = "E"): String =
        buildString {
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
                // stack trace 缩进两格便于阅读
                sw.toString().lineSequence().forEach { line ->
                    append("  ").append(line).append("\n")
                }
            } else {
                append("\n")
            }
        }

    private fun writeEntry(entry: String, file: File) {
        synchronized(this) {
            try {
                // application 未初始化（早于 onCreate / 静态初始化期）时降级跳过文件写，
                // logcat 仍记录——避免 filesDir 访问崩把 app 搞崩
                if (file.length() > MAX_FILE_BYTES) {
                    val bytes = file.readBytes()
                    val keep = bytes.copyOfRange((bytes.size / 2), bytes.size)
                    file.writeBytes(keep)
                }
                file.appendText(entry)
            } catch (_: Throwable) {
                // 写文件失败（存储满/权限/application 未初始化）静默跳过，logcat 仍记录
            }
        }
    }

    /** 全局未捕获异常处理器：app 崩溃时把 stack trace 写本地 log 后再交回原 handler。 */
    fun installGlobalCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                logError("Crash", "Uncaught exception on ${thread.name}", throwable)
                // 附 app 版本信息便于复现
                val app = Application.application
                val version = try {
                    "appVersion=${app.packageManager.getPackageInfo(app.packageName, 0)?.let { it.versionName }} " +
                        "Build.VERSION.SDK_INT=${Build.VERSION.SDK_INT} " +
                        "Device=${Build.MODEL}"
                } catch (_: Throwable) { "" }
                if (version.isNotBlank()) logError("Crash", "Context: $version")
            } catch (_: Throwable) {}
            // 交回原 handler 让系统正常崩（避免吞崩溃让用户无感）
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun logFile(): File =
        File(Application.application.filesDir, FILE_NAME)

    private fun syncLogFile(): File =
        File(Application.application.filesDir, SYNC_FILE_NAME)

    /** 给设置页/调试入口用的：返回 log 文件绝对路径，方便用户分享。 */
    fun logFilePath(): String = logFile().absolutePath

    /** 返回同步 log 文件绝对路径（filesDir/sync.log），方便用户翻同步流程复盘。 */
    fun syncLogFilePath(): String = syncLogFile().absolutePath
}
