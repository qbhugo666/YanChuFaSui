package com.voicecontrol.app

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SpeechAuditTest {
    private val context = SpeechAudit.Context("normal", true, true, 1, "g1-u1", true, false, 30, "a", "a")
    private fun recorder(decision: AudioDecisionOutcome? = null, capture: SpeechAudit.Capture = SpeechAudit.Capture()) =
        SpeechAudit.Recorder("g1-u1", context, capture, decision, "assets", 1000L)
    private fun entry(r: SpeechAudit.Recorder, intent: String, observed: SpeechAudit.ObservedEffect = SpeechAudit.ObservedEffect.UNKNOWN) =
        UsageLog.Entry(System.currentTimeMillis(), "", "点击八", uid = "g1-u1", speechAudit = r.json(null).toString(),
            confirmedIntent = intent, observedEffect = observed.name)

    @Test fun correctAudioNumberDoesNotBecomeTruthUntilUserMarksIt() {
        val r = recorder(AudioDecisionOutcome(null, null, 10L, numTop = listOf("18" to .999f)))
        r.planned(CommandRouting.Decision.TapNumber(8))
        r.dispatched(SpeechAudit.Route("tap_number", 8), true)
        assertEquals("unlabeled", SpeechAudit.assessed(entry(r, "")).getString("assessment"))
        val scored = SpeechAudit.assessed(entry(r, "tap_number:18"))
        assertFalse(scored.getBoolean("dispatch_choice_correct"))
        assertEquals("tap_number:8", scored.getString("dispatch_choice"))
        assertEquals("decision_mismatch_audio_cause_unknown", scored.getString("assessment"))
        assertFalse(scored.getBoolean("audio_available"))
    }

    @Test fun sharedNumberDispatcherTraceIsExactAndFailedDispatchIsNotSuccess() {
        val snapshot = NumberReviewContext.Snapshot(1, "page", (1..30).map { "target$it" })
        val request = NumberReviewContext.Request("g1-u1", 1, snapshot)
        val live = NumberReviewContext.Live("g1-u1", 1, true, true, true, snapshot)
        for (ok in listOf(true, false)) {
            val r = recorder()
            r.planned(CommandRouting.Decision.TapNumber(8))
            val result = NumberTapDispatcher().dispatch(8, "点击八", listOf("18" to .99f), request, live,
                confirmation = listOf("18" to .99f)) { n ->
                assertEquals(18, n); ok
            }
            r.dispatched(SpeechAudit.Route("tap_number", result.number), result.dispatched)
            val assessed = SpeechAudit.assessed(entry(r, "tap_number:18"))
            assertEquals(ok, assessed.getBoolean("dispatch_choice_correct"))
            assertEquals(if (ok) "dispatch_choice_matches_intent" else "dispatch_failed", assessed.getString("assessment"))
        }
    }

    @Test fun productionNavigationGuardRejectionIsNoCommandNotSuccessfulBack() {
        val r = recorder()
        val match = CommandMatcher.Match("go_back", "go_back", "basic_navigation", "返回", "contains")
        r.planned(CommandRouting.Decision.DispatchCommand(match))
        r.matched(match, false)
        val outcome = NavigationIntentGuard.dispatch("不能返回", match, true, true, false) { fail("must not dispatch"); true }
        r.rejected("navigation_${outcome.rejection!!.name}")
        assertTrue(SpeechAudit.assessed(entry(r, "no_command")).getBoolean("dispatch_choice_correct"))
        assertFalse(SpeechAudit.assessed(entry(r, "go_back")).getBoolean("dispatch_choice_correct"))
    }

    @Test fun acceptedGestureDoesNotProvePageBusinessEffect() {
        val r = recorder()
        r.dispatched(SpeechAudit.Route("swipe_up"), true)
        assertEquals("unconfirmed", r.json(null).getJSONObject("outcome").getString("business_effect"))
        for (effect in listOf(SpeechAudit.ObservedEffect.NONE, SpeechAudit.ObservedEffect.WRONG))
            assertEquals("effect_or_trace_conflict", SpeechAudit.assessed(entry(r, "swipe_up", effect)).getString("assessment"))
        assertEquals("dispatch_choice_matches_intent", SpeechAudit.assessed(entry(r, "swipe_up", SpeechAudit.ObservedEffect.UNKNOWN)).getString("assessment"))
    }

    @Test fun noActionDoesNotBorrowPreviousSentenceOrCountUnobservedExecutorAsCorrect() {
        val silent = recorder()
        silent.planned(CommandRouting.Decision.NoMatch("unmatched"))
        assertEquals("no_command", SpeechAudit.assessed(entry(silent, "go_back")).getString("dispatch_choice"))
        val unobserved = recorder()
        unobserved.planned(CommandRouting.Decision.Repeat(5))
        assertEquals("unobserved_path", SpeechAudit.assessed(entry(unobserved, "no_command")).getString("assessment"))
        assertFalse(SpeechAudit.assessed(entry(unobserved, "no_command")).has("dispatch_choice_correct"))
    }

    @Test fun failedTextThenFuzzyActionAreBothVisibleWithoutExtraDispatch() {
        val r = recorder()
        r.planned(CommandRouting.Decision.TapText("向上"))
        r.textTap(SilentCommandRecovery.TextTapStatus.FAILED)
        r.dispatched(SpeechAudit.Route("swipe_up"), true)
        val json = r.json(null)
        assertEquals(2, json.getJSONObject("outcome").getInt("attempts_observed"))
        assertEquals("FAILED", json.getString("text_tap_status"))
        assertEquals("swipe_up", SpeechAudit.assessed(entry(r, "swipe_up")).getString("dispatch_choice"))
    }

    @Test fun invalidProbabilitiesAndMissingModelsDoNotLoseSentenceOrBecomeEvidence() {
        val r = recorder(AudioDecisionOutcome(null, Float.NaN, 0, numTop = listOf("18" to Float.NaN, "4" to Float.POSITIVE_INFINITY,
            "10" to 1.1f), m6Top = null))
        val json = JSONObject(r.json(null).toString())
        val review = json.getJSONObject("review")
        assertTrue(review.getJSONObject("volume").isNull("prob"))
        assertFalse(review.getJSONObject("heads").getJSONObject("m6").getBoolean("available"))
        assertTrue(review.getJSONObject("heads").getJSONObject("number").getBoolean("invalid_probability"))
        assertFalse(json.getBoolean("audio_retained"))
        assertFalse(json.getBoolean("features_retained"))
        assertFalse(json.has("pcm"))
    }

    @Test fun modesSwitchSnapshotAndTimingsAreFactsWithoutChangingGuards() {
        val capture = SpeechAudit.Capture(audioDurationMs = 600, asrMs = 120, reviewWaitMs = 80, queuedAtMs = 940,
            enhancedAtRecognition = true, modeAtRecognition = "normal", m6Requested = true)
        val changed = context.copy(mode = "dictation", enhanced = false, liveSnapshot = "b", latestUid = "g1-u2")
        val json = SpeechAudit.Recorder("g1-u1", changed, capture, null, "assets", 1000).json(null)
        assertEquals(60, json.getJSONObject("timing").getInt("main_queue_ms"))
        assertEquals(80, json.getJSONObject("timing").getInt("review_wait_ms"))
        assertEquals("dictation", json.getJSONObject("context").getString("mode"))
        assertEquals("normal", json.getJSONObject("context").getString("recognition_mode"))
        val snap = NumberReviewContext.Snapshot(1, "private_app", listOf("private_target"))
        val token = SpeechAudit.snapshotToken(snap)!!
        assertEquals(64, token.length)
        assertEquals(token, SpeechAudit.snapshotToken(snap.copy()))
        assertNotEquals(token, SpeechAudit.snapshotToken(snap.copy(targets = listOf("another"))))
        assertFalse(token.contains("private"))
    }

    @Test fun numberedIntentAndMissingOrMismatchedUidCannotInflateAccuracy() {
        for (n in 1..30) {
            assertTrue(SpeechAudit.validIntent("tap_number:$n"))
            assertEquals("点击编号 $n", SpeechAudit.intentLabel("tap_number:$n"))
        }
        for (v in listOf("tap_number:0", "tap_number:31", "tap_number:76", "tap_number:018", "18", "lock_screen", ""))
            assertFalse(v, SpeechAudit.validIntent(v))
        val r = recorder()
        r.dispatched(SpeechAudit.Route("go_back"), true)
        assertEquals("missing_trace", SpeechAudit.assessed(entry(r, "go_back").copy(uid = "g1-u2")).getString("assessment"))
        assertEquals("missing_trace", SpeechAudit.assessed(entry(r, "go_back").copy(speechAudit = "")).getString("assessment"))
    }
}
