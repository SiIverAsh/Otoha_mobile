package io.github.otoha.mobile

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashReportsTest {
    private val device = DeviceDiagnostics(
        manufacturer = "ExampleMaker", model = "真机 Model 1", sdk = 26,
        androidRelease = "8.0.0", appVersion = "0.1.1", versionCode = 2,
    )

    @Test fun `report retains device details timestamp thread and original cause chain`() {
        val cause = IOException("底层网络错误").apply {
            stackTrace = arrayOf(StackTraceElement("example.Transport", "read", "Transport.kt", 12))
        }
        val error = IllegalStateException("启动失败", cause).apply {
            stackTrace = arrayOf(StackTraceElement("example.MainActivity", "onCreate", "MainActivity.kt", 42))
        }
        val report = CrashReportFormatter.exceptionReport(device, "main-线程", error, 0L)

        assertTrue(report.contains("应用版本：0.1.1 (2)"))
        assertTrue(report.contains("厂商：ExampleMaker"))
        assertTrue(report.contains("型号：真机 Model 1"))
        assertTrue(report.contains("Android：8.0.0 / SDK 26"))
        assertTrue(report.contains("1970-01-01T00:00:00Z"))
        assertTrue(report.contains("线程：main-线程"))
        assertTrue(report.contains("java.lang.IllegalStateException: 启动失败"))
        assertTrue(report.contains("MainActivity.kt:42"))
        assertTrue(report.contains("Caused by: java.io.IOException: 底层网络错误"))
        assertTrue(report.contains("Transport.kt:12"))
        assertTrue(report.toByteArray(Charsets.UTF_8).size <= CrashReportFormatter.MAX_BYTES)
    }

    @Test fun `UTF8 truncation keeps complete multibyte characters within 64 KiB`() {
        val original = "耳朵🙂".repeat(20_000)
        // Exercise boundaries inside both three-byte CJK and four-byte supplementary characters.
        for (maxBytes in (CrashReportFormatter.MAX_BYTES - 9)..CrashReportFormatter.MAX_BYTES) {
            val truncated = CrashReportFormatter.limitUtf8(original, maxBytes)
            assertTrue(truncated.toByteArray(Charsets.UTF_8).size <= maxBytes)
            assertTrue(truncated.contains("已截断"))
            assertValidUnicode(truncated)
        }
    }

    @Test fun `huge exception report remains bounded without replacement characters`() {
        val error = IllegalStateException("中文🙂".repeat(100_000))
        val report = CrashReportFormatter.exceptionReport(device, "main", error, 0L)
        assertTrue(report.toByteArray(Charsets.UTF_8).size <= CrashReportFormatter.MAX_BYTES)
        assertTrue(report.contains("型号：真机 Model 1"))
        assertTrue(report.contains("java.lang.IllegalStateException:"))
        assertTrue(report.contains("已截断"))
        assertValidUnicode(report)
    }

    @Test fun `short UTF8 reports are not changed or marked as truncated`() {
        val original = "本机诊断 🙂\nNo crash has been recorded.\n"
        assertEquals(original, CrashReportFormatter.limitUtf8(original))
    }

    @Test fun `recording failure still delegates the same thread and original throwable`() {
        val thread = Thread("fatal-thread")
        val original = IllegalArgumentException("original failure")
        val order = mutableListOf<String>()
        var recordedThread: Thread? = null
        var recordedError: Throwable? = null
        var delegatedThread: Thread? = null
        var delegatedError: Throwable? = null
        var fallbackCalled = false
        val previous = Thread.UncaughtExceptionHandler { actualThread, actualError ->
            order += "delegate"
            delegatedThread = actualThread
            delegatedError = actualError
        }
        val handler = LocalCrashHandler(record = { actualThread, actualError ->
            order += "record"
            recordedThread = actualThread
            recordedError = actualError
            // Errors, including OOM during diagnostics, must not replace the original crash.
            throw AssertionError("cannot write diagnostic report")
        }, previous = previous, terminateFallback = { fallbackCalled = true })

        handler.uncaughtException(thread, original)

        assertEquals(listOf("record", "delegate"), order)
        assertSame(thread, recordedThread)
        assertSame(original, recordedError)
        assertSame(thread, delegatedThread)
        assertSame(original, delegatedError)
        assertFalse(fallbackCalled)
    }

    @Test fun `missing previous handler invokes fallback after recording`() {
        val order = mutableListOf<String>()
        val original = IllegalStateException("fatal")
        var recordedError: Throwable? = null
        val handler = LocalCrashHandler(record = { _, error ->
            order += "record"
            recordedError = error
        }, previous = null, terminateFallback = { order += "terminate" })

        handler.uncaughtException(Thread("fatal-thread"), original)

        assertSame(original, recordedError)
        assertEquals(listOf("record", "terminate"), order)
    }

    @Test fun `missing previous handler still invokes fallback when recording fails`() {
        var fallbackCalls = 0
        val handler = LocalCrashHandler(record = { _, _ -> throw IOException("disk full") },
            previous = null, terminateFallback = { fallbackCalls++ })

        handler.uncaughtException(Thread("fatal-thread"), IllegalStateException("original"))

        assertEquals(1, fallbackCalls)
    }

    private fun assertValidUnicode(text: String) {
        assertFalse(text.contains('\uFFFD'))
        assertFalse(text.contains('?')) // Java's UTF-8 encoder replaces isolated UTF-16 surrogates with '?'.
        var index = 0
        while (index < text.length) {
            val char = text[index]
            if (Character.isHighSurrogate(char)) {
                assertTrue(index + 1 < text.length && Character.isLowSurrogate(text[index + 1]))
                index += 2
            } else {
                assertFalse(Character.isLowSurrogate(char))
                index++
            }
        }
        val bytes = text.toByteArray(Charsets.UTF_8)
        assertEquals(text, Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString())
    }
}
