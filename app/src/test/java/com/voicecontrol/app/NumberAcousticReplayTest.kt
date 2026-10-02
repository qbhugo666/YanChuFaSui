package com.voicecontrol.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 假执行器记录实际派发。答案只进入输出评分，不决定纠正或文字目标是否存在。 */
class NumberAcousticReplayTest {
    private fun file(path: String) = OptionalExperimentFiles.requireFile(path)
    private fun pairs(a: JSONArray?): List<Pair<String, Float>>? = a?.let {
        (0 until it.length()).map { i -> it.getJSONObject(i).let { v -> v.getString("label") to v.getDouble("prob").toFloat() } }
    }
    @Test fun developmentFullReplay() = replay("dev")
    @Test fun regressionFullReplay() = replay("final")
    @Test fun newPhrasesFullReplay() = replay("accept2")
    @Test fun frozenValidationFullReplay() = replay("validation")
    @Test fun failedValidationDevelopmentReplay() = replay("robust_dev")
    @Test fun calibratedDevelopmentReplay() = replay("calibrated_dev")
    @Test fun freshFinalReplay() = replay("fresh_final")

    private fun replay(split: String) {
        val input = file("_test/m6/number_acoustic_v3/out/replay_$split.json")
        val cases = JSONArray(input.readText(Charsets.UTF_8))
        val matcher = CommandMatcher.fromJson(file("src/main/assets/commands.json").readText(Charsets.UTF_8))
        var outputCount = 0
        val destination = requireNotNull(input.canonicalFile.parentFile?.parentFile?.parentFile)
            .resolve("number_route_recovery/legacy_replay/acoustic")
        destination.mkdirs()
        destination.resolve("full_$split.json").bufferedWriter(Charsets.UTF_8).use { writer ->
        writer.write("[")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val text = c.getString("asr_text")
            for (count in listOf(20, 30, 80, 0)) for (status in listOf(
                SilentCommandRecovery.TextTapStatus.DISPATCHED, SilentCommandRecovery.TextTapStatus.NOT_FOUND,
                SilentCommandRecovery.TextTapStatus.UNAVAILABLE, SilentCommandRecovery.TextTapStatus.FAILED)) {
                val ctx = CommandRouting.UtteranceContext(false, count > 0, true,
                    visibleLabelCount = count.takeIf { it > 0 })
                val plan = CommandRouting.planCommand(text, ctx, matcher)
                val snapshot = count.takeIf { it > 0 }?.let {
                    NumberReviewContext.Snapshot(1, "fake-page", (1..it).map { n -> "target$n" }) }
                val uid = "${c.getString("case_id")}-$count-$status"
                val request = NumberReviewContext.Request(uid, 1, snapshot)
                val m6 = pairs(c.optJSONArray("m6_top"))
                val cursor = pairs(c.optJSONArray("cursor_top"))
                val results = JSONObject()
                val productionCandidate = if (c.getJSONObject("tops").has("calibrated")) listOf("silent_only") else emptyList()
                for (model in listOf("off") + productionCandidate + c.getJSONObject("tops").keys().asSequence().toList()) {
                    val enabled = model != "off"
                    val volume = AudioReviewRequest.plan(text, enabled, true, false, false, false).volume
                    fun reviewed(action: String) = AudioDecisionRouting.correctedAction(action, enabled,
                        if (volume) c.optString("volume_decision").takeIf { it in listOf("inc", "dec") } else null)
                    val calls = mutableListOf<String>()
                    var corrected = false
                    var silentNumber = false
                    var outcome = "no_action"
                    when (plan) {
                        is CommandRouting.Decision.TapNumber -> {
                            val supplementary = model in listOf("robust", "calibrated")
                            val audio = if (enabled) pairs(c.getJSONObject("tops").getJSONArray(if (supplementary || model == "silent_only") "current" else model)) else null
                            val r = NumberTapDispatcher().dispatch(plan.number, text, audio, request,
                                NumberReviewContext.Live(uid, 1, enabled, true, true, snapshot),
                                if (supplementary) pairs(c.getJSONObject("tops").getJSONArray(model)) else null) { target ->
                                if (target !in 1..count && count > 0) false
                                else { calls.add("TapNumber:$target"); true }
                            }
                            corrected = r.correction != null
                            if (r.dispatched) outcome = "TapNumber:${r.number}"
                        }
                        is CommandRouting.Decision.DispatchCommand -> {
                            outcome = reviewed(plan.action); calls.add(outcome)
                        }
                        is CommandRouting.Decision.TapText, is CommandRouting.Decision.NoMatch -> {
                            val tapState = if (plan is CommandRouting.Decision.TapText) status else
                                SilentCommandRecovery.TextTapStatus.NOT_ATTEMPTED
                            if (tapState == SilentCommandRecovery.TextTapStatus.DISPATCHED) {
                                outcome = "tap_text"; calls.add(outcome)
                            } else {
                                val fuzzy = matcher.matchFuzzyDetailed(text)
                                if (!fuzzy.ambiguous && fuzzy.match != null) {
                                    outcome = reviewed(fuzzy.match!!.action); calls.add(outcome)
                                } else if (enabled) {
                                    val rescued = SilentCommandRecovery().attempt(plan, text, tapState, fuzzy,
                                        SilentCommandRecovery.Context(true, true, true, uid, uid,
                                            focusedEditable = true, cursorEnabled = CursorReviewPolicy.ENABLED), m6, cursor) {
                                        calls.add(it); true }
                                    outcome = rescued?.action ?: "no_action"
                                    if (rescued == null && model in listOf("calibrated","silent_only")) {
                                        val numeric = NumberSilentRecovery().attempt(plan,text,tapState,fuzzy,request,
                                            NumberReviewContext.Live(uid,1,true,true,true,snapshot),
                                            pairs(c.getJSONObject("tops").getJSONArray("calibrated"))) { target ->
                                                calls.add("TapNumber:$target");true }
                                        if (numeric != null) {
                                            silentNumber = true
                                            if (numeric.dispatched) outcome = "TapNumber:${numeric.number}"
                                        }
                                    }
                                }
                            }
                        }
                        is CommandRouting.Decision.Ambiguous -> Unit
                        else -> { outcome = plan.javaClass.simpleName; calls.add(outcome) }
                    }
                    assertTrue("multiple dispatches $uid", calls.size <= 1)
                    results.put(model, JSONObject().put("final", outcome).put("corrected", corrected)
                        .put("silent_number", silentNumber)
                        .put("dispatch_count", calls.size).put("plan", plan.javaClass.simpleName))
                }
                val output = JSONObject().put("case_id", c.getString("case_id"))
                    .put("kind", c.getString("kind")).put("true_number", c.opt("true_number"))
                    .put("label", c.getString("label")).put("asr_text", text).put("count", count)
                    .put("text_expected", c.optString("text_expected"))
                    .put("condition", c.optString("condition", "clean"))
                    .put("text_status", status.name).put("results", results)
                if (outputCount++ > 0) writer.write(",\n")
                writer.write(output.toString())
            }
        }
        writer.write("]")
        }
        assertEquals(cases.length() * 16, outputCount)
    }
}
