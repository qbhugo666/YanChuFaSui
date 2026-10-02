package com.voicecontrol.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** C1复用既有完整编号回放，不再运行ASR/encoder。
 * 普通编号/无网格/假执行器，保护模式及真实页面效果由别的验收负责。
 * candidate仅指本轮诊断接线，没有新的声音策略。真值永远在决策输出后才读取。 */
class SpeechAuditReplayTest {
    private fun file(p: String) = OptionalExperimentFiles.requireFile(p).canonicalFile

    @Test fun currentOnAndDiagnosticCandidateAreFrozenWithoutNewHeadInference() {
        val production = file("src/main/assets/commands.json")
        val root = production.parentFile!!.parentFile!!.parentFile!!.parentFile!!.parentFile!!
        // C1账本作为305轮冻结基线保留，新生产结果另存，不能覆盖后再称旧基线。
        val out = root.resolve("_test/m6/number_override_safety/current_ledger")
        out.mkdirs()
        val summaries = JSONArray()
        for ((name, source, count) in listOf(
            Triple("number_known_development", "_test/m6/number_acoustic_v3/out/pair4_10/replay_final2.json", 30),
            Triple("number_analysed_batch", "_test/m6/number_route_recovery/acceptance/replay.json", 30),
            Triple("navigation_known_development", "_test/m6/number_acoustic_v3/out/navigation/replay_fresh.json", 0))) {
            val input = file(source)
            val full = out.resolve("full_$name.json")
            NumberPairReplayTest().replayTo(input, full, includeSuffix = true,
                includeNavigationProtection = true, counts = listOf(count),
                textStatuses = listOf(SilentCommandRecovery.TextTapStatus.NOT_FOUND))
            val original = JSONArray(input.readText(Charsets.UTF_8))
            val oldRows = JSONArray(full.readText(Charsets.UTF_8))
            assertEquals(original.length(), oldRows.length())
            val ledger = JSONArray()
            var positive = 0; var offCorrect = 0; var currentCorrect = 0; var silent = 0; var wrong = 0
            var nonNavDispatchedNav = 0
            val mistakes = mutableSetOf<String>()
            val isNav = name.startsWith("navigation")
            for (i in 0 until original.length()) {
                val raw = original.getJSONObject(i)
                val row = oldRows.getJSONObject(i)
                assertEquals(raw.getString("case_id"), row.getString("case_id"))
                val results = row.getJSONObject("results")
                val off = results.getJSONObject("off").getString("final")
                val current = results.getJSONObject("suffix").getString("final")
                assertEquals("OFF observation must not change routing", off,
                    results.getJSONObject("suffix_off").getString("final"))
                // label=other只表示非导航，里面也有真实编号/长按/重复，不能一律当闲话。
                val truth = if (isNav) raw.optString("label").takeIf { it in SpeechAudit.NAVIGATION }
                    else raw.optInt("true_number").takeIf { raw.optString("kind") == "pos" && it in 1..30 }
                        ?.let { "TapNumber:$it" }
                if (truth != null) {
                    positive++
                    if (off == truth) offCorrect++
                    if (current == truth) currentCorrect++ else {
                        mistakes.add(raw.getString("case_id").substringBeforeLast('-'))
                        if (current == "no_action") silent++ else wrong++
                    }
                } else if (isNav && current in SpeechAudit.NAVIGATION) nonNavDispatchedNav++
                val cause = when {
                    truth == null && isNav && current in SpeechAudit.NAVIGATION -> "non_navigation_dispatched_navigation"
                    truth == null -> "not_scored_in_this_group"
                    current == truth -> "decision_correct"
                    current == "no_action" -> "silent_error"
                    else -> "wrong_action_or_number"
                }
                val heads = JSONObject().put("number", raw.optJSONObject("tops")?.optJSONArray("current") ?: JSONObject.NULL)
                    .put("number_pair", raw.optJSONArray("pair_top") ?: JSONObject.NULL)
                    .put("number_segment", raw.optJSONArray("installed_segment_top") ?: JSONObject.NULL)
                    .put("m6", raw.optJSONArray("m6_top") ?: JSONObject.NULL)
                    .put("cursor", raw.optJSONArray("cursor_top") ?: JSONObject.NULL)
                    .put("navigation_experiment_not_installed", raw.optJSONArray("nav_top") ?: JSONObject.NULL)
                ledger.put(JSONObject().put("case_id", raw.getString("case_id"))
                    .put("raw_id", raw.getString("case_id").substringBeforeLast('-'))
                    .put("evidence", "known_synthetic_development")
                    .put("condition", raw.optString("condition"))
                    .put("spoken_text", raw.optString("text_expected", raw.optString("text")))
                    .put("asr_text", raw.getString("asr_text"))
                    .put("text_clean_parse", NumberReviewPolicy.isCleanParse(raw.getString("asr_text")))
                    .put("truth", truth ?: JSONObject.NULL).put("truth_scope", if (isNav) "navigation_action_only" else "exact_number")
                    .put("off", off).put("current_on", current).put("candidate_on", current)
                    .put("candidate_change", false).put("error_class", cause)
                    .put("capture_or_asr_cause", "cannot_infer_without_inspecting_original_audio")
                    .put("heads", heads).put("context", JSONObject().put("label_count", count)
                        .put("grid", false).put("text_target_status", "NOT_FOUND").put("focused_editable", true))
                    .put("cached_model_outputs_missing", isNav))
            }
            out.resolve("ledger_$name.json").writeText(ledger.toString(1), Charsets.UTF_8)
            val summary = JSONObject().put("dataset", name).put("input", source)
                .put("input_sha256", SpeechAudit.digest(input.readBytes()))
                .put("audio_conditions", original.length()).put("primary_contexts", original.length())
                .put("positive_conditions", positive).put("off_correct", offCorrect)
                .put("current_on_correct", currentCorrect).put("current_on_errors", positive - currentCorrect)
                .put("current_on_silent_errors", silent).put("current_on_wrong_action_errors", wrong)
                .put("different_raw_errors", mistakes.size).put("non_navigation_dispatched_nav", nonNavDispatchedNav)
                .put("candidate_new_correct", 0).put("candidate_new_wrong", 0).put("off_changes", 0)
                .put("evidence", "known_synthetic_development_not_human_accuracy")
                .put("candidate_is_diagnostics_only", true)
            summaries.put(summary)
            if (isNav) { assertEquals(144, positive); assertEquals(140, currentCorrect); assertEquals(7, nonNavDispatchedNav) }
            if (name == "number_known_development") { assertEquals(792, positive); assertEquals(411, offCorrect) }
            if (name == "number_analysed_batch") { assertEquals(60, positive); assertEquals(42, currentCorrect) }
        }
        out.resolve("summary.json").writeText(JSONObject().put("schema", SpeechAudit.SCHEMA)
            .put("candidate_has_new_adoption", false).put("datasets", summaries).toString(2), Charsets.UTF_8)
    }
}
