package io.github.otoha.mobile

import android.content.Context
import android.os.Build
import android.util.AtomicFile
import java.io.File
import java.io.PrintWriter
import java.io.Writer
import java.time.Instant
import kotlin.system.exitProcess

internal data class DeviceDiagnostics(
    val manufacturer: String,
    val model: String,
    val sdk: Int,
    val androidRelease: String,
    val appVersion: String,
    val versionCode: Long,
)

/** Pure formatting helpers so byte limits and exception-chain output can be tested on the JVM. */
internal object CrashReportFormatter {
    const val MAX_BYTES = 64 * 1024
    private const val TRUNCATION = "\n[报告超过长度限制，已截断]\n"

    fun deviceInfo(device: DeviceDiagnostics): String = buildString {
        appendLine("Otoha 本地诊断")
        appendLine("应用版本：${device.appVersion} (${device.versionCode})")
        appendLine("厂商：${device.manufacturer}")
        appendLine("型号：${device.model}")
        appendLine("Android：${device.androidRelease} / SDK ${device.sdk}")
        appendLine("报告只保存在本机，不会自动上传。")
    }

    fun exceptionReport(device: DeviceDiagnostics, threadName: String, error: Throwable,
        timestampMillis: Long): String {
        val output = BoundedWriter(MAX_BYTES)
        val writer = PrintWriter(output)
        writer.print(deviceInfo(device))
        writer.println("异常时间（UTC）：${Instant.ofEpochMilli(timestampMillis)}")
        writer.println("线程：$threadName")
        writer.println()
        error.printStackTrace(writer)
        writer.flush()
        return limitUtf8(output.toString(), wasTruncated = output.truncated)
    }

    fun limitUtf8(text: String, maxBytes: Int = MAX_BYTES, wasTruncated: Boolean = false): String {
        require(maxBytes >= TRUNCATION.toByteArray(Charsets.UTF_8).size)
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes && !wasTruncated) return text
        val suffix = TRUNCATION.toByteArray(Charsets.UTF_8)
        var end = minOf(bytes.size, maxBytes - suffix.size)
        // Do not split a UTF-8 code point at the truncation boundary.
        while (end > 0 && end < bytes.size && (bytes[end].toInt() and 0xc0) == 0x80) end--
        return String(bytes, 0, end, Charsets.UTF_8) + TRUNCATION
    }

    private class BoundedWriter(private val maxChars: Int) : Writer() {
        private val text = StringBuilder()
        var truncated = false
            private set
        override fun write(buffer: CharArray, offset: Int, count: Int) {
            val take = minOf(count, maxChars - text.length)
            if (take > 0) text.append(buffer, offset, take)
            if (take < count) truncated = true
        }
        override fun write(value: String, offset: Int, count: Int) {
            val take = minOf(count, maxChars - text.length)
            if (take > 0) text.append(value, offset, offset + take)
            if (take < count) truncated = true
        }
        override fun flush() = Unit
        override fun close() = Unit
        override fun toString(): String = text.toString()
    }
}

/** Always preserves Android's original fatal-exception handling; never restarts the app. */
internal class LocalCrashHandler(
    private val record: (Thread, Throwable) -> Unit,
    private val previous: Thread.UncaughtExceptionHandler?,
    private val terminateFallback: () -> Unit = {
        android.os.Process.killProcess(android.os.Process.myPid())
        exitProcess(10)
    },
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, error: Throwable) {
        try { record(thread, error) } catch (_: Throwable) {
            // A full disk, inaccessible storage or OOM must not replace the original exception.
        }
        if (previous != null) previous.uncaughtException(thread, error) else terminateFallback()
    }
}

internal class CrashReports(private val context: Context) {
    private val file by lazy { AtomicFile(File(context.noBackupFilesDir, "last-crash-report.txt")) }

    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is LocalCrashHandler) return
        Thread.setDefaultUncaughtExceptionHandler(LocalCrashHandler(::record, previous))
    }

    @Synchronized
    fun record(thread: Thread, error: Throwable) {
        val report = CrashReportFormatter.exceptionReport(device(), thread.name, error, System.currentTimeMillis())
        val stream = file.startWrite()
        try {
            stream.write(report.toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (failure: Throwable) {
            file.failWrite(stream)
            throw failure
        }
    }

    @Synchronized
    fun readLatest(): String? = runCatching {
        file.openRead().use { input ->
            val bytes = ByteArray(CrashReportFormatter.MAX_BYTES)
            var size = 0
            while (size < bytes.size) {
                val count = input.read(bytes, size, bytes.size - size)
                if (count < 0) break
                size += count
            }
            String(bytes, 0, size, Charsets.UTF_8).takeIf(String::isNotBlank)
        }
    }.getOrNull()

    @Synchronized
    fun clear(): Boolean = runCatching {
        file.delete()
        val base = file.baseFile
        !base.exists() && !File(base.path + ".bak").exists() && !File(base.path + ".new").exists()
    }.getOrDefault(false)

    fun diagnosticText(): String = readLatest() ?: CrashReportFormatter.deviceInfo(device()) +
        "\n尚无本地未捕获异常记录。\n"

    @Suppress("DEPRECATION")
    private fun device(): DeviceDiagnostics {
        val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        val code = info?.let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong() } ?: 0L
        return DeviceDiagnostics(Build.MANUFACTURER, Build.MODEL, Build.VERSION.SDK_INT,
            Build.VERSION.RELEASE, info?.versionName ?: "未知", code)
    }
}
