package com.voicecontrol.app

/**
 * 2026-09-30：光标专用三分类复核。全类头先证明属于光标组，专用头判断左右并可拒识。
 * 旧应力出现“降低音量→右移”，因此不能只将全类头的光标分数提高就放行。
 * 本策略只供完整旧链静默出口使用；输入框焦点、模式、句归属由 SilentCommandRecovery 守卫。
 */
internal object CursorReviewPolicy {
    val ACTIONS: Set<String> = setOf("text_cursor_left", "text_cursor_right")
    // 开发集选定模型/阈值后冻结；独立最终集只验收、不用于重新挑阈值。
    const val MIN_CONFIDENCE = 0.995f
    const val MIN_MARGIN = 0.20f
    const val ENABLED = true // final3 冻结验收通过；运行仍受 Debug、增强开关和焦点守卫约束。

    private val nonImperative = Regex("不|没|别|勿|还是|怎么|哪里|哪儿|是否|能否|请问|好像|可能|应该|已经|正在|按钮|吗|呢")
    private val movementSyllables = setOf("yi","nuo","xiang") // 移 / 挪 / 向；不是个人错字清单。
    // 首次独立验收失败：起音缺失的“往左边→讲左边”被两头高分误当光标。
    // 只有方位、没有动作对象的句子不能凭焦点补出“移光标”；不是新增个人错字绑定。
    private val locationEnding = Regex(".*[左右](?:边|侧|面)?$")

    fun candidate(text: String, general: List<Pair<String, Float>>?,
                  cursor: List<Pair<String, Float>>?): String? {
        // 完全丢失文本意图时，两头共用 encoder 可能一起自信地出错，不能当独立证据。
        // 第二轮验收的否定句只剩“友”、描述句“照不到光标了”因此不允许声音补动作。
        if (text.length !in 2..40) return null
        if (nonImperative.containsMatchIn(text)) return null
        if (locationEnding.matches(text) && !text.contains("光标")) return null
        if (pinyinOf(text).split(' ').none { it in movementSyllables }) return null
        // 全类头只负责组资格，方向以专用头为准。方向冲突不能被词表顺序决定。
        val group = general?.firstOrNull() ?: return null
        if (group.first !in ACTIONS || !group.second.isFinite() || group.second !in 0.50f..1f) return null
        val scores = cursor ?: return null
        if (scores.size != 3 || scores.map { it.first }.toSet() != ACTIONS + "other") return null
        if (scores.any { !it.second.isFinite() || it.second !in 0f..1f } ||
            scores.sumOf { it.second.toDouble() } !in 0.99..1.01) return null
        val ordered = scores.sortedByDescending { it.second }
        val best = ordered[0]
        if (best.first !in ACTIONS || best.second < MIN_CONFIDENCE ||
            best.second - ordered[1].second < MIN_MARGIN) return null
        return best.first
    }
}
