package com.voicecontrol.app

/** 2026-09-30：已启用最近任务救回；光标扩展由专用头和输入框焦点独立守卫。失败不重试。 */
internal class SilentCommandRecovery {
    enum class TextTapStatus { NOT_ATTEMPTED, NOT_FOUND, DISPATCHED, FAILED, UNAVAILABLE }
    data class Context(val enabled: Boolean, val active: Boolean, val normalMode: Boolean,
                       val uid: String, val latestUid: String,
                       val focusedEditable: Boolean = false, val cursorEnabled: Boolean = false)
    data class Result(val action: String, val dispatched: Boolean)
    private var lastAttemptUid: String? = null

    fun attempt(plan: CommandRouting.Decision, text: String, tapStatus: TextTapStatus,
                fuzzy: CommandMatcher.StrictOutcome, context: Context,
                audio: List<Pair<String, Float>>?, cursorAudio: List<Pair<String, Float>>? = null,
                dispatch: (String) -> Boolean): Result? {
        if (!context.enabled || !context.active || !context.normalMode || context.uid.isBlank() ||
            context.uid != context.latestUid || context.uid == lastAttemptUid) return null
        if (!eligibleSource(plan, text, tapStatus, fuzzy)) return null
        val top = audio?.firstOrNull()
        val recent = top?.takeIf { it.second.isFinite() && it.second in 0.80f..1.0f &&
            it.first in AcousticReviewRegistry.m6Recovery.actions &&
            JointDecisionPolicy.AdoptionRules.firstBatchMayAdopt("no_match", it.first) }?.first
        val cursor = if (context.cursorEnabled && context.focusedEditable && !CursorExecutionGuard.parameterized(text))
            CursorReviewPolicy.candidate(text, audio, cursorAudio) else null
        val action = recent ?: cursor ?: return null
        lastAttemptUid = context.uid // 派发前占用本句；失败也不允许重试。
        return Result(action, dispatch(action))
    }

    companion object {
        /** “打开”也用于固定命令（打开 App 切换器）；先尝试页面目标，找不到才允许救回。
         * 明确的点击/点/按目标不改成系统动作，即便目标在页面上不存在。 */
        private val explicitClick = Regex("^(?:点击|点|按|长按).+")
        fun eligibleSource(plan: CommandRouting.Decision, text: String, tapStatus: TextTapStatus,
                           fuzzy: CommandMatcher.StrictOutcome): Boolean {
            if (fuzzy.ambiguous || fuzzy.match != null || explicitClick.matches(text.trim())) return false
            return when (plan) {
                is CommandRouting.Decision.NoMatch -> tapStatus == TextTapStatus.NOT_ATTEMPTED
                is CommandRouting.Decision.TapText -> tapStatus == TextTapStatus.NOT_FOUND
                else -> false
            }
        }
    }
}
