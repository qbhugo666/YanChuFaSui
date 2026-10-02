package com.voicecontrol.app

/**
 * 数字复核策略（2026-09-30，FREQUENT_COMMAND_REVIEW_TASK §B 路径①）：
 * 文字链选到了**存在但错误**的编号（如「点击十八」→TapNumber(8) 且 8 在快照内）时，
 * 数字头在**首次 tapLabel 之前**纠正为正确编号。
 *
 * 守卫（任务书 §B 接线守卫）：
 * - 编号显示中（labelsVisible）且有有效快照——无快照不凭声音创造点击目标
 * - 数字头 top1 是 1~30 内的具体编号（非 other/out_of_range/non_number_click）
 * - top1 概率≥采纳线 0.80（dev 冻结，不在验收集调参）
 * - 纠正目标必须在**当前同一编号快照范围内**（不强裁、不重算 softmax）
 * - 候选编号 ≠ 文字编号（相同则无需纠正）
 * - 只在首次派发前执行；失败不补点（幂等红线）
 *
 * 静默救回（路径②：旧链无派发时救回编号）本轮**未接**——回放未模拟
 * tap_text→不存在→静默的完整链，0 救回证据不足。
 */
internal object NumberReviewPolicy {

    const val ADOPT_THRESHOLD = 0.80f        // 部分解析文字的纠正采纳线（v1）
    const val CLEAN_TEXT_ADOPT = 0.95f       // 完整解析文字的覆盖线（v2 审计§B：
                                              // 「点击八」→8 干净但可能是「十八」截断——覆盖
                                              // 需更强声学证据；「点击十误」→15@0.895 反例被拦）
    const val MAX_SNAPSHOT_RANGE = 30

    data class Correction(val fromNumber: Int, val toNumber: Int)

    /**
     * v2 双阈策略（2026-10-01 审计§B）：
     * - 文字部分解析（「领」等不在同音表）→ 声学≥0.80 即纠正（v1 行为保持）
     * - 文字完整解析（干净数字）→ 需声学≥0.95 才覆盖（治「说 18 被写成 8」的合法错号；
     *   「点击十误」→15 正确但声学 10@0.895 被拦——审计反例保护）
     */
    fun correctBeforeTap(textNumber: Int, snapshotCount: Int?,
                         labelsVisible: Boolean,
                         numTop: List<Pair<String, Float>>,
                         textCleanParse: Boolean = false): Correction? {
        if (!labelsVisible || snapshotCount == null || snapshotCount <= 0) return null
        if (numTop.isEmpty()) return null
        val top = numTop.first()
        if (!top.second.isFinite() || top.second <= 0f || top.second > 1f) return null
        // v2 双阈：完整解析需更高声学证据才覆盖
        val threshold = if (textCleanParse) CLEAN_TEXT_ADOPT else ADOPT_THRESHOLD
        if (top.second < threshold) return null
        val topN = top.first.toIntOrNull() ?: return null
        if (topN !in 1..MAX_SNAPSHOT_RANGE) return null
        if (topN !in 1..snapshotCount) return null
        if (topN == textNumber) return null
        if (textNumber !in 1..snapshotCount) return null
        if (textNumber > MAX_SNAPSHOT_RANGE) return null
        return Correction(textNumber, topN)
    }

    /** ASR 文本归一化后编号部分是否完整解析（全在数字/同音表内）——生产/回放共用 */
    fun isCleanParse(asrText: String): Boolean {
        val t = asrText.trim()
        val m = Regex("""^(?:点击|点)\s*(.+)$""").find(t) ?: return true
        // 2026-10-01 冻结验收失败：编号后缀「号/个」不是识别损坏，不能因此降到 .80。
        // 只去掉明确编号语法；「啊/领/路」等未知字仍按部分解析处理。
        val numPart = m.groupValues[1].trim().removePrefix("编号").removePrefix("第")
            .removeSuffix("号").removeSuffix("个").trim()
        val normalized = DigitParser.normalizeDigitHomophones(numPart)
        return normalized.isNotEmpty() && normalized.all { it in "0123456789零一二两三四五六七八九十百" }
    }
}
