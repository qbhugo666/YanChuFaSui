package com.voicecontrol.app

/** 纯路由策略层：音频二审（当前唯一注册模块=音量增减二分类头）的触发门控与判决采纳。 */
internal object AudioDecisionRouting {

    /**
     * 是否需要为本句请求声音复核（2026-09-28 M3 统一入口，替代散落的 `contains(音/声/量)`）。
     *
     * 召回保持宽口径（含「音/声/量」任一字）：ASR 可能把「增加音量」输出成「机音量」这类
     * 残缺文本，触发字本身会丢——按候选词表收窄召回会漏（11:46 真实样本「机音量」被救回）。
     *
     * 模式门控（新增，纯收益）：听写内容 / 说法录入 / 长按待命三个模式下，本句永远不会
     * 走到音量动作的采纳分支（采纳只在 dispatchMatched 的 volume_up/down 分支），这些模式
     * 下白等最多 2.5s IPC 毫无意义——跳过，行为零变化、延迟直降。
     */
    fun shouldRequestReview(
        text: String,
        enhancedEnabled: Boolean,
        dictationMode: Boolean,
        captureArmed: Boolean,
        longPressMode: Boolean,
    ): Boolean {
        if (!enhancedEnabled) return false
        // 运行路由真实消费注册表（2026-09-29 收尾项4）：没有已注册的声音复核模块就不发请求
        // ——能力存在性由 AcousticReviewRegistry 声明，不靠开关名暗示
        if (AcousticReviewRegistry.modules.isEmpty()) return false
        if (dictationMode || captureArmed || longPressMode) return false
        if (text.isEmpty()) return false
        return text.contains("音") || text.contains("声") || text.contains("量")
    }

    fun correctedAction(candidateAction: String, enabled: Boolean, decision: String?): String {
        if (!enabled) return candidateAction
        // 运行路由真实消费注册表（2026-09-29 收尾项4）：候选动作不被任何已注册模块覆盖时
        // 永不改写——inc/dec 判决语义属于 volume_binary 模块（其 actions 恰为 volume_up/down），
        // 未来新类别必须注册自己的能力+自己的判决词表，不能蹭这两个字符串
        if (!AcousticReviewRegistry.coversAction(candidateAction)) return candidateAction
        return when (decision) {
            "inc" -> if (candidateAction == "volume_up" || candidateAction == "volume_down") "volume_up" else candidateAction
            "dec" -> if (candidateAction == "volume_up" || candidateAction == "volume_down") "volume_down" else candidateAction
            else -> candidateAction
        }
    }

    data class VolumeResolution(
        val action: String,
        val label: String,
        val description: String,
        val overrideBlocked: Boolean = false,
    )

    data class VolumeDispatch(val resolution: VolumeResolution, val dispatched: Boolean)

    /**
     * 2026-10-02：最低/上限提示原来盖掉了动作方向，用户无法看懂二审后究竟执行哪个动作。
     * 胶囊与记录复用同一份最终派发结果；边界提示保留，失败不标“已执行”。
     */
    fun volumeResultText(dispatch: VolumeDispatch, actionNote: String?): String {
        val actionName = when (dispatch.resolution.action) {
            "volume_up" -> "增加音量"
            "volume_down" -> "降低音量"
            else -> error("Not a volume direction: ${dispatch.resolution.action}")
        }
        if (!dispatch.dispatched) return "→ $actionName（未执行）"
        return if (actionNote.isNullOrBlank()) "→ $actionName ✅ 已执行"
            else "→ $actionName · $actionNote"
    }

    /** 2026-10-02：沿其他命令的“⚡ 执行：”胶囊格式，方向/边界仍复用实际派发结果。 */
    fun volumeBarText(dispatch: VolumeDispatch, actionNote: String?): String {
        val text = volumeResultText(dispatch, actionNote).removePrefix("→ ")
        return if (dispatch.dispatched) "⚡ 执行：${text.removeSuffix(" ✅ 已执行")}" else "🎤 $text"
    }

