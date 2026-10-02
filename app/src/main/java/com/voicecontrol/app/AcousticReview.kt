package com.voicecontrol.app

/**
 * 声音复核模块的能力注册表（2026-09-28，任务书 M6「类别接口」+ M5「详情列出实际支持范围」）。
 *
 * 设计约束（来自任务书，勿越）：
 * - 模块能力由**注册信息描述**，不由开关名称暗示——设置页「当前支持」文案直接读注册表，
 *   新模块注册后文案自动跟随，不会再出现文档与实际能力脱节。
 * - 音量二分类改判与静默声音救回分开注册；均在独立 :audio_decision 进程推理。
 *   扩展新类别（隐藏/播放、上下左右…）需要：训练数据（用户授权）→ 独立拒识/其他类负样本
 *   检验 → 冻结模型回放评测 → 注册进本表。规格见 internal-docs/M6_ACOUSTIC_EXTENSION_SPEC.md。
 * - 「统一规则覆盖全部指令」≠「全指令声学模型」：数字/文字目标等开放参数类别永远走
 *   专门解析路径；编号另用专用数字头，不把参数塞进固定动作头。
 */
object AcousticReviewRegistry {

    /** 一个已注册的声音复核模块对外声明的能力（纯数据，不含模型引用） */
    data class ModuleCapability(
        /** 模块标识（日志/设置页用） */
        val id: String,
        /** 本模块可参与复核的候选动作（超出范围的动作永远不被改写） */
        val actions: Set<String>,
        /** 用户可读的能力范围描述（设置页详情行） */
        val userScopeText: String,
        /** 模型资产指纹（冻结凭证；评测回放必须用同一哈希） */
        val modelFingerprint: String,
    )

    /** 当前注册的全部模块。顺序仅影响展示，不影响路由（路由按 action 匹配，见 AudioDecisionRouting） */
    val modules: List<ModuleCapability> = listOf(
        ModuleCapability(
            id = "volume_binary",
            actions = setOf("volume_up", "volume_down"),
            userScopeText = "音量增大 / 音量减小（区分相反方向）",
            modelFingerprint = "sensevoice_enc.onnx + audio_decision_head.onnx（哈希清单见 assets/audio_decision_assets.sha256）",
        ),
    )

    /** 当前开发构建已接通的新增采纳；与旧音量二分类器的 coversAction 范围分开。 */
    val m6Recovery = ModuleCapability(
        id = "m6_recents_recovery",
        actions = JointDecisionPolicy.AdoptionRules.FIRST_BATCH_NEW_ACTIONS,
        userScopeText = "打开最近任务（原识别未命中时声音复核救回）",
        modelFingerprint = "m6_head_packed.onnx + m6_head_meta.json（构建指纹校验）",
    )

    /** 专用左右/other 头，不能扩大旧音量头或全类头的改判白名单。 */
    val cursorRecovery = ModuleCapability(
        id = "cursor_focused_recovery",
        actions = CursorReviewPolicy.ACTIONS,
        userScopeText = "光标左移 / 右移（输入框已聚焦、原识别未命中时救回）",
        modelFingerprint = "cursor_review_head.onnx + cursor_review_meta.json（构建指纹校验）",
    )

    val numberCorrection = ModuleCapability(
        id = "number_before_tap",
        actions = setOf("tap_number"),
        userScopeText = "编号点击1～30（点击前复核，重点区分4/10；原识别未命中时救回）",
        modelFingerprint = "nn_number_head.onnx + nn_number_segments.onnx + nn_number_pair4_10.onnx及元数据（构建指纹校验）",
    )

    /** 某动作是否被任一已注册模块覆盖——**运行路由真实调用**（2026-09-29 收尾项4）：
     *  AudioDecisionRouting.correctedAction 以此为改写范围守卫（不被覆盖的动作永不改写），
     *  shouldRequestReview 以 modules.isEmpty() 为请求前置。设置页能力文案与可调用模块
     *  同源（本注册表），不会再出现文案宣称但路由不可达的能力。 */
    fun coversAction(action: String): Boolean = modules.any { action in it.actions }

    /** 设置页详情文案：各模块能力范围逐行列出 */
    fun userScopeSummary(includeM6: Boolean = false): String =
        if (modules.isEmpty()) "当前没有已启用的声音复核模块"
        else (modules + if (includeM6) listOf(m6Recovery, numberCorrection) +
            (if (CursorReviewPolicy.ENABLED) listOf(cursorRecovery) else emptyList()) else emptyList())
            .joinToString("；") { it.userScopeText }
}
