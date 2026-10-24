package com.voicecontrol.app

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FeedbackFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun smallReportStaysInlineAndLargeReportIsCompleteUtf8InWhitelistedFile() {
        val directory = temporary.newFolder()
        val files = FeedbackFiles(directory)
        assertNull(files.prepare("短反馈").attachment)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
        val text = "中文反馈\n".repeat(20_000)
        val report = files.prepare(text)
        assertNotNull(report.attachment)
        assertTrue(FeedbackSharePolicy.validFilename(report.attachment!!.name))
        assertEquals(text, report.attachment.readText(Charsets.UTF_8))
        FeedbackFiles.discard(report)
        assertFalse(report.attachment.exists())
    }

    @Test fun pruningKeepsAtMostFiveRecentReportsAndLeavesOtherFilesAlone() {
        val directory = temporary.newFolder()
        val now = System.currentTimeMillis()
        val reports = (0..6).map { index ->
            File(directory, "speech-feedback-${index.toString(16).padStart(32, '0')}.txt").apply {
                writeText("反馈 $index")
                assertTrue(setLastModified(now - index * 1_000L))
            }
        }
        val old = File(directory, "speech-feedback-${"f".repeat(32)}.txt").apply {
            writeText("过期反馈")
            assertTrue(setLastModified(now - FeedbackSharePolicy.RETENTION_MS - 1))
        }
        val other = File(directory, "usage_log.json").apply { writeText("不要删除") }
        FeedbackFiles(directory).prune(now)
        assertTrue(reports.take(5).all { it.exists() })
        assertTrue(reports.drop(5).none { it.exists() })
        assertFalse(old.exists())
        assertTrue(other.exists())
    }

    @Test fun newlyPreparedAttachmentIsRetainedEvenIfOlderFilesHaveFutureTimestamps() {
        val directory = temporary.newFolder()
        repeat(5) { index ->
            File(directory, "speech-feedback-${index.toString(16).padStart(32, '0')}.txt").apply {
                writeText("旧反馈")
                assertTrue(setLastModified(System.currentTimeMillis() + 60_000))
            }
        }
        val report = FeedbackFiles(directory).prepare("a".repeat(FeedbackSharePolicy.INLINE_BYTES + 1))
        assertTrue(report.attachment!!.exists())
        assertEquals(FeedbackSharePolicy.MAX_FILES, directory.listFiles()!!.size)
    }
}
