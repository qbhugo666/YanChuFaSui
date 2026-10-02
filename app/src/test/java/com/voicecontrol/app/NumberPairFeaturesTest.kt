package com.voicecontrol.app

import org.junit.Assert.*
import org.junit.Test

class NumberPairFeaturesTest {
    @Test fun excludesControlsAndPreservesOrderedDigitEvidence() {
        val controls=Array(4) { FloatArray(512) { 999f } }
        val acoustic=Array(10) { t->FloatArray(512) { (t+1).toFloat() } }
        val result=NumberPairFeatures.fromEncoder(controls+acoustic,10)!!
        assertEquals(2048,result.size)
        for ((bin,value) in listOf(2f,5f,7.5f,9.5f).withIndex())
            for (channel in 0 until 512) assertEquals(value,result[bin*512+channel],0f)
    }
    @Test fun rejectsShortMismatchedAndNonFiniteEncoderOutputs() {
        assertNull(NumberPairFeatures.fromEncoder(Array(7) { FloatArray(512) },3))
        assertNull(NumberPairFeatures.fromEncoder(Array(9) { FloatArray(512) },4))
        assertNull(NumberPairFeatures.fromEncoder(Array(8) { FloatArray(511) },4))
        val broken=Array(8) { FloatArray(512) };broken[6][1]=Float.NaN
        assertNull(NumberPairFeatures.fromEncoder(broken,4))
    }
}
