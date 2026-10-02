package com.voicecontrol.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * M6 工作包 C：三链成对回放的**生产路由侧**（2026-09-30）。
 *
 * Python 侧（_test/m6/m6_replay.py）对同一批测试音频做 PC ASR 与两个声音头的推理，
 * 把逐句 {asr_text, ctx, 音频候选, 真值} 写入 replay_input.json；本测试读入后用**生产
 * Kotlin 实现**（CommandRouting.planCommand + AudioDecisionRouting + JointDecisionPolicy）
 * 跑三条链并写回 routing_out.json——任务书 §A5「路由必须调用生产 Kotlin 实现，不在
 * Python 手抄匹配规则」。文件不存在时跳过（普通 CI 无实验数据）。
 */
class M6ReplayRoutingTest {

    private fun resolve(rel: String): File? {
        val cands = listOf(File(rel), File("../$rel"))
        return cands.firstOrNull { it.isFile }
    }

    @Test fun replayRouting() {
        val input = resolve("_test/m6/out/replay_input.json")
        assumeTrue("replay_input.json 不存在（先跑 python _test/m6/m6_replay.py prepare）",
            input != null)
        val matcher = CommandMatcher.fromJson(
            resolve("src/main/assets/commands.json")!!.readText(Charsets.UTF_8))
        val root = JSONObject(input!!.readText(Charsets.UTF_8))
        val labels = root.getJSONArray("labels").let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        }
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

            // 链1：增强 OFF——纯文字路由拟动作（参数化决策映射为其动作家族标识）
            val offAction = decisionActionId(decision)

            // 链2：现有音量二审（生产函数）
            val oldRequested = AudioDecisionRouting.shouldRequestReview(
                text, enhancedEnabled = true, dictationMode = false, captureArmed = false,
                longPressMode = false)
            val oldDecision = c.optJSONObject("old_volume")   // {label:"inc"/"dec", conf}
            val oldAfter = if (oldRequested && oldDecision != null) {
                val base = offAction ?: "none"
                AudioDecisionRouting.correctedAction(
                    base, enabled = true, decision = oldDecision.getString("label"))
            } else offAction

            // 链3：新策略（离线 JointDecisionPolicy）
            val audio = c.optJSONArray("new_top")?.let { arr ->
                (0 until arr.length()).map {
                    val o = arr.getJSONObject(it)
                    JointDecisionPolicy.AudioCandidate(o.getString("label"),
                        o.getDouble("prob").toFloat())
                }
            } ?: emptyList()
            val newRequested = JointDecisionPolicy.shouldRequest(decision, covered, "normal")
            val joint = JointDecisionPolicy.reconcile(
                JointDecisionPolicy.actionOfDecision(decision), audio, abstainTh, adoptTh,
                newRequested)
            // 新策略拟动作：Agree/Other/NoConfidence/NotRequested→文字决策；ProposeCorrect→
            // 离线只记录提案不执行（最终动作仍=文字决策；提案单列供收益/误伤统计）
            val newAfter = when (joint) {
                is JointDecisionPolicy.Outcome.ProposeCorrect -> offAction
                else -> offAction
            }

            val row = JSONObject()
                .put("case_id", c.getString("case_id"))
                .put("true_action", c.getString("true_action"))
                .put("asr_text", text)
                .put("decision_type", decision::class.simpleName)
                .put("off_action", offAction ?: "none")
                .put("old_requested", oldRequested)
                .put("old_after", oldAfter ?: "none")
                .put("new_requested", newRequested)
                .put("joint_outcome", joint::class.simpleName)
                .put("joint_from", (joint as? JointDecisionPolicy.Outcome.ProposeCorrect)?.from
                    ?: JSONObject.NULL)
                .put("joint_to", (joint as? JointDecisionPolicy.Outcome.ProposeCorrect)?.to
                    ?: JSONObject.NULL)
                .put("new_after", newAfter ?: "none")
            out.put(row)
        }

        val outDir = listOf(File("_test/m6/out"), File("../_test/m6/out"))
            .firstOrNull { it.isDirectory }
            ?: File("../_test/m6/out").apply { mkdirs() }
        val outFile = outDir.resolve("routing_out.json")
        outFile.writeText(out.toString(1), Charsets.UTF_8)
        assertTrue("回放输出条数应为正", out.length() > 0)
        println("M6 replay routing: ${out.length()} cases -> $outFile")
    }

    /** 文字路由结论 → 声学可对照的动作标识（与 JointDecisionPolicy.actionOfDecision 同源，
     *  另把参数化决策映射到其动作家族，供逐句对比表的统一口径） */
    private fun decisionActionId(d: CommandRouting.Decision): String? = when (d) {
        is CommandRouting.Decision.DispatchCommand -> d.action
        is CommandRouting.Decision.TapNumber -> "tap_number(param)"
        is CommandRouting.Decision.GridTapCell -> "grid_tap(param)"
        is CommandRouting.Decision.GridLongPress -> "grid_long_press(param)"
        is CommandRouting.Decision.LongPressNumber -> "long_press_number(param)"
        is CommandRouting.Decision.LongPressText -> "long_press_text(param)"
        is CommandRouting.Decision.TapText -> "tap_text(param)"
        is CommandRouting.Decision.Repeat -> "repeat(param)"
        is CommandRouting.Decision.Replace -> "replace(param)"
        is CommandRouting.Decision.GridZoom -> "grid_zoom(param)"
        is CommandRouting.Decision.Ambiguous -> "ambiguous"
        is CommandRouting.Decision.NoMatch -> "no_match"
    }
}
