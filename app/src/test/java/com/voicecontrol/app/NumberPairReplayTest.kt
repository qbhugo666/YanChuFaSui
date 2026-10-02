package com.voicecontrol.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 4/10实验沿完整当前ON；只调用生产策略，答案仅写出供评分。 */
class NumberPairReplayTest {
    private fun file(path:String)=OptionalExperimentFiles.requireFile(path)
    private fun pairs(a:JSONArray?):List<Pair<String,Float>>?=a?.let {
        (0 until it.length()).map { i->it.getJSONObject(i).let { o->o.getString("label") to o.getDouble("prob").toFloat() } }
    }
    @Test fun cachedDevelopmentReplay() {
        for (split in listOf("dev_validation","dev_fresh_final","v2_dev_validation","v2_dev_fresh_final",
            "v2_dev_first_final")) replay(split)
    }
    @Test fun frozenNewAudioReplay() { replay("final1") }
    @Test fun secondFrozenNewAudioHasNewCorrectNumbersAndNoChangedErrors() {
        val output=replay("final2")
        val rows=JSONArray(output.readText(Charsets.UTF_8));val gained=mutableSetOf<String>()
        for (i in 0 until rows.length()) {
            val row=rows.getJSONObject(i);val results=row.getJSONObject("results")
            val old=results.getJSONObject("current").getString("final")
            val reviewed=results.getJSONObject("pair").getString("final")
            if (old==reviewed) continue
            assertEquals("control changed: ${row.getString("case_id")}","pos",row.getString("kind"))
            val number=row.getInt("true_number")
            assertTrue("absent target changed",number in 1..row.getInt("count"))
            assertEquals("changed error: ${row.getString("case_id")}","TapNumber:$number",reviewed)
            gained.add(row.getString("case_id"))
        }
        assertTrue("zero adoption is not correction evidence",gained.isNotEmpty())
    }
    private fun replay(split:String):File {
        val input=file("_test/m6/number_acoustic_v3/out/pair4_10/replay_$split.json")
        val root = requireNotNull(input.canonicalFile.parentFile?.parentFile?.parentFile?.parentFile)
            .resolve("number_override_safety/replay/pair")
        root.mkdirs()
        val output=root.resolve("full_$split.json")
        replayTo(input,output)
        return output
    }
    /** 新验收也沿同一完整旧链；输出隔离，历史full_*.json不可覆盖。 */
    internal fun replayTo(input:File,output:File,includeSuffix:Boolean=false,
                          includeNavigationProtection:Boolean=false,
                          counts:List<Int> = listOf(20,30,80,0),
                          textStatuses:List<SilentCommandRecovery.TextTapStatus> = listOf(
                              SilentCommandRecovery.TextTapStatus.DISPATCHED, SilentCommandRecovery.TextTapStatus.NOT_FOUND,
                              SilentCommandRecovery.TextTapStatus.FAILED, SilentCommandRecovery.TextTapStatus.UNAVAILABLE)) {
        val cases=JSONArray(input.readText(Charsets.UTF_8))
        val matcher=CommandMatcher.fromJson(file("src/main/assets/commands.json").readText(Charsets.UTF_8))
        var written=0
        output.parentFile?.mkdirs()
        output.bufferedWriter(Charsets.UTF_8).use { writer->
            writer.write("[")
            for (i in 0 until cases.length()) {
                val c=cases.getJSONObject(i);val text=c.getString("asr_text")
                for (count in counts) for (status in textStatuses) {
                    val plan=CommandRouting.planCommand(text,CommandRouting.UtteranceContext(false,count>0,true,
                        visibleLabelCount=count.takeIf { it>0 }),matcher)
                    val snapshot=count.takeIf { it>0 }?.let { NumberReviewContext.Snapshot(1,"page",(1..it).map { n->"target$n" }) }
                    val uid="${c.getString("case_id")}-$count-$status"
                    val req=NumberReviewContext.Request(uid,1,snapshot)
                    val live=NumberReviewContext.Live(uid,1,true,true,true,snapshot)
                    val results=JSONObject()
                    val sides = listOf("off","current","pair") +
                        if (includeSuffix) listOf("suffix_off","suffix") else emptyList()
                    for (side in sides) {
                        val enabled=side!="off" && side!="suffix_off"
                        val suffix=side=="suffix" || side=="suffix_off"
                        val candidate=side=="pair" || suffix
                        val volume=AudioReviewRequest.plan(text,enabled,true,false,false,false).volume
                        fun reviewed(action:String)=AudioDecisionRouting.correctedAction(action,enabled,
                            if (volume) c.optString("volume_decision").takeIf { it in listOf("inc","dec") } else null)
                        val calls=mutableListOf<String>();var final="no_action";var corrected=false
                        fun dispatchMatch(match:CommandMatcher.Match) {
                            val action=reviewed(match.action)
                            val outcome=NavigationIntentGuard.dispatch(text,match,enabled && includeNavigationProtection,
                                true,matcher.isCustomMatch(match)) { calls.add(action);true }
                            if(outcome.dispatched) final=action
                        }
                        var suffixRestored=false;var replacedShowLabels=false
                        when (plan) {
                            is CommandRouting.Decision.TapNumber->{
                                val result=NumberTapDispatcher().dispatch(plan.number,text,
                                    if (enabled) pairs(c.getJSONObject("tops").getJSONArray("current")) else null,req,live.copy(enabled=enabled),
                                    pairAudio=if (candidate && enabled) pairs(c.optJSONArray("pair_top")) else null,
                                    confirmation=if (enabled) pairs(c.optJSONArray("installed_segment_top")) else null) { n->
                                    if (n in 1..count || count==0) { calls.add("TapNumber:$n");true } else false
                                }
                                corrected=result.correction!=null
                                if (result.dispatched) final="TapNumber:${result.number}"
                            }
                            is CommandRouting.Decision.DispatchCommand->dispatchMatch(plan.match)
                            is CommandRouting.Decision.TapText,is CommandRouting.Decision.NoMatch->{
                                val tap=if (plan is CommandRouting.Decision.TapText) status else SilentCommandRecovery.TextTapStatus.NOT_ATTEMPTED
                                if (tap==SilentCommandRecovery.TextTapStatus.DISPATCHED) { final="tap_text";calls.add(final) }
                                else {
                                    val fuzzy=matcher.matchFuzzyDetailed(text)
                                    val restored=if (suffix) NumberSuffixRecovery().attempt(plan,text,tap,fuzzy,matcher,
                                        labelsVisible=count>0,gridShowing=false,request=req,live=live.copy(enabled=enabled),
                                        audio=if (enabled) pairs(c.getJSONObject("tops").getJSONArray("current")) else null,
                                        pairAudio=if (enabled) pairs(c.optJSONArray("pair_top")) else null,
                                        confirmation=if (enabled) pairs(c.optJSONArray("installed_segment_top")) else null) { n->
                                            if (n in 1..count) { calls.add("TapNumber:$n");true } else false
                                        } else null
                                    if (restored!=null) {
                                        suffixRestored=restored.dispatch.attempted
                                        replacedShowLabels=restored.replacedShowLabels
                                        corrected=restored.dispatch.correction!=null
                                        if (restored.dispatch.dispatched) final="TapNumber:${restored.dispatch.number}"
                                    } else if (!fuzzy.ambiguous && fuzzy.match!=null) {
                                        // 单句回放没有连续三次近似退出的会话史；保持生产首次危险模糊命中拒绝。
                                        val match=fuzzy.match!!
                                        if (match.method!="pinyin_fuzzy" || match.action !in setOf("exit_session","lock_screen")) {
                                            dispatchMatch(match)
                                        }
                                    } else if (enabled) {
                                        val old=SilentCommandRecovery().attempt(plan,text,tap,fuzzy,
                                            SilentCommandRecovery.Context(true,true,true,uid,uid,true,CursorReviewPolicy.ENABLED),
                                            pairs(c.optJSONArray("m6_top")),pairs(c.optJSONArray("cursor_top"))) { calls.add(it);true }
                                        if (old!=null) final=old.action else {
                                            val n=NumberSilentRecovery().attempt(plan,text,tap,fuzzy,req,live,
                                                pairs(c.optJSONArray("installed_segment_top"))) { calls.add("TapNumber:$it");true }
                                            if (n?.dispatched==true) final="TapNumber:${n.number}"
                                        }
                                    }
                                }
                            }
                            is CommandRouting.Decision.Ambiguous->Unit
                            else->{final=plan.javaClass.simpleName;calls.add(final)}
                        }
                        assertTrue("multiple dispatches $uid",calls.size<=1)
                        results.put(side,JSONObject().put("final",final)
                            .put("corrected",corrected).put("dispatch_count",calls.size)
                            .put("suffix_restored",suffixRestored).put("replaced_show_labels",replacedShowLabels))
                    }
                    val row=JSONObject().put("case_id",c.getString("case_id")).put("text_expected",c.optString("text_expected"))
                        .put("asr_text",text).put("true_number",c.opt("true_number")).put("kind",c.getString("kind"))
                        .put("count",count).put("text_status",status.name).put("condition",c.optString("condition","clean"))
                        .put("plan",plan.javaClass.simpleName).put("pair_top",c.optJSONArray("pair_top")).put("results",results)
                    if (written++>0) writer.write(",\n")
                    writer.write(row.toString())
                }
            }
            writer.write("]")
        }
        assertEquals(cases.length()*counts.size*textStatuses.size,written)
    }
}
