package com.voicecontrol.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class NumberRouteRecoveryTest {
    private val snapshot = NumberReviewContext.Snapshot(1, "page", (1..30).map { "target$it" })
    private val request = NumberReviewContext.Request("u1", 1, snapshot)
    private val live = NumberReviewContext.Live("u1", 1, true, true, true, snapshot)
    private val fuzzy = CommandMatcher.StrictOutcome(null, false)
    private val missing = SilentCommandRecovery.TextTapStatus.NOT_FOUND
    private fun probability(number: String, score: Float, pair: Boolean = false): List<Pair<String, Float>> {
        val labels = if (pair) NumberPairReviewPolicy.LABELS else NumberRouteRecovery.NUMERIC_LABELS
        return labels.map { it to when (it) { number -> score; "other" -> 1f-score; else -> 0f } }
    }
    private fun original(number: Int) = NumberRouteRecovery.Heads(probability(number.toString(), .999f), null, null)
    private fun verdict(text: String, heads: NumberRouteRecovery.Heads = original(8)) =
        NumberRouteRecovery().evaluate(CommandRouting.Decision.TapText(text), text, missing, fuzzy, request, live, heads)

    @Test fun completeNumericEvidenceWithBoundedPrefixDifference() {
        for ((text, number) in listOf("献机吧" to 8, "建习吧" to 8, "面绩二十三" to 23,
            "电记二十二" to 22, "几四号" to 4, "警击十啊" to 10)) {
            assertEquals(text, number, NumberRouteRecovery.textEvidence(text)?.number)
            assertEquals(text, number, verdict(text, original(number)).number)
        }
        for (text in listOf("现在八", "五击十八", "电起石板", "点击四百", "点击四吧", "点击111",
            "点击七十六", "几四十号", "点击十吧", "电几时吧", "不要点击八", "点击八吗", "点击八？",
            "长按八", "重复八次", "点击八个人")) {
            assertNull(text, NumberRouteRecovery.textEvidence(text))
            assertNull(text, verdict(text).number)
        }
        assertNull(verdict("献机吧", original(18)).number)
        assertEquals("conflicting_heads", verdict("献机吧", NumberRouteRecovery.Heads(
            probability("8", .999f), probability("18", .999f), null)).reason)
        assertEquals("general_head_rejected", verdict("几四", NumberRouteRecovery.Heads(
            probability("non_number_click", .999f), null, probability("4", .999f, true))).reason)
    }

    @Test fun guardedSourceCurrentContextAndFailureCannotDoubleDispatch() {
        for (state in listOf(live.copy(enabled=false), live.copy(active=false), live.copy(normalMode=false),
            live.copy(uid="u2"), live.copy(generation=2), live.copy(snapshot=null),
            live.copy(snapshot=snapshot.copy(targets=snapshot.targets.reversed()))))
            assertNull(NumberRouteRecovery().attempt(CommandRouting.Decision.TapText("献机吧"), "献机吧", missing,
                fuzzy, request, state, original(8)) { fail("unexpected tap"); true })
        for (status in listOf(SilentCommandRecovery.TextTapStatus.DISPATCHED, SilentCommandRecovery.TextTapStatus.FAILED,
            SilentCommandRecovery.TextTapStatus.UNAVAILABLE, SilentCommandRecovery.TextTapStatus.NOT_ATTEMPTED))
            assertNull(NumberRouteRecovery().attempt(CommandRouting.Decision.TapText("献机吧"), "献机吧", status,
                fuzzy, request, live, original(8)) { fail("unexpected tap"); true })
        val recovery = NumberRouteRecovery(); val calls = mutableListOf<Int>()
        val first = recovery.attempt(CommandRouting.Decision.TapText("献机吧"), "献机吧", missing, fuzzy,
            request, live, original(8)) { calls.add(it); false }
        assertEquals(8, first?.number); assertFalse(first!!.dispatched)
        assertNull(recovery.attempt(CommandRouting.Decision.TapText("献机吧"), "献机吧", missing, fuzzy,
            request, live, original(8)) { calls.add(it); true })
        assertEquals(listOf(8), calls)
    }

    @Test fun unsafePlansNumbersOrProbabilitiesDoNotBecomeClicks() {
        val plan=CommandRouting.Decision.TapText("献机吧")
        for (values in listOf(null,probability("8",.98f),probability("8",Float.NaN),
            probability("8",Float.POSITIVE_INFINITY),probability("8",1.1f),
            probability("8",.999f).dropLast(1),List(33) { "8" to (.999f/33) })) {
            assertNull(NumberRouteRecovery().evaluate(plan,"献机吧",missing,fuzzy,request,live,
                NumberRouteRecovery.Heads(values,null,null)).number)
        }
        val small=snapshot.copy(targets=snapshot.targets.take(7))
        assertNull(NumberRouteRecovery().evaluate(plan,"献机吧",missing,fuzzy,request.copy(snapshot=small),
            live.copy(snapshot=small),original(8)).number)
        for (other in listOf(CommandRouting.Decision.TapNumber(8),CommandRouting.Decision.Repeat(8),
            CommandRouting.Decision.TapText("献机吧"))) {
            val outcome=if (other is CommandRouting.Decision.TapText) CommandMatcher.StrictOutcome(null,true) else fuzzy
            assertNull(NumberRouteRecovery().evaluate(other,"献机吧",missing,outcome,request,live,original(8)).number)
        }
        val matcher=CommandMatcher.fromJson(file("src/main/assets/commands.json").readText(Charsets.UTF_8))
        val existing=matcher.matchStrictDetailed("返回")
        assertNotNull(existing.match)
        assertNull(NumberRouteRecovery().evaluate(plan,"献机吧",missing,existing,
            request,live,original(8)).number)
        assertEquals(8,NumberRouteRecovery().evaluate(CommandRouting.Decision.NoMatch("test"),"献机吧",
            SilentCommandRecovery.TextTapStatus.NOT_ATTEMPTED,fuzzy,request,live,original(8)).number)
        assertNull(NumberRouteRecovery().evaluate(CommandRouting.Decision.NoMatch("test"),"献机吧",missing,
            fuzzy,request,live,original(8)).number)
    }

    private fun file(path: String) = OptionalExperimentFiles.requireFile(path)
    private fun pairs(array: JSONArray?): List<Pair<String, Float>>? = array?.let {
        (0 until it.length()).map { i -> it.getJSONObject(i).let { v -> v.getString("label") to v.getDouble("prob").toFloat() } }
    }

    @Test fun cachedCompleteReplayWithoutChangingHistoricalOutputs() {
        val directory = file("_test/m6/number_acoustic_v3/out/pair4_10/replay_final2.json").parentFile!!
        val destination = requireNotNull(directory.canonicalFile.parentFile?.parentFile?.parentFile)
            .resolve("number_route_recovery")
        replayCandidate(directory.resolve("replay_final2.json"),directory.resolve("full_final2.json"),
            destination.resolve("candidate_development_v2.json"))
    }

    @Test fun frozenIndependentFullReplay() {
        val input=file("_test/m6/number_route_recovery/acceptance/replay.json")
        val baseline=input.canonicalFile.parentFile!!.resolve("baseline.json")
        NumberPairReplayTest().replayTo(input,baseline)
        replayCandidate(input,baseline,baseline.parentFile!!.resolve("candidate.json"))
    }

    private fun replayCandidate(input:File,baselineFile:File,output:File) {
        val cases = JSONArray(input.readText(Charsets.UTF_8))
        val byId = (0 until cases.length()).associate { cases.getJSONObject(it).let { c -> c.getString("case_id") to c } }
        val baseline = JSONArray(baselineFile.readText(Charsets.UTF_8))
        val matcher = CommandMatcher.fromJson(file("src/main/assets/commands.json").readText(Charsets.UTF_8))
        output.parentFile!!.mkdirs()
        var written = 0
        output.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write("[")
            for (i in 0 until baseline.length()) {
                val b = baseline.getJSONObject(i); val c = byId.getValue(b.getString("case_id"))
                val text = c.getString("asr_text"); val count = b.getInt("count")
                val ctx = CommandRouting.UtteranceContext(false, count>0, true, visibleLabelCount=count.takeIf { it>0 })
                val plan = CommandRouting.planCommand(text, ctx, matcher)
                assertEquals("production baseline plan drift", b.getString("plan"), plan.javaClass.simpleName)
                val target = count.takeIf { it>0 }?.let { NumberReviewContext.Snapshot(1, "page", (1..it).map { n->"target$n" }) }
                val uid = "${c.getString("case_id")}-${b.getString("text_status")}-$count"
                val old = b.getJSONObject("results").getJSONObject("pair").getString("final")
                val status = if (plan is CommandRouting.Decision.NoMatch) SilentCommandRecovery.TextTapStatus.NOT_ATTEMPTED
                    else SilentCommandRecovery.TextTapStatus.valueOf(b.getString("text_status"))
                val heads = NumberRouteRecovery.Heads(pairs(c.getJSONObject("tops").getJSONArray("current")),
                    pairs(c.optJSONArray("installed_segment_top")), pairs(c.optJSONArray("pair_top")))
                val decision = if (old=="no_action") NumberRouteRecovery().evaluate(plan,text,status,
                    matcher.matchFuzzyDetailed(text), NumberReviewContext.Request(uid,1,target),
                    NumberReviewContext.Live(uid,1,true,true,true,target),heads) else NumberRouteRecovery.Verdict(null,null,"existing_action")
                val after = decision.number?.let { "TapNumber:$it" } ?: old
                val off = b.getJSONObject("results").optJSONObject("off")?.optString("final")
                // OFF用同一候选验证零参与，不把缓存ON当作OFF。
                val offVerdict = if (off=="no_action") NumberRouteRecovery().evaluate(plan,text,status,
                    matcher.matchFuzzyDetailed(text),NumberReviewContext.Request(uid,1,target),
                    NumberReviewContext.Live(uid,1,false,true,true,target),heads) else null
                assertNull("OFF recovery participated",offVerdict?.number)
                val row = JSONObject().put("case_id",c.getString("case_id")).put("text_expected",c.optString("text_expected"))
                    .put("asr_text",text).put("kind",c.getString("kind")).put("true_number",c.opt("true_number"))
                    .put("condition",c.optString("condition")).put("count",count).put("text_status",b.getString("text_status"))
                    .put("before",old).put("after",after).put("reason",decision.reason).put("source",decision.source ?: "")
                    .put("prefix_cost",decision.evidence?.prefixCost ?: -1.0).put("plan",plan.javaClass.simpleName)
                    .put("off_before",off ?: JSONObject.NULL).put("off_after",off ?: JSONObject.NULL)
                if (written++>0) writer.write(",\n")
                writer.write(row.toString())
            }
            writer.write("]")
        }
        assertEquals(baseline.length(),written)
    }
}
