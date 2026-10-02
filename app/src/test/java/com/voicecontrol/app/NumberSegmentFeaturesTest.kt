package com.voicecontrol.app

import org.junit.Assert.*
import org.junit.Test

class NumberSegmentFeaturesTest {
    @Test fun excludesFourControlRowsAndMatchesUnevenThreeBinContract() {
        val frames = Array(12) { t -> FloatArray(512) { if (t < 4) 1000f else (t-4).toFloat() } }
        val features = NumberSegmentFeatures.fromEncoder(frames,8)!!
        assertEquals(1536,features.size)
        assertEquals(1f,features[0],0f) // acoustic 0,1,2
        assertEquals(4f,features[512],0f) // acoustic 3,4,5
        assertEquals(6.5f,features[1024],0f) // acoustic 6,7
    }
    @Test fun tooShortWrongLengthWrongChannelOrNonFiniteCannotPretendToBeFeatures() {
        assertNull(NumberSegmentFeatures.fromEncoder(Array(6){FloatArray(512)},2))
        assertNull(NumberSegmentFeatures.fromEncoder(Array(8){FloatArray(512)},3))
        assertNull(NumberSegmentFeatures.fromEncoder(Array(7){FloatArray(511)},3))
        assertNull(NumberSegmentFeatures.fromEncoder(Array(7){FloatArray(512){Float.NaN}},3))
    }
}
