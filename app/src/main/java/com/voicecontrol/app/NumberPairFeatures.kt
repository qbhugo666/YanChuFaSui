package com.voicecontrol.app

/** 4/10头的冻结特征：真实encoder第二输出，四个有序声学分段，不含控制行。 */
internal object NumberPairFeatures {
    const val CONTROL_ROWS = NumberSegmentFeatures.CONTROL_ROWS
    const val CHANNELS = NumberSegmentFeatures.CHANNELS
    const val BINS = 4
    const val DIMENSION = CHANNELS * BINS

    fun fromEncoder(frames: Array<FloatArray>, lfrLength: Int): FloatArray? {
        if (lfrLength < BINS || frames.size != lfrLength + CONTROL_ROWS ||
            frames.any { it.size != CHANNELS || it.any { value -> !value.isFinite() } }) return null
        val output = FloatArray(DIMENSION)
        val width = lfrLength / BINS
        val remainder = lfrLength % BINS
        var start = CONTROL_ROWS
        for (bin in 0 until BINS) {
            val length = width + if (bin < remainder) 1 else 0
            for (t in start until start + length) for (i in 0 until CHANNELS)
                output[bin * CHANNELS + i] += frames[t][i]
            for (i in 0 until CHANNELS) output[bin * CHANNELS + i] /= length.toFloat()
            start += length
        }
        return output
    }
}
