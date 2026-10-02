package com.voicecontrol.app

import org.junit.Assert.*
import org.junit.Test

class NumberReviewContextTest {
    private val snap = NumberReviewContext.Snapshot(1, "app", listOf("A@1", "B@2"))
    private val request = NumberReviewContext.Request("g1-u1", 1, snap)
    private val live = NumberReviewContext.Live("g1-u1", 1, true, true, true, snap)

    @Test fun stableRedrawRemainsCompatible() {
        assertNull(NumberReviewContext.rejection(request, live.copy(snapshot = snap.copy(targets = snap.targets.toList()))))
    }
    @Test fun sameCountChangedTargetsIsRejected() {
        assertEquals("snapshot_changed", NumberReviewContext.rejection(request,
            live.copy(snapshot = snap.copy(targets = listOf("C@1", "D@2")))))
    }
    @Test fun sameCoordinatesNewWindowIsRejected() {
        assertEquals("snapshot_changed", NumberReviewContext.rejection(request, live.copy(snapshot = snap.copy(windowId = 2))))
    }
    @Test fun previousSessionCallbackIsRejected() {
        assertEquals("stale_session", NumberReviewContext.rejection(request, live.copy(generation = 2)))
    }
    @Test fun anotherSentenceCannotUseThisProposal() {
        assertEquals("stale_utterance", NumberReviewContext.rejection(request, live.copy(uid = "g1-u2")))
    }
    @Test fun offDuringInferenceRejectsProposal() {
        assertEquals("enhanced_off", NumberReviewContext.rejection(request, live.copy(enabled = false)))
    }
    @Test fun protectedModeAndMissingSnapshotRejectProposal() {
        assertEquals("protected_mode", NumberReviewContext.rejection(request, live.copy(normalMode = false)))
        assertEquals("snapshot_unavailable", NumberReviewContext.rejection(request, live.copy(snapshot = null)))
        assertEquals("session_inactive", NumberReviewContext.rejection(request, live.copy(active = false)))
    }
}
