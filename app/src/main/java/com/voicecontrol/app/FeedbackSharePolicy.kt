package com.voicecontrol.app

/** 大反馈走文件，避免把全部二审账目塞入系统分享的 Binder 消息。 */
internal object FeedbackSharePolicy {
    const val INLINE_BYTES = 96 * 1024
    const val RETENTION_MS = 48 * 60 * 60 * 1000L
    const val MAX_FILES = 5
    private val filename = Regex("^speech-feedback-[a-f0-9]{32}\\.txt$")
    fun validFilename(name: String): Boolean = filename.matches(name)
    fun needsFile(report: String): Boolean = report.toByteArray(Charsets.UTF_8).size > INLINE_BYTES
}
