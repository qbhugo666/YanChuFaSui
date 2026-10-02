package com.voicecontrol.app

/**
 * 声音与文字共同决策（2026-09-30）：reconcile 用于离线分析，AdoptionRules 同时限制实际救回。
 *
 * VoiceService 经 SilentCommandRecovery 接入首批最近任务救回，其余新类别继续只观察。
 *
 * 设计约束（任务书 §五）：
 * - 复用现有 planCommand/matcher，不重写路由；文字命中质量（method）与音频分数分开表达。
 * - 触发不再只靠「音/声/量」：固定命令命中（含 exact——跨类误识别可能恰好命中另一条合法
 *   命令）都在新头覆盖集内即请求；参数化决策（编号/网格/文字目标/重复次数）不请求——
 *   音频头不证明参数正确（任务书 §二）。
 * - 五态：一致 / 提案改判 / 其他类 / 无把握 / 未请求。**提案≠执行**——每条改判在回放
 *   报告中列收益与误伤，不靠增大置信度把「隐藏」强改成音量（§C4）。
 * - 回退语义：无把握→文本决策原样；故障→文本决策原样（与现有音量回退语义同构，不全局改写）。
 */
internal object JointDecisionPolicy {

    /** 新全类头经 evaluate-only 工具预计算的 top-k 候选（label 含 "other"） */
    data class AudioCandidate(val label: String, val prob: Float)

    /**
     * 新头覆盖的固定动作集合（labels 去掉 other）。由回放输入提供（与训练词表同源），
     * 未来接入时由模型注册信息描述（AcousticReviewRegistry 模式）。
     */
    fun coveredActions(labels: List<String>): Set<String> = labels.filter { it != "other" }.toSet()

    /** 文字路由结论 → 其声学可复核的动作标识（参数化决策返回 null=不请求） */
    fun actionOfDecision(d: CommandRouting.Decision?): String? = when (d) {
        is CommandRouting.Decision.DispatchCommand -> d.action
        // 编号/网格/长按文字/文字点击/替换/重复：动作头可判「点击/长按」类意图但参数不进头；
        // 这些路径的文字结构匹配是主证据，声音复核不改变参数——本轮回放不请求（列局限）
        is CommandRouting.Decision.TapNumber, is CommandRouting.Decision.GridTapCell,
        is CommandRouting.Decision.GridLongPress, is CommandRouting.Decision.LongPressNumber,
        is CommandRouting.Decision.LongPressText, is CommandRouting.Decision.TapText,
        is CommandRouting.Decision.Replace, is CommandRouting.Decision.Repeat -> null
        is CommandRouting.Decision.GridZoom, is CommandRouting.Decision.Ambiguous,
        is CommandRouting.Decision.NoMatch -> null
        null -> null
    }

    /**
     * 是否请求声音复核（新策略，选择性请求口径）。
     * 2026-09-30 回放驱动修正（两轮）：首轮 propose=0 根因=NoMatch 不请求；二轮分析 81 条
     * 文字错句中 77 条落 TapText（ASR 把命令转成 2~8 字短句→文字点击兜底）——**短句兜底
     * 通道是命令词转错后的最大汇集地**，也纳入请求：音频判固定命令=救回提案；判 other=
     * 维持文字点击（不误伤真点屏幕文字的场景）。参数化决策（编号/网格/重复次数等）仍不
     * 请求——音频头不证明参数正确（任务书 §二）。exact 命中也请求（§C2）。
     */
    fun shouldRequest(d: CommandRouting.Decision?, covered: Set<String>, mode: String): Boolean {
        if (mode != "normal") return false   // 听写/待命/录入等模式不请求（沿用现有模式门控语义）
        val action = actionOfDecision(d)
        if (action != null) return action in covered
        return d is CommandRouting.Decision.NoMatch ||
            d is CommandRouting.Decision.Ambiguous ||
            d is CommandRouting.Decision.TapText
    }

    sealed class Outcome {
        /** 音频与文字一致（或音频弃权/故障回退）——执行文字决策 */
        data class Agree(val action: String) : Outcome()
        /** 提案改判（离线统计用；未授权不执行）：音频 top 类≠文字动作且达采纳线 */
        data class ProposeCorrect(val from: String, val to: String, val conf: Float) : Outcome()
        /** 音频明确 other（非命令）——文字决策保留，回放报告统计该句疑点 */
        data class Other(val action: String, val conf: Float) : Outcome()
        /** 无把握（conf<弃权线或 top=other 但无采纳证据）→ 文字决策原样（回退语义） */
        data class NoConfidence(val action: String) : Outcome()
        /** 未请求（参数化/不在覆盖集）→ 文字决策原样 */
        data class NotRequested(val action: String) : Outcome()
    }

