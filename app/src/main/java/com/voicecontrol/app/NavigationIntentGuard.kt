package com.voicecontrol.app

/** 2026-10-01：缓存实锤“不能返回/桌面在哪里”已听对，却被contains派发成导航。
 * 在标准导航真正派发前保护显式否定/询问；不请求模型，不改用户绑定与OFF流程。
 * 仅此六个动作，其他命令、听写/录入/待命及退出安全红线不在本规则范围。
 */
internal object NavigationIntentGuard {
    val ACTIONS = setOf("swipe_up", "swipe_down", "swipe_left", "swipe_right", "go_back", "go_home")

    enum class Rejection(val description: String) {
        NEGATION("否定说法"), QUESTION("询问说法")
    }

    data class Outcome(val dispatched: Boolean, val rejection: Rejection? = null)

    // 不使用单字“不/没/别”的全句搜索，避免把“别的页面，请返回”等正当命令挡住。
    private val negation = Regex("^(?:(?:请|我|现在|先|暂时|这次)\\s*){0,2}(?:不要(?!紧)|不用|不能|不可|不可以|别(?=再?(?:向|往|上|下|左|右|返|回|退|后退|前往|桌面|滑|滚))|勿)")
    private val question = Regex("^(?:请问|能否|是否|能不能|可不可以)|(?:怎么|如何|哪里|哪儿|在哪|是不是|是什么)|(?:吗|么|呢|什么|为什么|[?？])\\s*$")

    /** 生产与测试共用派发口：拒绝时不调用执行器，失败也不自动重试。 */
    fun dispatch(text: String, matched: CommandMatcher.Match, enhancedEnabled: Boolean,
                 normalMode: Boolean, isCustom: Boolean, execute: () -> Boolean): Outcome {
        if (enhancedEnabled && normalMode && !isCustom && matched.action in ACTIONS) {
            val t = text.trim()
            // 与现有短命令上限一致；长描述不在本轮可验证的语法范围内。
            if (t.length in 1..30) {
                val rejection = when {
                    negation.containsMatchIn(t) -> Rejection.NEGATION
                    question.containsMatchIn(t) -> Rejection.QUESTION
                    else -> null
                }
                if (rejection != null) return Outcome(false, rejection)
            }
        }
        return Outcome(execute())
    }
}
