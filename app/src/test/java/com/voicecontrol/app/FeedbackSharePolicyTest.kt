package com.voicecontrol.app

import org.junit.Assert.*
import org.junit.Test

class FeedbackSharePolicyTest {
    @Test fun attachmentNamesAreNotPathsOrPrivateFilesAndLargeUnicodeReportsUseStream() {
        assertTrue(FeedbackSharePolicy.validFilename("speech-feedback-${"a".repeat(32)}.txt"))
        for (value in listOf("../usage_log.json", "speech-feedback-../usage_log.json", "/speech-feedback-${"a".repeat(32)}.txt",
            "speech-feedback-${"a".repeat(32)}.txt/extra", "speech-feedback-%2e%2e.txt", "audio.wav", ""))
            assertFalse(value, FeedbackSharePolicy.validFilename(value))
        assertFalse(FeedbackSharePolicy.needsFile("一条小反馈"))
        assertFalse(FeedbackSharePolicy.needsFile("a".repeat(FeedbackSharePolicy.INLINE_BYTES)))
        assertTrue(FeedbackSharePolicy.needsFile("a".repeat(FeedbackSharePolicy.INLINE_BYTES + 1)))
        assertTrue(FeedbackSharePolicy.needsFile("中文反馈".repeat(FeedbackSharePolicy.INLINE_BYTES / 5)))
    }
}
