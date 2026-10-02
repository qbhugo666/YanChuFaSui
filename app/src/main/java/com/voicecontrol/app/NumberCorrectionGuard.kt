package com.voicecontrol.app

/** 2026-10-02：高分单头也会把正确编号改错。改号前复用已有分段输出交叉检查。
 * 共用encoder的头不是独立投票，也不把一致/高分称为正确证明。
 * 完整文字需要分段高分(.99原采纳线)确认；部分文字沿原.80证据线并加一致性约束。
 * 不以逐条答案选阈值；缺失、低分、冲突均保留文字编号。
 */
internal object NumberCorrectionGuard {
    const val CONFIRMATION_THRESHOLD = .99f

    fun rejection(correction: NumberReviewPolicy.Correction,
                  segments: List<Pair<String, Float>>?,
                  pair: List<Pair<String, Float>>?, textCleanParse: Boolean): String? {
        val segment = top(segments) ?: return "number_confirmation_unavailable"
        val threshold = if (textCleanParse) CONFIRMATION_THRESHOLD else NumberReviewPolicy.ADOPT_THRESHOLD
        if (segment.second < threshold) return "number_confirmation_low"
        if (segment.first != correction.toNumber.toString()) return "number_heads_conflict"

        // 4/10/other是范围判断：候选18应归other，不能一头说18、另一头仍说10时改号。
        // 用原三类概率，绝不抽取4/10后重新归一。专用头自己的严格4↔10路径另行保留。
        if (correction.fromNumber in setOf(4, 10) || correction.toNumber in setOf(4, 10)) {
            val values = pair ?: return "number_pair_confirmation_unavailable"
            if (values.size != 3 || values.map { it.first }.toSet() != NumberPairReviewPolicy.LABELS.toSet() ||
                values.any { !it.second.isFinite() || it.second !in 0f..1f } ||
                kotlin.math.abs(values.sumOf { it.second.toDouble() } - 1.0) > .001)
                return "number_pair_confirmation_unavailable"
            val domain = if (correction.toNumber in setOf(4, 10)) correction.toNumber.toString() else "other"
            if (values.maxBy { it.second }.first != domain) return "number_pair_domain_conflict"
        }
        return null
    }

    private fun top(values: List<Pair<String, Float>>?): Pair<String, Float>? {
        if (values.isNullOrEmpty() || values.any { !it.second.isFinite() || it.second !in 0f..1f } ||
            values.map { it.first }.distinct().size != values.size) return null
        // IPC输出已按概率排序；这里仍按最大值取，不能因输入顺序制造确认。
        return values.maxBy { it.second }
    }
}
