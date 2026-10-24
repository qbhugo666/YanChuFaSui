package com.voicecontrol.app

import java.io.ByteArrayOutputStream
import java.io.IOException

/** logcat 使用非阻塞可读量轮询；兼容 API 24，不依赖 API 26 的 waitFor(timeout)。 */
internal object FeedbackProcessReader {
    internal data class Output(val text: String, val timedOut: Boolean = false, val truncated: Boolean = false)

    fun read(
        command: List<String>,
        timeoutMs: Long = 3_000,
        maxBytes: Int = 2 * 1024 * 1024,
        launch: (List<String>) -> Process = { ProcessBuilder(it).redirectErrorStream(true).start() },
        nanoTime: () -> Long = System::nanoTime,
        pause: (Long) -> Unit = Thread::sleep,
    ): Output {
        require(timeoutMs > 0 && maxBytes > 0)
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        val process = launch(command)
        try {
            val input = process.inputStream
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            val startedAt = nanoTime()
            while (true) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                if (nanoTime() - startedAt >= timeoutMs * 1_000_000L) {
                    return Output(bytes.toString("UTF-8"), timedOut = true)
                }
                val available = input.available()
                if (available > 0) {
                    val count = input.read(buffer, 0, minOf(available, buffer.size, maxBytes - bytes.size()))
                    if (count < 0) return Output(bytes.toString("UTF-8"))
                    bytes.write(buffer, 0, count)
                    if (bytes.size() == maxBytes) return Output(bytes.toString("UTF-8"), truncated = true)
                } else {
                    val exit = try { process.exitValue() } catch (_: IllegalThreadStateException) { null }
                    if (exit != null) {
                        // 再查一次，避免退出检查与最后一段输出到达之间的竞态。
                        if (input.available() > 0) continue
                        if (exit != 0) throw IOException("logcat exit=$exit ${bytes.toString("UTF-8")}")
                        return Output(bytes.toString("UTF-8"))
                    }
                    pause(10)
                }
            }
        } finally {
            runCatching { process.destroy() }
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.outputStream.close() }
        }
    }
}
