package com.voicecontrol.app

/** 2026-10-01：4/10声学对照，实验与生产共用；保留旧数字头已经采纳的判决。 */
internal object NumberPairReviewPolicy {
    val LABELS = listOf("4", "10", "other")
    const val THRESHOLD = .995f
    const val MIN_MARGIN = .99f
    private val protectedText = Regex("不|没|别|勿|吗|呢|怎么|哪里|是否|重复|长按|网格|输入|听写|显示|取消|停止")

    fun correctBeforeTap(textNumber: Int, text: String, snapshotCount: Int?,
                         audio: List<Pair<String, Float>>?): NumberReviewPolicy.Correction? {
        if (textNumber !in listOf(4, 10) || snapshotCount == null ||
            textNumber !in 1..snapshotCount || text.length !in 1..12 || protectedText.containsMatchIn(text)) return null
        val values = audio ?: return null
        if (values.size != LABELS.size || values.map { it.first }.toSet() != LABELS.toSet() ||
            values.any { !it.second.isFinite() || it.second !in 0f..1f } ||
            kotlin.math.abs(values.sumOf { it.second.toDouble() } - 1.0) > .001) return null
        // 需要完整拒识概率，不能把33类的4/10两项重新归一制造高分。
        val ranked = values.sortedByDescending { it.second }
        val top = ranked[0]
        val number = top.first.toIntOrNull() ?: return null
        if (number !in listOf(4, 10) || number == textNumber || number !in 1..snapshotCount ||
            top.second < THRESHOLD || top.second - ranked[1].second < MIN_MARGIN) return null
        return NumberReviewPolicy.Correction(textNumber, number)
    }
}