    /**
     * 调和文字决策与音频 top-k。
     * @param textAction 文字路由的固定动作（null=参数化/未命中，不调和）
     * @param audio 按概率降序的候选（首元素=top）
     * @param abstainTh 弃权线（max softmax，dev 冻结值）
     * @param adoptTh 改判采纳线（更高；本策略固定 0.80，回放中敏感度另测不调 test）
     */
    fun reconcile(textAction: String?, audio: List<AudioCandidate>, abstainTh: Float,
                  adoptTh: Float, requested: Boolean): Outcome {
        if (!requested) return Outcome.NotRequested(textAction ?: "none")
        if (textAction == null) {
            // 2026-09-30 回放驱动修正：文字无候选/歧义（NoMatch/Ambiguous）但已请求——
            // 音频是唯一强证据：命中固定命令且达采纳线=救回提案；明确 other=维持忽略；
            // 低置信=无把握回退（维持忽略，不猜）
            if (audio.isEmpty()) return Outcome.NoConfidence("no_match")
            val top = audio.first()
            if (top.prob < abstainTh) return Outcome.NoConfidence("no_match")
            if (top.label == "other") return Outcome.Other("no_match", top.prob)
            if (top.prob >= adoptTh) return Outcome.ProposeCorrect("no_match", top.label, top.prob)
            return Outcome.NoConfidence("no_match")
        }
        if (audio.isEmpty()) return Outcome.NoConfidence(textAction)
        val top = audio.first()
        // 非有限分数（NaN/Inf——模型异常输出形态）：按无把握回退，绝不当一致或采纳（单测固化）
        if (!top.prob.isFinite()) return Outcome.NoConfidence(textAction)
        if (top.prob < abstainTh) return Outcome.NoConfidence(textAction)
        if (top.label == "other") {
            // 显式其他类：非命令证据——文字决策保留（离线统计疑点），不改判
            return Outcome.Other(textAction, top.prob)
        }
        if (top.label == textAction) return Outcome.Agree(textAction)
        // 音频指向不同的固定命令类：达采纳线才形成提案（提案≠执行，报告逐条列收益/误伤）
        if (top.prob >= adoptTh) return Outcome.ProposeCorrect(textAction, top.label, top.prob)
        // 介于两线之间：无把握回退
        return Outcome.NoConfidence(textAction)
    }

    /**
     * 首批采纳门槛（2026-09-30 D，任务书 §下一阶段5）：提案≠执行——实际改变动作须过本门槛。
     * 依据独立验收（435 条全新合成×双态页面）：静默门槛净救回 7/误伤 0/提案错 0；
     * 完整放行则 41 救回但对已对句提案 29（不采纳无害，采纳则风险）——故首批只放行
     * 「旧链静默（no_match）」来源 + 验收救回动作白名单；保护动作（退出/锁屏/清空类
     * ——非幂等或会话终结）永不放行。Release 不加载新头；开发构建按首批规则采纳。
     */
    object AdoptionRules {
        /**
         * 首批新增采纳（2026-09-30 162 轮复核收窄）：**仅 open_recents**——本批应力数据中
         * 该动作 0 新增错误执行；text_cursor_left/right 在应力中被采纳为错误方向 4 例
         * （左移→右移×2、降音量→右移×2），继续 shadow 观察并针对混淆补强后再验收；
         * 滑动四向无净增益证据。音量组走旧二分类规则（不是「沿用」新头——两套规则独立）。
         */
        val FIRST_BATCH_ACTIONS: Set<String> = setOf("open_recents")

        val VOLUME_GROUP: Set<String> = setOf("volume_up", "volume_down")

        /** 首批新增动作（不含音量组）——报告口径的「首批 N 动作」以此为准 */
        val FIRST_BATCH_NEW_ACTIONS: Set<String> = FIRST_BATCH_ACTIONS - VOLUME_GROUP

        /** 保护动作：永不声音改判（会话终结/文字破坏类） */
        val PROTECTED_ACTIONS: Set<String> = setOf(
            "exit_session", "lock_screen", "text_clear", "text_delete",
        )

        /**
         * 首批是否允许把 from 改成 to。来源资格（162 轮复核③）：只有完整旧链**确实无动作**
         * 才能救回——no_match 表示旧链静默（TapText 目标不存在且模糊兜底也未命中=真正无动作）；
         * 显式文字目标命中（tap_text_success）、参数指令（Repeat/TapNumber/GridZoom/LongPress
         * 等类名）、歧义（ambiguous）、保护模式中的句子一律无资格。
         */
        fun firstBatchMayAdopt(from: String, to: String): Boolean {
            if (to in PROTECTED_ACTIONS) return false
            if (to !in FIRST_BATCH_ACTIONS) return false
            // 音量组内改判走旧二分类规则（与新头无关——旧音量头既有语义，独立路径）
            if (from == "volume_down" || from == "volume_up") {
                return to == "volume_up" || to == "volume_down"
            }
            // 首批新增：只允许旧链确实无动作（no_match）的救回
            return from == "no_match"
        }
    }
}
