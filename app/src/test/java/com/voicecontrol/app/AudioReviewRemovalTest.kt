package com.voicecontrol.app

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 用户移除声音二审：旧开关不能重启请求，残缺文字也不能再被声音反转。 */
class AudioReviewRemovalTest {
    @Test fun `旧偏好开启也没有任何声音请求`() {
        for (savedEnabled in listOf(false, true)) {
            val enabled = AudioReviewRequest.runtimeEnabled(savedEnabled)
            assertFalse(enabled)
            var calls = 0
            for (text in listOf("增加音量", "机音量", "点击十八", "最近任务", "音量然后退出")) {
                val plan = AudioReviewRequest.plan(text, enabled, true, false, false, false)
                assertNull(plan.execute { calls++; AudioDecisionOutcome("dec", .999f, 0L, null) })
            }
            assertEquals(0, calls)
        }
    }

    @Test fun `移除后残缺候选只按文字方向派发一次`() {
        val json = listOf(File("src/main/assets/commands.json"), File("app/src/main/assets/commands.json"))
            .first { it.isFile }.readText(Charsets.UTF_8)
        val matcher = CommandMatcher.fromJson(json)
        for ((word, action, contrary) in listOf(Triple("增加音量", "volume_up", "dec"),
            Triple("降低音量", "volume_down", "inc"))) {
            val match = matcher.matchStrict(word)!!.copy(method = "pinyin_fuzzy")
            val calls = mutableListOf<String>()
            val result = AudioDecisionRouting.dispatchVolume("机音量", match,
                AudioReviewRequest.runtimeEnabled(true), AudioDecisionOutcome(contrary, .999f, 0L, null), false) {
                calls.add(it); true
            }
            assertEquals(listOf(action), calls)
            assertEquals(action, result.resolution.action)
        }
    }

    @Test fun `旧推理入口直接返回已移除且不加载模型`() {
        val result = AudioDecision.decideDetailed(FloatArray(32000))
        assertEquals("feature_removed", result.fallbackReason)
        assertNull(result.decision)
        assertNull(result.m6Top)
        assertNull(result.numTop)
        assertFalse(AudioDecision.isReady())
    }
}
