package com.voicecontrol.app

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

class NumberPairAssetTest {
    @Test fun packedModelLabelsAndFeatureContractMatchFrozenPolicy() {
        val assets=File("src/main/assets")
        val model=OptionalExperimentFiles.requireFile("src/main/assets/nn_number_pair4_10.onnx")
        val meta=JSONObject(OptionalExperimentFiles.requireFile("src/main/assets/nn_number_pair4_10_meta.json").readText(Charsets.UTF_8))
        val sha=MessageDigest.getInstance("SHA-256").digest(model.readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals("85bc159b1e9a2f26e4849868aa51083b453065b148df0c1cf09495001fd01d43",sha)
        assertEquals(meta.getString("model_sha256"),sha)
        assertEquals(525782L,model.length());assertEquals(model.length(),meta.getLong("model_bytes"))
        assertEquals(NumberPairReviewPolicy.LABELS,meta.getJSONArray("labels").let { a->
            (0 until a.length()).map(a::getString) })
        assertEquals(NumberPairFeatures.DIMENSION,meta.getInt("input_dimension"))
        assertEquals(NumberPairFeatures.CONTROL_ROWS,meta.getInt("control_prefix_rows"))
        assertEquals(NumberPairFeatures.BINS,meta.getInt("bins"))
        assertEquals(NumberPairReviewPolicy.THRESHOLD,meta.getDouble("threshold").toFloat(),0f)
        assertEquals(NumberPairReviewPolicy.MIN_MARGIN,meta.getDouble("min_margin").toFloat(),0f)
        assertEquals(1.0,meta.getDouble("temperature"),0.0)
        assertTrue(AcousticReviewRegistry.numberCorrection.userScopeText.contains("4/10"))
        assertEquals(setOf("volume_up","volume_down"),AcousticReviewRegistry.modules.single().actions)
    }
}
