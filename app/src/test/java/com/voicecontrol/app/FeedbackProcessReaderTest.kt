package com.voicecontrol.app

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class FeedbackProcessReaderTest {
    @Test fun actualProcessPipeIsDrainedWithoutBlockingAndIncludesMergedStderr() {
        val executable = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
        val java = java.io.File(System.getProperty("java.home"), "bin/$executable").absolutePath
        val result = FeedbackProcessReader.read(listOf(java, "-version"), timeoutMs = 10_000)
        assertFalse(result.timedOut)
        assertFalse(result.truncated)
        assertTrue(result.text.contains("version"))
    }

    private class FakeProcess(text: String = "", val exit: Int? = 0) : Process() {
        var destroyed = false
        var inputClosed = false
        var errorClosed = false
        var outputClosed = false
        val input = object : ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)) {
            override fun close() { inputClosed = true; super.close() }
        }
        val error = object : ByteArrayInputStream(byteArrayOf()) {
            override fun close() { errorClosed = true; super.close() }
        }
        val output = object : ByteArrayOutputStream() {
            override fun close() { outputClosed = true; super.close() }
        }
        override fun getInputStream() = input
        override fun getErrorStream() = error
        override fun getOutputStream() = output
        override fun exitValue(): Int = exit ?: throw IllegalThreadStateException()
        override fun waitFor(): Int = throw AssertionError("不应无期限等待进程")
        override fun destroy() { destroyed = true }
        fun assertClosed() {
            assertTrue(destroyed)
            assertTrue(inputClosed)
            assertTrue(errorClosed)
            assertTrue(outputClosed)
        }
    }

    @Test fun readsWholeUtf8OutputAndClosesAllProcessResources() {
        val process = FakeProcess("机器听到：向上滑动\n执行完成")
        val result = FeedbackProcessReader.read(listOf("logcat"), launch = { process })
        assertEquals("机器听到：向上滑动\n执行完成", result.text)
        assertFalse(result.timedOut)
        assertFalse(result.truncated)
        process.assertClosed()
    }

    @Test fun hungProcessHasBoundedTimeAndPreservesCollectedOutput() {
        val process = FakeProcess("已取得的日志", exit = null)
        var time = 0L
        val result = FeedbackProcessReader.read(listOf("logcat"), timeoutMs = 30, launch = { process },
            nanoTime = { time }, pause = { time += it * 1_000_000 })
        assertTrue(result.timedOut)
        assertEquals("已取得的日志", result.text)
        assertEquals(30_000_000L, time)
        process.assertClosed()
    }

    @Test fun cancellationInterruptsWaitAndStillClosesProcess() {
        val process = FakeProcess(exit = null)
        try {
            FeedbackProcessReader.read(listOf("logcat"), launch = { process }, pause = { throw InterruptedException() })
            fail("应传播取消，不能继续生成旧报告")
        } catch (_: InterruptedException) {
            process.assertClosed()
        }
    }

    @Test fun excessiveOutputIsBoundedAndMarkedAsTruncated() {
        val process = FakeProcess("a".repeat(2_000), exit = null)
        val result = FeedbackProcessReader.read(listOf("logcat"), maxBytes = 100, launch = { process })
        assertEquals(100, result.text.length)
        assertTrue(result.truncated)
        process.assertClosed()
    }

    @Test fun processFailureIsReportedAndCleanedUp() {
        val process = FakeProcess("permission denied", exit = 1)
        try {
            FeedbackProcessReader.read(listOf("logcat"), launch = { process })
            fail("应报告日志读取失败")
        } catch (error: IOException) {
            assertTrue(error.message.orEmpty().contains("permission denied"))
            process.assertClosed()
        }
    }
}
