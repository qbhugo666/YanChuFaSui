package com.voicecontrol.app

import org.junit.Assert.*
import org.junit.Test

/** 测生产身份发布入口与数字派发器；模拟声音候选只证明接线，不代表真人声学准确率。 */
class RecognitionSessionIdentityTest {
    private val snapshot = NumberReviewContext.Snapshot(7, "test.app",
        (1..30).map { "$it@target$it" })
    private val audio = listOf("18" to .99f)

    private inner class Session {
        var generation = 0
        var tag = ""
        var sequence = 69
        val dispatcher = NumberTapDispatcher()
        val taps = mutableListOf<Int>()

        fun commit(gen: Int, nonce: String): RecognitionSessionIdentity {
            generation = gen
            return RecognitionSessionIdentity.commit(gen, nonce) {
                tag = it.tag
                sequence = 0
            }
        }

        fun next(identity: RecognitionSessionIdentity) = NumberReviewContext.Request(
            identity.utteranceId(++sequence), identity.generation, snapshot)

        fun live() = NumberReviewContext.Live("$tag-u$sequence", generation,
            true, true, true, snapshot)

        fun dispatch(request: NumberReviewContext.Request, live: NumberReviewContext.Live = live()) =
            dispatcher.dispatch(8, "点击八", audio, request, live,
                confirmation = audio) { taps.add(it); true }
    }

    @Test fun `首次线程立即开始_身份先发布_数字复核不再误拒为旧句`() {
        val session = Session()
        // 旧顺序的反例：线程先复制空标签。保留守卫应拒绝这个结果，不能靠放宽守卫救回。
        val oldTagCapturedBeforeCommit = session.tag
        val identity = session.commit(1, "first")
        val oldRequest = NumberReviewContext.Request("$oldTagCapturedBeforeCommit-u1", 1, snapshot)
        val request = session.next(identity)
        val oldResult = session.dispatch(oldRequest)
        assertEquals("stale_utterance", oldResult.rejection)
        assertEquals(8, oldResult.number)

        val fixedResult = session.dispatch(request)
        assertEquals("g1-first-u1", request.uid)
        assertNull(fixedResult.rejection)
        assertEquals(18, fixedResult.number)
        assertNotNull(fixedResult.correction)
        assertEquals(listOf(8, 18), session.taps)
    }

    @Test fun `连续句不重置编号_快速重开后迟到旧身份仍被拒绝`() {
        val session = Session()
        val firstSession = session.commit(1, "first")
        val first = session.next(firstSession)
        assertTrue(session.dispatch(first).dispatched)
        val duplicate = session.dispatch(first)
        assertEquals("already_dispatched", duplicate.rejection)
        assertFalse(duplicate.attempted)

        val second = session.next(firstSession)
        assertEquals("g1-first-u2", second.uid)
        assertTrue(session.dispatch(second).dispatched)
        assertEquals(listOf(18, 18), session.taps)

        val newSession = session.commit(2, "second")
        val current = session.next(newSession)
        assertEquals("g2-second-u1", current.uid)
        // 延迟开始的旧线程仍持有旧身份，不得从全局借用新会话的代际/标签。
        assertEquals("g1-first-u1", firstSession.utteranceId(1))
        assertEquals(1, firstSession.generation)
        assertEquals("stale_utterance", NumberReviewContext.rejection(first, session.live()))
        assertEquals("stale_session", NumberReviewContext.rejection(
            first.copy(uid = current.uid), session.live()))
        assertTrue(session.dispatch(current).dispatched)
        assertEquals(listOf(18, 18, 18), session.taps)
    }

    @Test fun `修身份仍保留关闭增强_模式_旧句与页面守卫`() {
        val session = Session()
        val identity = session.commit(1, "first")
        val request = session.next(identity)
        val live = session.live()
        val cases = listOf(
            "enhanced_off" to live.copy(enabled = false),
            "protected_mode" to live.copy(normalMode = false),
            "session_inactive" to live.copy(active = false),
            "stale_utterance" to live.copy(uid = identity.utteranceId(2)),
            "snapshot_changed" to live.copy(snapshot = snapshot.copy(windowId = 8)),
        )
        for ((reason, changedLive) in cases) {
            val result = session.dispatch(request, changedLive)
            assertEquals(reason, result.rejection)
            assertNull(result.correction)
            assertEquals(8, result.number)
        }
        assertEquals(List(cases.size) { 8 }, session.taps)
    }
}
