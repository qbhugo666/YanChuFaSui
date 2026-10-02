package com.voicecontrol.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * M6 D3：完整生产回放（2026-09-30，任务书 M6_REVIEW_AND_D_TASK 缺项①②）。
 *
 * 与首轮 M6ReplayRoutingTest 的差别：TapText 不再一律当 tap_text(param)——按用例标注的
 * 页面双态（目标文字存在/不存在）模拟生产真实链：存在→点击成功；不存在→tapText 失败→
 * matchFuzzySafely 模糊兜底（生产 matcher 真实调用）→命中则执行该命令（旧兜底救回）。
 * 由此得到「相对完整旧流程」的基线与净增益（任务书：不能拿 40 提案当部署依据）。
 */
class M6AcceptReplayTest {

    private fun resolveFile(rel: String): File? =
        listOf(File(rel), File("../$rel")).firstOrNull { it.isFile }

    @Test fun acceptReplayRouting() = runReplay("_test/m6/out/replay_accept_input.json",
        "accept_recents_live.json")

    @Test fun finalReplayRouting() = runReplay("_test/m6/out/replay_final_input.json",
        "final_recents_live.json")

    @Test fun stressReplayRouting() = runReplay("_test/m6/out/replay_stress_input.json",
        "stress_recents_live.json")

    @Test fun cursorStageReplayRouting() = runReplay("_test/m6/cursor_stage/replay_final_input.json",
        "cursor_final_routing.json")

    @Test fun cursorOldErrorsReplayRouting() = runReplay("_test/m6/cursor_stage/replay_regression_input.json",
        "cursor_regression_routing.json")

    @Test fun cursorStageDevReplayRouting() = runReplay("_test/m6/cursor_stage/replay_dev_input.json",
        "cursor_dev_routing.json")

    @Test fun cursorStageFinal2ReplayRouting() = runReplay("_test/m6/cursor_stage/replay_final2_input.json",
        "cursor_final2_routing.json")

    @Test fun cursorStageFinal3ReplayRouting() = runReplay("_test/m6/cursor_stage/replay_final3_input.json",
        "cursor_final3_routing.json")

