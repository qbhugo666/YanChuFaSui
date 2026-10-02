package com.voicecontrol.app

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 同PCM设备输出走生产判断；探针没有点击手机，派发仅由本测试假执行器记录。 */
class NumberPairDeviceReplayTest {
    private fun file(path:String)=OptionalExperimentFiles.requireFile(path)
    private fun pairs(a:JSONArray)= (0 until a.length()).map { i->a.getJSONObject(i).let {
        it.getString("label") to it.getDouble("prob").toFloat() } }
    private fun pairs(s:String)=s.split(',').map { v->v.split(':').let { it[0] to it[1].toFloat() } }

    @Test fun deviceLoadedHeadAndReopenKeepActualNumberDecisionsAndShowNewGain() {
        val base="_test/m6/number_acoustic_v3/out/pair4_10/device_probe/"
        val pc=JSONArray(file(base+"pc.json").readText(Charsets.UTF_8)).let { a->
            (0 until a.length()).associate { i->a.getJSONObject(i).let { it.getString("file") to it } } }
        val matcher=CommandMatcher.fromJson(file("src/main/assets/commands.json").readText(Charsets.UTF_8))
        val snapshot=NumberReviewContext.Snapshot(1,"page",(1..30).map { "target$it" })
        for (name in listOf("device.json","device_reopen.json")) {
            val device=JSONArray(file(base+name).readText(Charsets.UTF_8));val output=JSONArray();var newGains=0
            for (i in 0 until device.length()) {
                val row=device.getJSONObject(i);val source=pc.getValue(row.getString("file"))
                val pPair=pairs(source.getJSONArray("num_pair_top"));val dPair=pairs(row.getString("num_pair_top"))
                for (values in listOf(pPair,dPair)) {
                    assertEquals(NumberPairReviewPolicy.LABELS.toSet(),values.map { it.first }.toSet())
                    assertEquals(3,values.size)
                    assertTrue(values.all { it.second.isFinite() && it.second in 0f..1f })
                    assertEquals(1.0,values.sumOf { it.second.toDouble() },.001)
                }
                val text=source.getString("asr_text")
                val plan=CommandRouting.planCommand(text,CommandRouting.UtteranceContext(false,true,true,
                    visibleLabelCount=30),matcher)
                if (plan !is CommandRouting.Decision.TapNumber) continue
                val uid=source.getString("file");val request=NumberReviewContext.Request(uid,1,snapshot)
                val live=NumberReviewContext.Live(uid,1,true,true,true,snapshot)
                fun run(audio:List<Pair<String,Float>>,pair:List<Pair<String,Float>>?):NumberTapDispatcher.Result {
                    val calls=mutableListOf<Int>()
                    val result=NumberTapDispatcher().dispatch(plan.number,text,audio,request,live,pairAudio=pair) {
                        calls.add(it);it in 1..30 }
                    assertEquals(1,calls.size);return result
                }
                val pNum=pairs(source.getJSONArray("num_top"));val dNum=pairs(row.getString("num_top"))
                val pBase=run(pNum,null);val dBase=run(dNum,null)
                val pResult=run(pNum,pPair);val dResult=run(dNum,dPair)
                assertEquals(uid,pResult.number,dResult.number)
                assertEquals(uid,pResult.dispatched,dResult.dispatched)
                assertEquals(uid,NumberPairReviewPolicy.correctBeforeTap(plan.number,text,30,pPair),
                    NumberPairReviewPolicy.correctBeforeTap(plan.number,text,30,dPair))
                if (dResult.number!=dBase.number) {
                    assertEquals("device introduced a changed error",source.getInt("true_number"),dResult.number)
                    newGains++
                }
                output.put(JSONObject().put("file",uid).put("text_number",plan.number)
                    .put("pc_base",pBase.number).put("device_base",dBase.number)
                    .put("pc_reviewed",pResult.number).put("device_reviewed",dResult.number)
                    .put("device_new_gain",dResult.number!=dBase.number))
            }
            assertTrue("device head must actually add a correction, not merely load",newGains>0)
            file(base+name).parentFile.resolve("decisions_"+name).writeText(output.toString(1),Charsets.UTF_8)
        }
    }
}
