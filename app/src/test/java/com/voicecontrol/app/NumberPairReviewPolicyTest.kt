package com.voicecontrol.app

import org.junit.Assert.*
import org.junit.Test

class NumberPairReviewPolicyTest {
    private fun sound(n: Int, p: Float = .999f) = listOf(n.toString() to p,
        (if (n == 4) "10" else "4") to ((1f-p)/2), "other" to ((1f-p)/2))
    private val snapshot = NumberReviewContext.Snapshot(1,"page",(1..20).map { "target$it" })
    private val request = NumberReviewContext.Request("g1-u1",1,snapshot)
    private val live = NumberReviewContext.Live("g1-u1",1,true,true,true,snapshot)

    @Test fun bothDirectionsBeforeFirstTapAndFailureNeverRetries() {
        for ((old,new) in listOf(10 to 4,4 to 10)) {
            val calls=mutableListOf<Int>();val dispatcher=NumberTapDispatcher()
            val r=dispatcher.dispatch(old,"点击$old",null,request,live,pairAudio=sound(new)) { calls.add(it);false }
            assertEquals(new,r.number);assertFalse(r.dispatched);assertEquals(listOf(new),calls)
            assertFalse(dispatcher.dispatch(old,"点击$old",null,request,live,pairAudio=sound(new)) { calls.add(it);true }.attempted)
            assertEquals(listOf(new),calls)
        }
    }
    @Test fun conflictingPairCannotOverwriteTextViaGeneralProposal() {
        val r=NumberTapDispatcher().dispatch(10,"点击十",listOf("4" to .999f),request,live,
            pairAudio=sound(10),confirmation=listOf("4" to .999f)) { true }
        assertEquals(10,r.number)
        assertEquals("number_pair_domain_conflict",r.rejection)
    }
    @Test fun protectsOtherNumbersMissingTargetsAndNegativeSpeech() {
        for (n in listOf(0,1,8,14,18,20,30,40,76))
            assertNull(NumberPairReviewPolicy.correctBeforeTap(n,"点击$n",80,sound(4)))
        assertNull(NumberPairReviewPolicy.correctBeforeTap(4,"点击四",9,sound(10)))
        for (text in listOf("不要点击四","不是点击十","点击四吗","长按四","重复十次","网格四","输入四"))
            assertNull(NumberPairReviewPolicy.correctBeforeTap(10,text,30,sound(4)))
    }
    @Test fun rejectsUncertainMalformedAndForcedBinaryProbabilities() {
        assertNull(NumberPairReviewPolicy.correctBeforeTap(10,"点击十",30,sound(4,.99f)))
        for (p in listOf(Float.NaN,Float.POSITIVE_INFINITY,-.1f,1.01f))
            assertNull(NumberPairReviewPolicy.correctBeforeTap(10,"点击十",30,sound(4,p)))
        assertNull(NumberPairReviewPolicy.correctBeforeTap(10,"点击十",30,listOf("4" to .999f,"10" to .001f)))
        assertNull(NumberPairReviewPolicy.correctBeforeTap(10,"点击十",30,listOf("4" to .999f,"10" to .9f,"other" to .1f)))
    }
    @Test fun inFlightOffStalePageSentenceAndModeKeepOriginalTap() {
        for (state in listOf(live.copy(enabled=false),live.copy(uid="g1-u2"),live.copy(generation=2),
            live.copy(normalMode=false),live.copy(snapshot=snapshot.copy(targets=snapshot.targets.reversed())))) {
            val calls=mutableListOf<Int>()
            NumberTapDispatcher().dispatch(10,"点击十",null,request,state,pairAudio=sound(4)) { calls.add(it);true }
            assertEquals(listOf(10),calls)
        }
    }
}