    private fun runReplay(inputRel: String, outputName: String) {
        val input = resolveFile(inputRel)
        assumeTrue("$inputRel 不存在（先跑对应 prepare）", input != null)
        val matcher = CommandMatcher.fromJson(
            resolveFile("src/main/assets/commands.json")!!.readText(Charsets.UTF_8))
        val root = JSONObject(input!!.readText(Charsets.UTF_8))
        val labels = root.getJSONArray("labels").let { a -> (0 until a.length()).map { a.getString(it) } }
        val covered = JointDecisionPolicy.coveredActions(labels)
        val abstainTh = root.getDouble("abstain_threshold").toFloat()
        val adoptTh = root.getDouble("adopt_threshold").toFloat()
        val cases = root.getJSONArray("cases")
        val out = JSONArray()

        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val text = c.getString("asr_text")
            val ctx = CommandRouting.UtteranceContext(
                gridShowing = false, labelsVisible = false, lastActionPresent = true)
            val decision = CommandRouting.planCommand(text, ctx, matcher)
            val trueAction = c.getString("true_action")
            val kind = c.optString("kind", "cmd")

            val oldRequested = AudioDecisionRouting.shouldRequestReview(
                text, enhancedEnabled = true, dictationMode = false, captureArmed = false,
                longPressMode = false)
            val oldVol = c.optJSONObject("old_volume")
            val audio = c.optJSONArray("new_top")?.let { arr ->
                (0 until arr.length()).map {
                    val o = arr.getJSONObject(it)
                    JointDecisionPolicy.AudioCandidate(o.getString("label"), o.getDouble("prob").toFloat())
                }
            } ?: emptyList()
            val cursorAudio = c.optJSONArray("cursor_top")?.let { arr ->
                (0 until arr.length()).map {
                    val o = arr.getJSONObject(it)
                    o.getString("label") to o.getDouble("prob").toFloat()
                }
            }
            val newRequested = JointDecisionPolicy.shouldRequest(decision, covered, "normal")
            val joint = JointDecisionPolicy.reconcile(
                JointDecisionPolicy.actionOfDecision(decision), audio, abstainTh, adoptTh, newRequested)

            // 完整旧链双态（页面文字存在/不存在）——任务书缺项①
            val variants = JSONArray()
            val flags = c.optJSONArray("taptext_variants")
            val vIter = if (flags != null) (0 until flags.length()).map { flags.getBoolean(it) } else listOf(false)
            val editableFlags = c.optJSONArray("editable_variants")
            val editableStates = if (editableFlags != null)
                (0 until editableFlags.length()).map { editableFlags.getBoolean(it) } else listOf(false)
            for (targetExists in vIter) for (focusedEditable in editableStates) {
                val oldFinal: String
                val oldPath: String
                when (decision) {
                    is CommandRouting.Decision.DispatchCommand -> {
                        oldFinal = decision.action; oldPath = "command"
                    }
                    is CommandRouting.Decision.TapText -> {
                        if (targetExists) {
                            oldFinal = "tap_text_success"; oldPath = "taptext_hit"
                        } else {
                            // 生产：tapText 失败 → matchFuzzySafely 模糊兜底
                            val fuzzy = matcher.matchFuzzyDetailed(text)
                            val fm = fuzzy.match
                            if (!fuzzy.ambiguous && fm != null) {
                                oldFinal = fm.action; oldPath = "taptext_miss_fuzzy_rescue"
                            } else {
                                oldFinal = "no_action"; oldPath = "taptext_miss_silent"
                            }
                        }
                    }
                    is CommandRouting.Decision.NoMatch -> {
                        val fuzzy = matcher.matchFuzzyDetailed(text)
                        oldFinal = if (!fuzzy.ambiguous) fuzzy.match?.action ?: "no_action" else "no_action"
                        oldPath = if (oldFinal == "no_action") "nomatch_silent" else "nomatch_fuzzy_rescue"
                    }
                    is CommandRouting.Decision.Ambiguous -> {
                        oldFinal = "no_action"; oldPath = "ambiguous_silent"
                    }
                    else -> { oldFinal = decision.javaClass.simpleName; oldPath = "param" }
                }
                // 链2（现有音量二审）只在文字链产出的动作是音量时能改判
                var oldAfter = oldFinal
                if (oldRequested && oldVol != null && oldFinal in listOf("volume_up", "volume_down")) {
                    oldAfter = AudioDecisionRouting.correctedAction(oldFinal, true, oldVol.getString("label"))
                }
                variants.put(JSONObject()
                    .put("target_exists", targetExists)
                    .put("focused_editable", focusedEditable)
                    .put("old_final", oldAfter)
                    .put("old_path", oldPath))
            }

            out.put(JSONObject()
                .put("case_id", c.getString("case_id"))
                .put("true_action", trueAction)
                .put("kind", kind)
                .put("asr_text", text)
                .put("text_expected", c.optString("text_expected"))
                .put("decision_type", decision::class.simpleName)
                .put("old_requested", oldRequested)
                .put("new_requested", newRequested)
                .put("joint_outcome", joint::class.simpleName)
                .put("joint_to", (joint as? JointDecisionPolicy.Outcome.ProposeCorrect)?.to ?: JSONObject.NULL)
                .put("joint_conf", (joint as? JointDecisionPolicy.Outcome.ProposeCorrect)?.conf ?: JSONObject.NULL)
                .put("variants", variants))

            // 生产 AdoptionRules 采纳判定（2026-09-30 复核⑤：Python 不再手写第二套规则——
            // 统计直接消费这里的 adopted_* 字段；shadow/离线均不执行，仅判定）
            for (vi in 0 until variants.length()) {
                val v = variants.getJSONObject(vi)
                val from = if (v.getString("old_final") == "no_action") "no_match"
                else v.getString("old_final")
                val uid = c.getString("case_id") + "-$vi"
                val tapStatus = if (decision is CommandRouting.Decision.TapText) {
                    if (v.getBoolean("target_exists")) SilentCommandRecovery.TextTapStatus.DISPATCHED
                    else SilentCommandRecovery.TextTapStatus.NOT_FOUND
                } else SilentCommandRecovery.TextTapStatus.NOT_ATTEMPTED
                val gates = AudioReviewRequest.plan(text, true, true, false, false, false)
                val baseline = if (v.getString("old_final") == "no_action")
                    SilentCommandRecovery().attempt(decision, text, tapStatus,
                        matcher.matchFuzzyDetailed(text),
                        SilentCommandRecovery.Context(gates.m6, true, true, uid, uid),
                        audio.map { it.label to it.prob }) { true } else null
                val result = if (v.getString("old_final") == "no_action")
                    SilentCommandRecovery().attempt(decision, text, tapStatus,
                        matcher.matchFuzzyDetailed(text),
                        SilentCommandRecovery.Context(gates.m6, true, true, uid, uid,
                            focusedEditable=v.getBoolean("focused_editable"),
                            cursorEnabled=root.optBoolean("cursor_adoption_experiment", false)),
                        audio.map { it.label to it.prob }, cursorAudio) { true } else null
                val to = result?.action ?: "none"
                v.put("adopted", result?.dispatched == true)
                v.put("adopt_from", from)
                v.put("adopt_to", to)
                v.put("current_final", baseline?.takeIf { it.dispatched }?.action ?: v.getString("old_final"))
                v.put("final_action", result?.takeIf { it.dispatched }?.action ?: v.getString("old_final"))
            }
        }

        val output = requireNotNull(input.parentFile).resolve(outputName)
        output.writeText(out.toString(1), Charsets.UTF_8)
        assertTrue(out.length() > 0)
        println("M6 replay [$outputName]: ${out.length()} cases")
    }
}
