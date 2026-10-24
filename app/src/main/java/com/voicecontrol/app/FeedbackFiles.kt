package com.voicecontrol.app

import java.io.File
import java.util.UUID

internal data class FeedbackReport(val text: String, val attachment: File? = null)

/** 只操作应用私有缓存中的白名单反馈文件；分享权限仍由原有只读 Provider 授予。 */
internal class FeedbackFiles(private val directory: File) {
    fun prepare(report: String): FeedbackReport {
        checkCancellation()
        prune()
        if (!FeedbackSharePolicy.needsFile(report)) return FeedbackReport(report)
        check(directory.isDirectory || directory.mkdirs()) { "无法创建反馈目录" }
        val file = File(directory, "speech-feedback-${UUID.randomUUID().toString().replace("-", "")}.txt")
        try {
            file.writeText(report, Charsets.UTF_8)
            checkCancellation()
            prune(keep = file)
            return FeedbackReport(report, file)
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
    }

    fun prune(now: Long = System.currentTimeMillis(), keep: File? = null) {
        val files = directory.listFiles()?.filter { it.isFile && FeedbackSharePolicy.validFilename(it.name) }
            ?.sortedWith(compareByDescending<File> { it == keep }.thenByDescending { it.lastModified() }).orEmpty()
        val cutoff = now - FeedbackSharePolicy.RETENTION_MS
        files.forEachIndexed { i, file ->
            if (i >= FeedbackSharePolicy.MAX_FILES || file.lastModified() < cutoff) file.delete()
        }
    }

    companion object {
        fun discard(report: FeedbackReport) { report.attachment?.delete() }

        fun checkCancellation() {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
        }
    }
}
