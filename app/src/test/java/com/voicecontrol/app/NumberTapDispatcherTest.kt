package com.voicecontrol.app

import org.junit.Assert.*
import org.junit.Test

class NumberTapDispatcherTest {
    private val snapshot = NumberReviewContext.Snapshot(1, "app", (1..20).map { "target$it" })
    private val req = NumberReviewContext.Request("g1-u1", 1, snapshot)
    private val live = NumberReviewContext.Live("g1-u1", 1, true, true, true, snapshot)
    private val sound = listOf("18" to .99f)

    @Test fun supplementalModelNeverReplacesExistingCorrection() {
        val r = NumberTapDispatcher().dispatch(8, "点击八", sound, req, live,
            listOf("6" to .999f), confirmation = sound) { true }
        assertEquals(18, r.number)
    }
    @Test fun supplementalOnlyAfterOldAbstentionAndStrongEvidence() {
        val r = NumberTapDispatcher().dispatch(8, "点击八", listOf("18" to .85f), req, live,
            listOf("18" to .999f), confirmation = listOf("18" to .999f)) { true }
        assertEquals(18, r.number)
        assertEquals(8, NumberTapDispatcher().dispatch(8, "点击八", listOf("18" to .85f), req, live,
            listOf("18" to .98f)) { true }.number)
        assertEquals(8, NumberTapDispatcher().dispatch(8, "点击八", null, req, live.copy(enabled = false),
            listOf("18" to .999f)) { true }.number)
    }

    @Test fun correctNumberBeforeFirstTapAndNoRetryOnFailure() {
        val calls = mutableListOf<Int>()
        val d = NumberTapDispatcher()
        val r = d.dispatch(8, "点击八", sound, req, live, confirmation = sound) { calls.add(it); false }
        assertEquals(listOf(18), calls); assertFalse(r.dispatched)
        val duplicate = d.dispatch(8, "点击八", sound, req, live) { calls.add(it); true }
        assertFalse(duplicate.attempted); assertEquals(listOf(18), calls)
    }
    @Test fun offDuringInferenceKeepsOriginalNumber() {
        val calls = mutableListOf<Int>()
        val r = NumberTapDispatcher().dispatch(8, "点击八", sound, req, live.copy(enabled = false)) { calls.add(it); true }
        assertEquals(listOf(8), calls); assertNull(r.correction)
    }
    @Test fun stalePageCannotAdoptDifferentNumber() {
        val calls = mutableListOf<Int>()
        val page = snapshot.copy(targets = snapshot.targets.reversed())
        val r = NumberTapDispatcher().dispatch(8, "点击八", sound, req, live.copy(snapshot = page)) { calls.add(it); true }
        assertEquals("snapshot_changed", r.rejection); assertEquals(listOf(8), calls)
    }
    @Test fun newerUtteranceRejectsLateSoundWithoutChangingTextFlow() {
        val r = NumberTapDispatcher().dispatch(8, "点击八", sound, req, live.copy(uid = "g1-u2")) { it == 8 }
        assertEquals("stale_utterance", r.rejection); assertEquals(8, r.number)
    }
    @Test fun noAudioIsExactlyOriginalTextTap() {
        val calls = mutableListOf<Int>()
        NumberTapDispatcher().dispatch(76, "点击七十六", null, null, live.copy(enabled = false)) { calls.add(it); false }
        assertEquals(listOf(76), calls)
    }
}
