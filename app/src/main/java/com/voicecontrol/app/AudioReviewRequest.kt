package com.voicecontrol.app

/** 生产识别线程和无副作用测试共用；退出在 IPC 之前处理，shadow 不获得旧音量采纳资格。 */
internal object AudioReviewRequest {
    // 2026-10-03 用户决定移除声音二审。旧偏好/旧备份不能重新启用运行入口。
    const val AVAILABLE = false

    fun runtimeEnabled(savedEnabled: Boolean): Boolean = AVAILABLE && savedEnabled

    data class Plan(val volume: Boolean, val m6: Boolean) {
        fun execute(request: () -> AudioDecisionOutcome?): AudioDecisionOutcome? {
            if (!volume && !m6) return null
            val result = request() ?: return null
            return if (volume) result else result.copy(
                decision = null, confidence = null,
                fallbackReason = "volume_gate_not_qualified")
        }
    }

    fun plan(text: String, enabled: Boolean, m6Enabled: Boolean,
             dictation: Boolean, capture: Boolean, standby: Boolean): Plan {
        if (!enabled || dictation || capture || standby || text.isBlank() || text.contains("退出"))
            return Plan(false, false)
        return Plan(AudioDecisionRouting.shouldRequestReview(text, enabled, dictation, capture, standby),
            m6Enabled)
    }
}