    /**
     * 2026-10-02真实反馈：文字exact“增加音量”，二类头dec@0.937却派发降低音量。
     * 二分类高分不证明文字错了。完整在册文字及用户绑定保留文字方向；残缺/拼音仍可复核。
     * 完整性从生产Match及原文判断，不维护第二份音量别名表，不拿用户标注参与运行决策。
     */
    fun resolveVolume(
        text: String,
        match: CommandMatcher.Match,
        enabled: Boolean,
        outcome: AudioDecisionOutcome?,
        customBinding: Boolean,
    ): VolumeResolution {
        val proposed = correctedAction(match.action, enabled, outcome?.decision)
        val completeText = match.method == "exact" ||
            (match.method == "contains" && match.matchedWord.isNotBlank() &&
                CommandMatcher.collapseDoubled(text.trim()).contains(match.matchedWord))
        val blocked = proposed != match.action && (completeText || customBinding)
        val action = if (blocked) match.action else proposed
        if (!enabled || outcome == null) {
            return VolumeResolution(action, "未参与", "候选=${match.action}；声音判决未采纳；实际=$action")
        }
        if (blocked) {
            val reason = if (customBinding) "自定义绑定优先" else "完整文字命令优先"
            val suggestion = if (proposed == "volume_up") "增加音量" else "降低音量"
            return VolumeResolution(action, "建议$suggestion，未采用",
                "候选=${match.action}；二审建议=$proposed；未采纳（$reason）；实际=$action", true)
        }
        return VolumeResolution(action, dispositionLabel(verdictOf(outcome), match.action, action),
            when {
                action != match.action -> "候选=${match.action}；二审改为=$action"
                outcome.decision != null -> "候选=${match.action}；二审与原命令一致"
                else -> "候选=${match.action}；二审回退=${outcome.fallbackReason ?: "未判定"}"
            })
    }

    /** 生产与回归共用的派发口：先决定方向，然后仅调用一次原执行器，失败不补发。 */
    fun dispatchVolume(
        text: String,
        match: CommandMatcher.Match,
        enabled: Boolean,
        outcome: AudioDecisionOutcome?,
        customBinding: Boolean,
        execute: (String) -> Boolean,
    ): VolumeDispatch {
        val resolution = resolveVolume(text, match, enabled, outcome, customBinding)
        return VolumeDispatch(resolution, execute(resolution.action))
    }

    /**
     * 2026-10-02用户要求直说“二审已修改为降低音量”。旧记录只从实际采纳详情恢复方向，
     * 不拿二类头的建议猜执行结果，也不改写历史存储。
     */
    fun displayLabel(label: String, detail: String = ""): String = when (label) {
        "按声音改为增加音量" -> "已修改为增加音量"
        "按声音改为降低音量" -> "已修改为降低音量"
        "已纠正" -> when {
            detail.contains("二审改为=volume_up") -> "已修改为增加音量"
            detail.contains("二审改为=volume_down") -> "已修改为降低音量"
            else -> "按声音改判"
        }
        else -> label
    }

    /** 二审判决语义（M3 统一返回口径；对照任务书：一致/纠正/无把握/不适用/未就绪/超时/故障） */
    enum class Verdict { INC, DEC, NO_CONFIDENCE, NOT_APPLICABLE, NOT_READY, TIMEOUT_OR_DEAD, INVALID_INPUT, ERROR }

    fun verdictOf(outcome: AudioDecisionOutcome?): Verdict = when {
        outcome == null -> Verdict.NOT_APPLICABLE          // 未请求（门控未过/增强关闭）
        outcome.decision == "inc" -> Verdict.INC
        outcome.decision == "dec" -> Verdict.DEC
        else -> when (outcome.fallbackReason) {
            "low_confidence", "too_short_for_features", "empty_encoder_output", "non_finite_logits" -> Verdict.NO_CONFIDENCE
            "not_ready", "runtime_unavailable" -> Verdict.NOT_READY
            "ipc_timeout_or_dead", "client_unavailable" -> Verdict.TIMEOUT_OR_DEAD
            "invalid_pcm", "session_not_initialized" -> Verdict.INVALID_INPUT
            else -> Verdict.ERROR
        }
    }

    /**
     * 用户可读的处置标签（M5 使用记录）：普通列表只显示这行短文案，
     * 置信度/encoder/head 耗时等专业数据留在诊断详情（导出反馈/日志）。
     */
    fun dispositionLabel(verdict: Verdict, candidateAction: String, finalAction: String): String = when {
        verdict == Verdict.NOT_APPLICABLE -> "未参与"
        verdict == Verdict.INC || verdict == Verdict.DEC ->
            if (finalAction != candidateAction) {
                if (finalAction == "volume_up") "已修改为增加音量" else "已修改为降低音量"
            } else "已复核一致"
        verdict == Verdict.NO_CONFIDENCE -> "无把握，使用普通识别"
        verdict == Verdict.NOT_READY || verdict == Verdict.TIMEOUT_OR_DEAD ||
            verdict == Verdict.INVALID_INPUT || verdict == Verdict.ERROR -> "暂时不可用，使用普通识别"
        else -> "未参与"
    }
}
