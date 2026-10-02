package com.voicecontrol.app

import org.junit.Assert.*
import org.junit.Test

class NumberSilentRecoveryTest {
    private val snapshot = NumberReviewContext.Snapshot(1, "app", (1..30).map { "target$it" })
    private val request = NumberReviewContext.Request("g1-u1", 1, snapshot)
    private val live = NumberReviewContext.Live("g1-u1", 1, true, true, true, snapshot)
    private val noFuzzy = CommandMatcher.StrictOutcome(null, false)
    private val sound = listOf("18" to .999f)
    private val missing = SilentCommandRecovery.TextTapStatus.NOT_FOUND

    @Test fun silentNumericIntentDispatchesExactNumberOnceEvenOnFailure() {
        val calls = mutableListOf<Int>()
        val recovery = NumberSilentRecovery()
        val plan = CommandRouting.Decision.TapText("电解十八")
        val result = recovery.attempt(plan, "电解十八", missing, noFuzzy, request, live, sound) {
            calls.add(it); false
        }
        assertEquals(18, result?.number)
        assertFalse(result!!.dispatched)
        assertNull(recovery.attempt(plan, "电解十八", missing, noFuzzy, request, live, sound) { calls.add(it); true })
        assertEquals(listOf(18), calls)
    }

    @Test fun successfulFailedUnavailableTargetsNeverReceiveAnotherClick() {
        for (status in listOf(SilentCommandRecovery.TextTapStatus.DISPATCHED,
            SilentCommandRecovery.TextTapStatus.FAILED, SilentCommandRecovery.TextTapStatus.UNAVAILABLE,
            SilentCommandRecovery.TextTapStatus.NOT_ATTEMPTED)) {
            var calls = 0
            assertNull(NumberSilentRecovery().attempt(CommandRouting.Decision.TapText("电解十八"),
                "电解十八", status, noFuzzy, request, live, sound) { calls++; true })
            assertEquals(0, calls)
        }
    }

    @Test fun noMatchMayRecoverOnlyWithoutAnyPriorTextAttempt() {
        val recovery = NumberSilentRecovery()
        var calls = 0
        val result = recovery.attempt(CommandRouting.Decision.NoMatch("no_candidate"), "机九号",
            SilentCommandRecovery.TextTapStatus.NOT_ATTEMPTED, noFuzzy, request, live,
            listOf("9" to .999f)) { calls++; it == 9 }
        assertEquals(9, result?.number)
        assertEquals(1, calls)
        assertNull(NumberSilentRecovery().attempt(CommandRouting.Decision.NoMatch("no_candidate"), "机九号",
            missing, noFuzzy, request, live, sound) { fail("unexpected tap"); true })
    }

    @Test fun staleOffProtectedOrMissingNumberSnapshotCannotDispatch() {
        for (state in listOf(live.copy(enabled=false), live.copy(active=false), live.copy(normalMode=false),
            live.copy(uid="g1-u2"), live.copy(generation=2), live.copy(snapshot=null),
            live.copy(snapshot=snapshot.copy(targets=snapshot.targets.reversed())))) {
            assertNull(NumberSilentRecovery().attempt(CommandRouting.Decision.TapText("电解十八"),
                "电解十八", missing, noFuzzy, request, state, sound) { fail("unexpected tap"); true })
        }
        assertNull(NumberSilentRecovery().attempt(CommandRouting.Decision.TapText("电解十八"),
            "电解十八", missing, noFuzzy, null, live, sound) { fail("unexpected tap"); true })
    }

    @Test fun rejectsWeakNonNumericOrInvalidSoundAndTargetOutsideSnapshot() {
        for (scores in listOf(null, listOf("18" to .98f), listOf("18" to Float.NaN),
            listOf("18" to Float.POSITIVE_INFINITY), listOf("18" to 1.1f), listOf("31" to .999f),
            listOf("out_of_range" to .999f), listOf("other" to .999f))) {
            assertNull(NumberSilentRecovery().attempt(CommandRouting.Decision.TapText("电解十八"),
                "电解十八", missing, noFuzzy, request, live, scores) { fail("unexpected tap"); true })
        }
        val small = snapshot.copy(targets=snapshot.targets.take(10))
        assertNull(NumberSilentRecovery().attempt(CommandRouting.Decision.TapText("电解十八"),
            "电解十八", missing, noFuzzy, request.copy(snapshot=small), live.copy(snapshot=small),
            sound) { fail("unexpected tap"); true })
    }

    @Test fun ordinaryTextNumbersNegationAndParameterIntentsAreNotRescueEvidence() {
        val recovery = NumberSilentRecovery()
        for (text in listOf("", "十八", "几点十八", "点击抖音", "点击设置", "不要点击十八", "点击十八吗",
            "重复十八次", "长按十八", "网格十八", "输入十八", "显示十八", "今天十八个人"))
            assertFalse(text, recovery.textEvidence(text))
        for (text in listOf("电解十八", "机九号", "紧及十二号", "点击第十八个"))
            assertTrue(text, recovery.textEvidence(text))
        assertNull(recovery.attempt(CommandRouting.Decision.TapNumber(18), "点击十八", missing,
            noFuzzy, request, live, sound) { fail("unexpected second tap"); true })
        assertNull(recovery.attempt(CommandRouting.Decision.TapText("电解十八"), "电解十八", missing,
            CommandMatcher.StrictOutcome(null, true), request, live, sound) { fail("ambiguous"); true })
    }
}
