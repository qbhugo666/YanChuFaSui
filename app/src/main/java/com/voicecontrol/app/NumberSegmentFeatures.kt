package com.voicecontrol.app

/** 冻结encoder第二输出的特征契约；旧512维全帧均值保持不变。 */
internal object NumberSegmentFeatures {
    const val CONTROL_ROWS = 4
    const val BINS = 3
    const val CHANNELS = 512
    const val DIMENSION = BINS * CHANNELS

    fun fromEncoder(frames: Array<FloatArray>, lfrLength: Int): FloatArray? {
        // /Concat_2依次加四个控制行；实际长度不符合时只停用补充头。
        if (lfrLength < BINS || frames.size != lfrLength + CONTROL_ROWS ||
            frames.any { it.size != CHANNELS || it.any { value -> !value.isFinite() } }) return null
        val features = FloatArray(DIMENSION)
        val width = lfrLength / BINS
        val remainder = lfrLength % BINS
        var start = CONTROL_ROWS
        for (bin in 0 until BINS) {
            // 等同numpy.array_split：余数依次给前面的分段。
            val length = width + if (bin < remainder) 1 else 0
            for (t in start until start + length) for (i in 0 until CHANNELS)
                features[bin * CHANNELS + i] += frames[t][i]
            for (i in 0 until CHANNELS) features[bin * CHANNELS + i] /= length.toFloat()
            start += length
        }
        return features
    }
}
