package com.voicecontrol.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 模拟候选证明守卫行为；缓存声学仅作已看过的合成开发回归，不冒充真人/独立验收。 */
class NumberOverrideSafetyTest {
    private val snapshot = NumberReviewContext.Snapshot(1, "page", (1..30).map { "target$it" })
    private val request = NumberReviewContext.Request("g1-u1", 1, snapshot)
    private val live = NumberReviewContext.Live("g1-u1", 1, true, true, true, snapshot)
    private fun sound(n: String, p: Float = .999f) = listOf(n to p)
    private fun pair(label: String) = NumberPairReviewPolicy.LABELS.map { it to if (it == label) .999f else .0005f }

    @Test fun highMainScoreCannotOverrideWithoutStrongConsistentExistingSegments() {
        for (confirmation in listOf(null, sound("8"), sound("18", .989f),
            sound("18", Float.NaN), sound("18", Float.POSITIVE_INFINITY), sound("18", 1.1f))) {
            val taps = mutableListOf<Int>()
            val result = NumberTapDispatcher().dispatch(8, "点击八", sound("18"), request, live,
                confirmation = confirmation) { taps.add(it); true }
            assertEquals(listOf(8), taps)
            assertNull(result.correction)
            assertNotNull(result.rejection)
        }
        val confirmed = NumberTapDispatcher().dispatch(8, "点击八", sound("18"), request, live,
            confirmation = listOf("8" to .001f, "18" to .999f)) { true }
        assertEquals(18, confirmed.number)
        assertNull(confirmed.rejection)
    }

    @Test fun pairDomainConflictDoesNotFallThroughToAnotherCorrection() {
        for ((old, new, domain) in listOf(Triple(10,18,"10"), Triple(10,11,"10"), Triple(12,10,"other"))) {
            val taps = mutableListOf<Int>()
            val result = NumberTapDispatcher().dispatch(old, "点击$old", sound(new.toString()), request, live,
                pairAudio = pair(domain), confirmation = sound(new.toString())) { taps.add(it); true }
            assertEquals("number_pair_domain_conflict", result.rejection)
            assertEquals(listOf(old), taps)
        }
        // 其他类是三类头的范围判断，不把它当所有数字/命令的拒识。
        assertEquals(18, NumberTapDispatcher().dispatch(10, "点击一领", sound("18"), request, live,
            pairAudio = pair("other"), confirmation = sound("18")) { true }.number)
    }

    @Test fun offPageAndSentenceGuardsAndFailedTapStillDispatchAtMostOnce() {
        for (state in listOf(live.copy(enabled=false),live.copy(active=false),live.copy(normalMode=false),
            live.copy(uid="g1-u2"),live.copy(generation=2),
            live.copy(snapshot=snapshot.copy(targets=snapshot.targets.reversed())))) {
            val taps=mutableListOf<Int>()
            val result=NumberTapDispatcher().dispatch(8,"点击八",sound("18"),request,state,
                confirmation=sound("18")) { taps.add(it);true }
            assertEquals(listOf(8),taps)
            assertNull(result.correction)
        }
        val owner=NumberTapDispatcher();val taps=mutableListOf<Int>()
        val first=owner.dispatch(8,"点击八",sound("18"),request,live,confirmation=sound("8")) {
            taps.add(it);false }
        assertFalse(first.dispatched)
        assertFalse(owner.dispatch(8,"点击八",sound("18"),request,live,confirmation=sound("18")) {
            taps.add(it);true }.attempted)
        assertEquals(listOf(8),taps)
        val next=owner.dispatch(8,"点击八",sound("18"),request.copy(uid="g1-u2"),live.copy(uid="g1-u2"),
            confirmation=sound("18")) { taps.add(it);true }
        assertEquals(18,next.number)
        assertEquals(listOf(8,18),taps)
    }

    @Test fun strictFourTenPathRemainsAvailableAfterGeneralAbstention() {
        for ((old,new) in listOf(4 to 10,10 to 4)) {
            val taps=mutableListOf<Int>()
            val result=NumberTapDispatcher().dispatch(old,"点击$old",null,request,live,
                pairAudio=pair(new.toString())) { taps.add(it);true }
            assertEquals(listOf(new),taps)
            assertNotNull(result.correction)
        }
    }

    private fun file(path: String) = OptionalExperimentFiles.requireFile(path).canonicalFile

