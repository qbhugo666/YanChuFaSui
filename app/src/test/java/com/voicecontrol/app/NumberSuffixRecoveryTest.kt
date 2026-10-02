package com.voicecontrol.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class NumberSuffixRecoveryTest {
    private fun file(path: String) = OptionalExperimentFiles.requireFile(path)
    private val commands by lazy { file("src/main/assets/commands.json").readText(Charsets.UTF_8) }
    private val matcher by lazy { CommandMatcher.fromJson(commands) }
    private val snapshot = NumberReviewContext.Snapshot(1,"page",(1..30).map { "target$it" })
    private val request = NumberReviewContext.Request("u1",1,snapshot)
    private val live = NumberReviewContext.Live("u1",1,true,true,true,snapshot)
    private val missing = SilentCommandRecovery.TextTapStatus.NOT_FOUND
    private val context = CommandRouting.UtteranceContext(false,true,true,visibleLabelCount=30)
    private val noMatch = CommandMatcher.StrictOutcome(null,false)

    private fun chinese(number: Int): String {
        val d="零一二三四五六七八九"
        return if (number<10) d[number].toString() else
            (if (number>=20) d[number/10].toString() else "")+"十"+
                (if (number%10>0) d[number%10].toString() else "")
    }

    private fun recover(text: String="八号", owner: NumberSuffixRecovery=NumberSuffixRecovery(),
                        status: SilentCommandRecovery.TextTapStatus=missing,
                        fuzzy: CommandMatcher.StrictOutcome=noMatch, m: CommandMatcher=matcher,
                        req: NumberReviewContext.Request?=request, state: NumberReviewContext.Live=live,
                        labels: Boolean=true, grid: Boolean=false,
                        audio: List<Pair<String,Float>>?=null, pair: List<Pair<String,Float>>?=null,
                        plan: CommandRouting.Decision=CommandRouting.planCommand(text,context,m),
                        tap: (Int)->Boolean={ true }) = owner.attempt(plan,text,status,fuzzy,m,labels,grid,
                            req,state,audio,pair,confirmation=audio,tap=tap)

    @Test fun allCanonicalNumbersOneToThirtyUseActualProductionPlan() {
        for (number in 1..30) for (text in listOf("${chinese(number)}号","${number}号")) {
            assertEquals(text,number,NumberSuffixRecovery.parseNumber(text))
            val plan=CommandRouting.planCommand(text,context,matcher)
            assertTrue("$text plan=$plan",plan is CommandRouting.Decision.TapText)
            val calls=mutableListOf<Int>()
            // 语法覆盖先验证无命令场景；真实词表若已有其他动作，必须保持原动作优先。
            val outcome=recover(text,fuzzy=noMatch,plan=plan) { calls.add(it);true }
            assertNotNull(text,outcome)
            assertEquals(text,number,outcome!!.dispatch.number)
            assertTrue(text,outcome.dispatch.dispatched)
            assertEquals(listOf(number),calls)
            val actualFuzzy=matcher.matchFuzzyDetailed(text)
            val actual=recover(text,fuzzy=actualFuzzy,plan=plan)
            if(actualFuzzy.ambiguous || actualFuzzy.match?.let {
                    it.action!="show_labels" || it.method!="pinyin_fuzzy" }==true)
                assertNull("$text retains $actualFuzzy",actual)
            else assertEquals(text,number,actual!!.dispatch.number)
        }
        assertEquals(2,NumberSuffixRecovery.parseNumber("两号"))
    }

    @Test fun onlyCompleteNumberSuffixCanEnter() {
        for (text in listOf("零号","0号","31号","三十一号","76号","七十六号","四百号","111号",
            "01号","一一号","十吧号","吧号","六路号","八号吗","不要八号","八号？","八号。",
            "八号房","八号按钮","八号和十号","八 号","重复八次","长按八号","第八号","点击八号",
            "显示编号","今天八号","输入八号","八号车","每月八号")) {
            assertNull(text,NumberSuffixRecovery.parseNumber(text))
            assertNull(text,recover(text,plan=CommandRouting.Decision.TapText(text)) { fail(text);true })
        }
        for (plan in listOf(CommandRouting.Decision.NoMatch("test"),CommandRouting.Decision.TapNumber(8),
            CommandRouting.Decision.LongPressNumber(8),CommandRouting.Decision.Repeat(8),
            CommandRouting.Decision.Ambiguous(listOf("八","十"))))
            assertNull(recover(plan=plan) { fail("wrong plan");true })
    }

    @Test fun successfulTextUnavailableQueryAndFailedGestureKeepOldPath() {
        for (status in SilentCommandRecovery.TextTapStatus.entries.filter { it!=missing })
            assertNull("$status",recover(status=status) { fail("$status");true })
    }

    @Test fun onlyUniqueStandardFuzzyShowLabelsOrNoMatchCanBeReplaced() {
        val show=matcher.matchFuzzyDetailed("八号")
        assertEquals("show_labels",show.match?.action)
        assertEquals("pinyin_fuzzy",show.match?.method)
        assertTrue(recover(fuzzy=show)!!.replacedShowLabels)
        assertFalse(recover(fuzzy=noMatch)!!.replacedShowLabels)
        assertNull(recover(fuzzy=CommandMatcher.StrictOutcome(null,true,listOf("编号","八号"))))
        for (word in listOf("显示编号","返回","退出","锁屏"))
            assertNull(word,recover(fuzzy=matcher.matchStrictDetailed(word)))
    }

    @Test fun customStandardIdAndFuzzyBindingsRetainOwnership() {
        for (action in listOf("show_labels","go_back","tap_number_6")) {
            val m=CommandMatcher.fromJson(commands,customBindings=listOf("八号" to action))
            val exact=m.matchStrictDetailed("八号")
            assertNotNull(exact.match)
            assertTrue(m.isCustomMatch(exact.match!!))
            assertNull(recover(m=m,fuzzy=exact,plan=CommandRouting.Decision.TapText("八号")))
        }
        // 标准id/group复用时也必须识别绑定，不能靠custom_前缀。
        val m=CommandMatcher.fromJson(commands,customBindings=listOf("爸号" to "show_labels"))
        val match=CommandMatcher.Match("show_labels","show_labels","system","爸号","pinyin_fuzzy")
        assertTrue(m.isCustomMatch(match))
        assertNull(recover(m=m,fuzzy=CommandMatcher.StrictOutcome(match,false),
            plan=CommandRouting.Decision.TapText("八号")))
    }

    @Test fun modeSwitchSessionUtteranceAndTargetIdentityMustStillMatch() {
        for (state in listOf(live.copy(enabled=false),live.copy(active=false),live.copy(normalMode=false),
            live.copy(uid="u2"),live.copy(generation=2),live.copy(snapshot=null),
            live.copy(snapshot=snapshot.copy(targets=emptyList())),
            live.copy(snapshot=snapshot.copy(windowId=2)),
            live.copy(snapshot=snapshot.copy(targets=snapshot.targets.reversed()))))
            assertNull(recover(state=state) { fail("$state");true })
        assertNull(recover(req=null))
        assertNull(recover(req=request.copy(uid="")))
        assertNull(recover(labels=false))
        assertNull(recover(grid=true))
        val small=snapshot.copy(targets=snapshot.targets.take(7))
        assertNull(recover(req=request.copy(snapshot=small),state=live.copy(snapshot=small)))
    }

    @Test fun failureAndRepeatedUidAreConsumedBeforeAnyFallback() {
        val owner=NumberSuffixRecovery();val calls=mutableListOf<Int>()
        val first=recover(owner=owner,fuzzy=matcher.matchFuzzyDetailed("八号")) { calls.add(it);false }
        assertTrue(first!!.dispatch.attempted)
        assertFalse(first.dispatch.dispatched)
        val second=recover(owner=owner) { calls.add(it);true }
        assertNotNull(second)
        assertFalse(second!!.dispatch.attempted)
        assertEquals("already_handled",second.dispatch.rejection)
        assertEquals(listOf(8),calls)
        val newer=recover(owner=owner,req=request.copy(uid="u2"),state=live.copy(uid="u2")) { calls.add(it);true }
        assertTrue(newer!!.dispatch.dispatched)
        // 迟到的旧句仍然被上下文拦截。
        assertNull(recover(owner=owner,state=live.copy(uid="u2")) { fail("stale");true })
    }

    @Test fun oldNumberAndFourTenPoliciesStillDecideBeforeFirstTap() {
        val numeric=(1..30).map { it.toString() }+listOf("out_of_range","non_number_click","other")
        fun head(n: String,p: Float)=numeric.map { it to when(it) { n->p;"other"->1f-p;else->0f } }
            .sortedByDescending { it.second }
        fun pair(n: String,p: Float)=NumberPairReviewPolicy.LABELS.map { it to when(it) { n->p;"other"->1f-p;else->0f } }
        assertEquals(18,recover(audio=head("18",.99f))!!.dispatch.number)
        assertEquals(8,recover(audio=head("18",.94f))!!.dispatch.number)
        assertEquals(10,recover(text="四号",audio=head("4",.5f),pair=pair("10",.999f))!!.dispatch.number)
        // 原头提案与4/10范围冲突时保留原编号，不能采纳后再反向补点。
        assertEquals(4,recover(text="四号",audio=head("10",.99f),pair=pair("4",.999f))!!.dispatch.number)
        assertEquals(8,recover(audio=head("18",Float.NaN))!!.dispatch.number)
    }

    @Test fun completeCachedReplayProvesGainAndOffCompatibility() {
        val root=file("_test/m6/number_route_recovery/acceptance/replay.json").canonicalFile.parentFile!!.parentFile!!.parentFile!!
        val output=root.resolve("number_suffix_recovery");output.mkdirs()
        for ((name,input) in listOf("old_development" to file("_test/m6/number_acoustic_v3/out/pair4_10/replay_final2.json"),
            "analysed_batch" to file("_test/m6/number_route_recovery/acceptance/replay.json"))) {
            val destination=output.resolve("full_$name.json")
            NumberPairReplayTest().replayTo(input,destination,includeSuffix=true)
            val rows=JSONArray(destination.readText(Charsets.UTF_8))
            val mainGains=mutableSetOf<String>();var changed=0;var candidates=0;var numberConditions=0;var oldCorrect=0;var newCorrect=0
            val changes=JSONArray()
            for(i in 0 until rows.length()) {
                val row=rows.getJSONObject(i);val results=row.getJSONObject("results")
                val before=results.getJSONObject("pair").getString("final")
                val after=results.getJSONObject("suffix").getString("final")
                assertEquals("OFF drift ${row.getString("case_id")}",results.getJSONObject("off").getString("final"),
                    results.getJSONObject("suffix_off").getString("final"))
                assertTrue(results.getJSONObject("suffix").getInt("dispatch_count")<=1)
                val main=row.getInt("count")==30 && row.getString("text_status")==missing.name
                if(main && row.getString("kind")=="pos") {
                    numberConditions++
                    val correct="TapNumber:${row.getInt("true_number")}";if(before==correct) oldCorrect++;if(after==correct) newCorrect++
                }
                if(main && results.getJSONObject("suffix").getBoolean("suffix_restored")) candidates++
                if(before==after) continue
                changed++
                assertEquals("changed control ${row.getString("case_id")}","pos",row.getString("kind"))
                assertEquals("new wrong ${row.getString("case_id")}","TapNumber:${row.getInt("true_number")}",after)
                assertEquals(missing.name,row.getString("text_status"))
                if(main) { mainGains.add(row.getString("case_id"));changes.put(row) }
            }
            val raw=mainGains.map { it.substringBeforeLast('-') }.toSet()
            val summary=JSONObject().put("source",name).put("evidence","analysed_cached_synthetic_development")
                .put("contexts",rows.length()).put("numeric_conditions",numberConditions).put("old_correct",oldCorrect)
                .put("new_correct",newCorrect).put("main_gain_conditions",mainGains.size).put("main_gain_raw",raw.size)
                .put("main_adoptions",candidates).put("changed_contexts",changed).put("new_errors",0).put("off_changes",0).put("changes",changes)
            output.resolve("summary_$name.json").writeText(summary.toString(2),Charsets.UTF_8)
            if(name=="analysed_batch") assertTrue("gain gate requires five distinct raw utterances, actual=${raw.size}",raw.size>=5)
        }
    }
}
