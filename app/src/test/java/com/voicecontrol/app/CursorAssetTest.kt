package com.voicecontrol.app

import java.io.File
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CursorAssetTest {
    @Test fun `设备资产与冻结训练输出同源`() {
        val assets = File("src/main/assets")
        val model = OptionalExperimentFiles.requireFile("src/main/assets/cursor_review_head.onnx")
        val meta = JSONObject(OptionalExperimentFiles.requireFile("src/main/assets/cursor_review_meta.json").readText(Charsets.UTF_8))
        val digest = MessageDigest.getInstance("SHA-256").digest(model.readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals(meta.getString("model_sha256"), digest)
        assertEquals(meta.getLong("bytes"), model.length())
        assertEquals(listOf("text_cursor_left", "text_cursor_right", "other"),
            meta.getJSONArray("labels").let { a -> (0 until a.length()).map { a.getString(it) } })
        assertEquals(CursorReviewPolicy.MIN_CONFIDENCE, meta.getDouble("threshold").toFloat(), 0f)
        assertEquals(CursorReviewPolicy.MIN_MARGIN, meta.getDouble("margin").toFloat(), 0f)
    }

    @Test fun `新增能力文案不会扩大旧音量或最近任务改判范围`() {
        assertTrue(CursorReviewPolicy.ENABLED)
        assertEquals(CursorReviewPolicy.ACTIONS, AcousticReviewRegistry.cursorRecovery.actions)
        assertEquals(setOf("open_recents"), AcousticReviewRegistry.m6Recovery.actions)
        assertEquals(setOf("volume_up", "volume_down"), AcousticReviewRegistry.modules.single().actions)
        assertFalse(AcousticReviewRegistry.coversAction("text_cursor_left"))
        assertTrue(AcousticReviewRegistry.userScopeSummary(includeM6 = true).contains("光标左移 / 右移"))
        assertFalse(AcousticReviewRegistry.userScopeSummary(includeM6 = false).contains("光标"))
    }
}
