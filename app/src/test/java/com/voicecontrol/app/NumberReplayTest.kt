package com.voicecontrol.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 数字双路评测（2026-09-30 审计 §B 修复版）：
 * **回放直接调用生产 NumberReviewPolicy.correctBeforeTap**——策略输入只含真实运行时
 * 可得的文字候选/声音输出/模式/快照；正确答案（trueN）仅用于评分，不控制是否纠正。
 * 逐句比较最终动作+精确参数；负例按各自真实动作评分。
 */
class NumberReplayTest {

    private fun resolveFile(rel: String): File? =
        listOf(File(rel), File("../$rel")).firstOrNull { it.isFile }

    @Test fun numberFinalReplay() =
        runReplay("_test/m6/number_navigation_stage/out/replay_num_final_input.json",
            "num_final_routing_out.json")

    @Test fun numberAccept2Replay() =
        runReplay("_test/m6/number_navigation_stage/out/replay_num_accept2_input.json",
            "num_accept2_routing_out.json")

    private fun runReplay(inputRel: String, outputName: String) {
        val input = resolveFile(inputRel)
        assumeTrue("$inputRel 不存在", input != null)
        val matcher = CommandMatcher.fromJson(
            resolveFile("src/main/assets/commands.json")!!.readText(Charsets.UTF_8))
        val cases = JSONObject(input!!.readText(Charsets.UTF_8)).getJSONArray("cases")
        val out = JSONArray()

        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val text = c.getString("asr_text")
            val trueN: Int? = if (c.isNull("true_number")) null else c.getInt("true_number")
            val kind = c.getString("kind")
            val numTop = c.optJSONArray("num_top")?.let { a ->
                (0 until a.length()).map {
                    val o = a.getJSONObject(it)
                    o.getString("label") to o.getDouble("prob").toFloat()
                }
            } ?: emptyList()

            for (snapshotCount in intArrayOf(20, 30)) {
                val ctx = CommandRouting.UtteranceContext(
                    gridShowing = false, labelsVisible = true, lastActionPresent = true,
                    visibleLabelCount = snapshotCount)
                val decision = CommandRouting.planCommand(text, ctx, matcher)

                // 旧链终动作+编号
                val oldFinal: String
                val oldNumber: Int?
                when (decision) {
                    is CommandRouting.Decision.TapNumber -> {
                        oldFinal = "TapNumber"; oldNumber = decision.number
                    }
                    is CommandRouting.Decision.DispatchCommand -> {
                        oldFinal = decision.action; oldNumber = null
                    }
                    is CommandRouting.Decision.TapText -> { oldFinal = "tap_text"; oldNumber = null }
                    is CommandRouting.Decision.NoMatch -> { oldFinal = "no_action"; oldNumber = null }
                    is CommandRouting.Decision.Ambiguous -> { oldFinal = "ambiguous"; oldNumber = null }
                    is CommandRouting.Decision.Repeat -> { oldFinal = "Repeat"; oldNumber = null }
                    else -> { oldFinal = decision.javaClass.simpleName; oldNumber = null }
                }

                // 生产策略（无答案泄漏）：直接调 correctBeforeTap（含 textCleanParse）
                val correction = if (oldFinal == "TapNumber" && oldNumber != null) {
                    NumberReviewPolicy.correctBeforeTap(
                        oldNumber, snapshotCount, true, numTop,
                        NumberReviewPolicy.isCleanParse(text))
                } else null

                val newFinal: String
                val newNumber: Int?
                if (correction != null) {
                    newFinal = "TapNumber"; newNumber = correction.toNumber
                } else {
                    newFinal = oldFinal; newNumber = oldNumber
                }

                out.put(JSONObject()
                    .put("case_id", c.getString("case_id"))
                    .put("snapshot", snapshotCount)
                    .put("kind", kind)
                    .put("true_number", trueN ?: JSONObject.NULL)
                    .put("text_expected", c.getString("text_expected"))
                    .put("asr_text", text)
                    .put("old_final", oldFinal)
                    .put("old_number", oldNumber ?: JSONObject.NULL)
                    .put("new_final", newFinal)
                    .put("new_number", newNumber ?: JSONObject.NULL)
                    .put("num_top1", numTop.firstOrNull()?.first ?: "none")
                    .put("num_top1_prob", numTop.firstOrNull()?.second ?: 0.0)
                    .put("corrected", correction != null))
            }
        }

        val outDir = listOf(File("_test/m6/number_navigation_stage/out"),
            File("../_test/m6/number_navigation_stage/out"))
            .firstOrNull { it.isDirectory } ?: File("../_test/m6/number_navigation_stage/out")
        outDir.resolve(outputName).writeText(out.toString(1), Charsets.UTF_8)
        assertTrue(out.length() > 0)
        println("Number replay [$outputName]: ${out.length()} rows")
    }
}
