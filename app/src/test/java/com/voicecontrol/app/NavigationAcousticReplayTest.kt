package com.voicecontrol.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 只派发给假执行器，不接线手机；答案仅用于输出评分。 */
class NavigationAcousticReplayTest {
    private fun file(p: String) = OptionalExperimentFiles.requireFile(p)
    private fun pairs(a: JSONArray) = (0 until a.length()).map {
        a.getJSONObject(it).let { j -> j.getString("label") to j.getDouble("prob").toFloat() }
    }
    @Test fun developmentReplay() = replay("dev")
    @Test fun stressReplay() = replay("final")
    @Test fun freshReplay() = replay("fresh")
    private fun replay(split: String) {
        val f = file("_test/m6/number_acoustic_v3/out/navigation/replay_$split.json")
        val rows = JSONArray(f.readText(Charsets.UTF_8))
        val matcher = CommandMatcher.fromJson(file("src/main/assets/commands.json").readText(Charsets.UTF_8))
        val out = JSONArray()
        for (i in 0 until rows.length()) {
            val r = rows.getJSONObject(i); val text = r.getString("asr_text")
            for (status in SilentCommandRecovery.TextTapStatus.entries) {
                val plan = CommandRouting.planCommand(text, CommandRouting.UtteranceContext(false,false,true), matcher)
                val m6 = pairs(r.getJSONArray("m6_top")); val cursor = pairs(r.getJSONArray("cursor_top"))
                val volume = AudioReviewRequest.plan(text,true,true,false,false,false).volume
                fun reviewed(action: String) = AudioDecisionRouting.correctedAction(action,true,
                    if (volume) r.optString("volume_decision").takeIf { it in listOf("inc","dec") } else null)
                var baseline = "no_action"
                var sourceEligible = false
                when (plan) {
                    is CommandRouting.Decision.DispatchCommand -> baseline = reviewed(plan.action)
                    is CommandRouting.Decision.TapText, is CommandRouting.Decision.NoMatch -> {
                        val tap = if (plan is CommandRouting.Decision.TapText) status else SilentCommandRecovery.TextTapStatus.NOT_ATTEMPTED
                        if (tap == SilentCommandRecovery.TextTapStatus.DISPATCHED) baseline = "tap_text"
                        else {
                            val fuzzy = matcher.matchFuzzyDetailed(text)
                            if (!fuzzy.ambiguous && fuzzy.match != null) baseline = reviewed(fuzzy.match!!.action)
                            else {
                                val uid = "$split-$i-$status"
                                baseline = SilentCommandRecovery().attempt(plan,text,tap,fuzzy,
                                    SilentCommandRecovery.Context(true,true,true,uid,uid,true,true),m6,cursor) { true }?.action ?: "no_action"
                                sourceEligible = baseline == "no_action" && SilentCommandRecovery.eligibleSource(plan,text,tap,fuzzy)
                            }
                        }
                    }
                    is CommandRouting.Decision.Ambiguous -> Unit
                    else -> baseline = plan.javaClass.simpleName
                }
                val candidate = if (sourceEligible) NavigationReviewPolicy.candidate(text,m6,pairs(r.getJSONArray("nav_top"))) else null
                out.put(JSONObject().put("case_id",r.getString("case_id")).put("text_expected",r.getString("text"))
                    .put("asr_text",text).put("label",r.getString("label")).put("condition",r.getString("condition"))
                    .put("text_status",status.name).put("baseline",baseline).put("candidate",candidate ?: baseline)
                    .put("adopted",candidate != null))
            }
        }
        requireNotNull(f.parentFile).resolve("full_$split.json").writeText(out.toString(1),Charsets.UTF_8)
        assertEquals(rows.length() * SilentCommandRecovery.TextTapStatus.entries.size,out.length())
    }
    @Test fun denialAndTextTargetsNeverAdopt() {
        val nav = NavigationReviewPolicy.ACTIONS.map { it to (if (it == "go_back") .999f else 0f) } + ("other" to .001f)
        for (text in listOf("不要返回","返回吗","桌面在哪里","输入返回"))
            assertNull(NavigationReviewPolicy.candidate(text,listOf("go_back" to .999f),nav))
        val plan = CommandRouting.Decision.TapText("返回")
        assertFalse(SilentCommandRecovery.eligibleSource(plan,"点击返回",SilentCommandRecovery.TextTapStatus.NOT_FOUND,
            CommandMatcher.StrictOutcome(null,false)))
    }
}
