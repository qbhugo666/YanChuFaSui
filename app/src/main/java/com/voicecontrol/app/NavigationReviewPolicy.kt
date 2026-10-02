package com.voicecontrol.app

/** 2026-10-01：导航候选实验策略，尚未接入手机。完整旧链静默后才有采纳资格。 */
internal object NavigationReviewPolicy {
    val ACTIONS = setOf("swipe_up", "swipe_down", "swipe_left", "swipe_right", "go_back", "go_home")
    const val MIN_CONFIDENCE = .97f
    private val nonImperative = Regex("不|没|别|勿|还是|怎么|哪里|哪儿|是否|能否|请问|好像|可能|应该|已经|正在|按钮|吗|呢|听写|输入")

    fun candidate(text: String, general: List<Pair<String, Float>>?, navigation: List<Pair<String, Float>>?): String? {
        if (text.length !in 1..30 || nonImperative.containsMatchIn(text)) return null
        // 2026-10-01：初版在开发应力中零采纳；短残句不能强求ASR还保留动词。
        // 改为两个头同方向且全类>=.99，专用>=.97。共享encoder，不称独立证据。
        // 此线只用开发回归选择，之后必须新冻结验收；不能直接接线手机。
        val g = general?.firstOrNull() ?: return null
        if (g.first !in ACTIONS || !g.second.isFinite() || g.second !in .99f..1f) return null
        val scores = navigation ?: return null
        if (scores.size != 7 || scores.map { it.first }.toSet() != ACTIONS + "other" ||
            scores.any { !it.second.isFinite() || it.second !in 0f..1f } ||
            scores.sumOf { it.second.toDouble() } !in .99..1.01) return null
        val sorted = scores.sortedByDescending { it.second }
        val top = sorted[0]
        return top.first.takeIf { it == g.first && it in ACTIONS && top.second >= MIN_CONFIDENCE && top.second - sorted[1].second >= .2f }
    }
}
