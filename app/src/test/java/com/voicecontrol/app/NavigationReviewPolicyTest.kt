package com.voicecontrol.app

import org.junit.Assert.*
import org.junit.Test

class NavigationReviewPolicyTest {
    private fun nav(action: String, p: Float = .985f) =
        (NavigationReviewPolicy.ACTIONS + "other").map { it to if (it == action) p else (1-p)/6 }
    @Test fun matchingHeadsMayRecoverShortMisTranscription() {
        assertEquals("swipe_right", NavigationReviewPolicy.candidate("又啊",
            listOf("swipe_right" to .999f), nav("swipe_right")))
    }
    @Test fun contradictoryHeadsDoNotGuessDirection() {
        assertNull(NavigationReviewPolicy.candidate("又啊",listOf("swipe_left" to .999f),nav("swipe_right")))
    }
    @Test fun invalidWeakAndOtherHeadsAbstain() {
        for (p in listOf(.98f,Float.NaN,Float.POSITIVE_INFINITY,1.01f))
            assertNull(NavigationReviewPolicy.candidate("又啊",listOf("swipe_right" to p),nav("swipe_right")))
        assertNull(NavigationReviewPolicy.candidate("又啊",listOf("swipe_right" to .999f),nav("swipe_right",.96f)))
        assertNull(NavigationReviewPolicy.candidate("又啊",listOf("swipe_right" to .999f),nav("other")))
    }
    @Test fun denialsDescriptionsQuestionsAndInputDoNotBecomeActions() {
        for (text in listOf("不要返回","不用返回","别回桌面","桌面在哪里","返回吗","我已经返回了", "输入返回", "听写桌面"))
            assertNull(text,NavigationReviewPolicy.candidate(text,listOf("go_back" to .999f),nav("go_back")))
    }
}