    @Test fun completeCachedProductionReplayAccountsForPreventedErrorsAndLostRescues() {
        val production=file("src/main/assets/commands.json")
        val root=production.parentFile!!.parentFile!!.parentFile!!.parentFile!!.parentFile!!
        val out=root.resolve("_test/m6/number_override_safety").apply { mkdirs() }
        val summaries=JSONArray()
        for ((name,source) in listOf(
            "number_known_development" to "_test/m6/number_acoustic_v3/out/pair4_10/replay_final2.json",
            "number_analysed_batch" to "_test/m6/number_route_recovery/acceptance/replay.json")) {
            // 305轮冻结账本作为旧ON；读入后才跑新生产规则，禁止覆盖或用真值选择候选。
            val baselineFile=file("_test/m6/c1_speech_audit/ledger_$name.json")
            val baselineBytes=baselineFile.readBytes()
            val baseline=JSONArray(String(baselineBytes,Charsets.UTF_8))
            val baselineById=(0 until baseline.length()).associate {
                baseline.getJSONObject(it).let { row -> row.getString("case_id") to row } }
            val input=file(source)
            val output=out.resolve("full_$name.json")
            NumberPairReplayTest().replayTo(input,output,includeSuffix=true,includeNavigationProtection=true,
                counts=listOf(30),textStatuses=listOf(SilentCommandRecovery.TextTapStatus.NOT_FOUND))
            val current=JSONArray(output.readText(Charsets.UTF_8));val changes=JSONArray()
            var total=0;var beforeCorrect=0;var afterCorrect=0;var prevented=0;var lost=0;var newWrong=0
            var offCorrect=0;var rescuesVsOff=0;var harmVsOff=0;var controlChanges=0;var newControlActions=0
            assertEquals(baseline.length(),current.length())
            for (i in 0 until current.length()) {
                val row=current.getJSONObject(i);val old=baselineById.getValue(row.getString("case_id"))
                val result=row.getJSONObject("results")
                val off=result.getJSONObject("off").getString("final")
                val before=old.getString("current_on");val after=result.getJSONObject("suffix").getString("final")
                assertEquals("OFF changed",old.getString("off"),off)
                assertEquals(off,result.getJSONObject("suffix_off").getString("final"))
                val truth=old.optString("truth").takeIf { it.startsWith("TapNumber:") }
                if (truth != null) {
                    total++
                    if (before==truth) beforeCorrect++
                    if (after==truth) afterCorrect++
                    if (before!=truth && after==truth) prevented++
                    if (before==truth && after!=truth) {
                        newWrong++
                        if (off!=truth) lost++
                    }
                    if (off==truth) offCorrect++
                    if (off!=truth && after==truth) rescuesVsOff++
                    if (off==truth && after!=truth) harmVsOff++
                } else if (before!=after) {
                    controlChanges++
                    if (before=="no_action" && after!="no_action") newControlActions++
                }
                if (before!=after) changes.put(JSONObject().put("case_id",row.getString("case_id"))
                    .put("asr_text",row.getString("asr_text")).put("truth",truth ?: JSONObject.NULL)
                    .put("off",off).put("before",before).put("after",after))
            }
            assertEquals("candidate caused an error outside losing an existing rescue",lost,newWrong)
            assertEquals("new control actions",0,newControlActions)
            assertEquals("original OFF-correct numbers still overridden wrongly",0,harmVsOff)
            assertEquals(SpeechAudit.digest(baselineBytes),SpeechAudit.digest(baselineFile.readBytes()))
            val summary=JSONObject().put("dataset",name).put("conditions",total)
                .put("input_sha256",SpeechAudit.digest(input.readBytes()))
                .put("baseline_sha256",SpeechAudit.digest(baselineBytes))
                .put("before_correct",beforeCorrect).put("after_correct",afterCorrect)
                .put("prevented_existing_errors",prevented).put("lost_existing_rescues",lost)
                .put("changed_correct_to_wrong",newWrong).put("net_correct_change",afterCorrect-beforeCorrect)
                .put("off_correct",offCorrect).put("rescues_vs_off",rescuesVsOff).put("harm_vs_off",harmVsOff)
                .put("control_changes",controlChanges).put("new_control_actions",newControlActions).put("off_changes",0)
                .put("evidence","already_analysed_synthetic_development_not_independent_or_human_accuracy")
                .put("changes",changes)
            summaries.put(summary)
            if (name=="number_known_development") {
                assertEquals(792,total)
                assertEquals(410,beforeCorrect)
                assertTrue("must improve net decisions, not just suppress all review",afterCorrect>beforeCorrect)
            } else {
                assertEquals(60,total)
                assertEquals(42,beforeCorrect)
                assertEquals(42,afterCorrect)
            }
        }
        out.resolve("summary.json").writeText(JSONObject().put("datasets",summaries).toString(2),Charsets.UTF_8)
    }
}
