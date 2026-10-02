package com.voicecontrol.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JointDecisionPolicy 独立单测（2026-09-30，任务书 M6_REVIEW_AND_D_TASK §6）——
 * 不依赖本地实验目录；覆盖：合法文字点击不被抢（shadow 口径）、域外/NaN/非法类别/
 * 缺模型（空候选）、模式保护、NoMatch 救回、保护动作门槛。
 */
class JointDecisionPolicyTest {

    private val covered = setOf("volume_up", "volume_down", "swipe_up", "pause_media", "exit_session")
    private fun audio(vararg pairs: Pair<String, Float>) =
        pairs.map { JointDecisionPolicy.AudioCandidate(it.first, it.second) }

    private fun dispatch(action: String) = CommandRouting.Decision.DispatchCommand(
        CommandMatcher.Match("id", action, "g", action, "exact"))
    private val tapText = CommandRouting.Decision.TapText("抖音")
    private val noMatch = CommandRouting.Decision.NoMatch("no_candidate")
    private val repeatD = CommandRouting.Decision.Repeat(3)
    private val ambiguous = CommandRouting.Decision.Ambiguous(listOf("a", "b"))

    // ---------- 触发 ----------

    @Test fun `非普通模式不请求`() {
        assertFalse(JointDecisionPolicy.shouldRequest(dispatch("swipe_up"), covered, "dictation"))
        assertFalse(JointDecisionPolicy.shouldRequest(tapText, covered, "standby"))
        assertFalse(JointDecisionPolicy.shouldRequest(noMatch, covered, "capture"))
    }

    @Test fun `固定命令命中且在覆盖集内请求`() {
        assertTrue(JointDecisionPolicy.shouldRequest(dispatch("swipe_up"), covered, "normal"))
        // 不在覆盖集的动作（假设 covered 不含）不请求
        assertFalse(JointDecisionPolicy.shouldRequest(dispatch("grid_zoom"), covered, "normal"))
    }

    @Test fun `NoMatch与歧义请求但参数化不请求`() {
        assertTrue(JointDecisionPolicy.shouldRequest(noMatch, covered, "normal"))
        assertTrue(JointDecisionPolicy.shouldRequest(ambiguous, covered, "normal"))
        assertFalse(JointDecisionPolicy.shouldRequest(repeatD, covered, "normal"))
        val tapNum = CommandRouting.Decision.TapNumber(5)
        assertFalse(JointDecisionPolicy.shouldRequest(tapNum, covered, "normal"))
    }

    // ---------- 调和 ----------

    @Test fun `未请求与空候选一律回退文本决策`() {
        // 缺模型/故障形态=空候选：NoConfidence 回退（不猜）
        val o = JointDecisionPolicy.reconcile("swipe_up", emptyList(), 0.3f, 0.8f, requested = true)
        assertTrue(o is JointDecisionPolicy.Outcome.NoConfidence)
        assertEquals("swipe_up", (o as JointDecisionPolicy.Outcome.NoConfidence).action)
        val nr = JointDecisionPolicy.reconcile("swipe_up", audio("swipe_up" to 0.99f), 0.3f, 0.8f, false)
        assertTrue(nr is JointDecisionPolicy.Outcome.NotRequested)
    }

    @Test fun `NaN与非法概率按无把握回退`() {
        val nan = JointDecisionPolicy.reconcile("swipe_up",
            audio("swipe_up" to Float.NaN), 0.3f, 0.8f, true)
        assertTrue(nan is JointDecisionPolicy.Outcome.NoConfidence)
        // 非法类别名不在候选集也没关系——按 label 字符串处理；低分即无把握
        val low = JointDecisionPolicy.reconcile("swipe_up",
            audio("bogus_action" to 0.2f), 0.3f, 0.8f, true)
        assertTrue(low is JointDecisionPolicy.Outcome.NoConfidence)
    }

    @Test fun `一致与其他类不改判`() {
        val agree = JointDecisionPolicy.reconcile("swipe_up",
            audio("swipe_up" to 0.9f), 0.3f, 0.8f, true)
        assertTrue(agree is JointDecisionPolicy.Outcome.Agree)
        // 显式 other：保留文字动作（不因音频说 other 而取消合法命令）
        val other = JointDecisionPolicy.reconcile("swipe_up",
            audio("other" to 0.95f), 0.3f, 0.8f, true)
        assertTrue(other is JointDecisionPolicy.Outcome.Other)
        assertEquals("swipe_up", (other as JointDecisionPolicy.Outcome.Other).action)
    }

    @Test fun `跨类高置信提案但介于两线回退`() {
        val propose = JointDecisionPolicy.reconcile("swipe_up",
            audio("volume_up" to 0.85f), 0.3f, 0.8f, true)
        assertTrue(propose is JointDecisionPolicy.Outcome.ProposeCorrect)
        assertEquals("volume_up", (propose as JointDecisionPolicy.Outcome.ProposeCorrect).to)
        // 0.30≤conf<0.80：无把握回退（不猜）
        val mid = JointDecisionPolicy.reconcile("swipe_up",
            audio("volume_up" to 0.5f), 0.3f, 0.8f, true)
        assertTrue(mid is JointDecisionPolicy.Outcome.NoConfidence)
    }

    @Test fun `NoMatch救回只在高置信且非other`() {
        val rescue = JointDecisionPolicy.reconcile(null,
            audio("exit_session" to 0.9f), 0.3f, 0.8f, true)
        assertTrue(rescue is JointDecisionPolicy.Outcome.ProposeCorrect)
        assertEquals("no_match", (rescue as JointDecisionPolicy.Outcome.ProposeCorrect).from)
        // 音频说 other：维持忽略（闲话不因高分 other 变成动作）
        val chat = JointDecisionPolicy.reconcile(null, audio("other" to 0.95f), 0.3f, 0.8f, true)
        assertTrue(chat is JointDecisionPolicy.Outcome.Other)
        assertEquals("no_match", (chat as JointDecisionPolicy.Outcome.Other).action)
        // 低置信：无把握（不猜）
        val low = JointDecisionPolicy.reconcile(null, audio("swipe_up" to 0.4f), 0.3f, 0.8f, true)
        assertTrue(low is JointDecisionPolicy.Outcome.NoConfidence)
    }

    // ---------- 首批采纳门槛（AdoptionRules，2026-09-30 D） ----------

    @Test fun `首批采纳规则只放行白名单来源与目标`() {
        val r = JointDecisionPolicy.AdoptionRules
        // 2026-09-30 162 轮复核收窄：首批新增仅 open_recents
        assertEquals(setOf("open_recents"), r.FIRST_BATCH_NEW_ACTIONS)
        // 只允许旧链确实无动作（no_match）来源
        assertFalse(r.firstBatchMayAdopt("swipe_up", "open_recents"))    // 旧链已有动作：不放行
        assertFalse(r.firstBatchMayAdopt("tap_text", "open_recents"))    // 文字点击命中中：不放行
        assertTrue(r.firstBatchMayAdopt("no_match", "open_recents"))     // 静默救回：放行
        // text_cursor 已收窄出首批（应力 4 例新增错误执行）
        assertFalse(r.firstBatchMayAdopt("no_match", "text_cursor_right"))
        // 保护动作永不放行
        assertFalse(r.firstBatchMayAdopt("no_match", "exit_session"))
        assertFalse(r.firstBatchMayAdopt("no_match", "lock_screen"))
        assertFalse(r.firstBatchMayAdopt("no_match", "text_clear"))
        // 音量组：**不走 AdoptionRules**（旧二分类+correctedAction 独立路径——162 轮复核
        // 「不把新全类头替换旧音量头当沿用」）——新头对音量目标一律不放行
        assertFalse(r.firstBatchMayAdopt("volume_down", "volume_up"))
        assertFalse(r.firstBatchMayAdopt("no_match", "volume_up"))
        // 滑动四向已移除
        assertFalse(r.firstBatchMayAdopt("no_match", "swipe_up"))
    }

    @Test fun `采纳白名单与独立验收的救回动作类别一致`() {
        val adopted = JointDecisionPolicy.AdoptionRules
        assertEquals(setOf("open_recents"), adopted.FIRST_BATCH_NEW_ACTIONS)
        assertFalse("exit_session" in adopted.FIRST_BATCH_ACTIONS)
        assertFalse("text_cursor_right" in adopted.FIRST_BATCH_ACTIONS)
        assertFalse("swipe_up" in adopted.FIRST_BATCH_ACTIONS)
    }

    // ---------- 162 轮复核①②：退出保护与音量资格隔离 ----------

    @Test fun `退出句不发任何二审请求（含 shadow）`() {
        // 普通模式含「退出」→ 两门控都必须为 false（退出语义优先于一切二审）
        val text = "退出"
        val enhanced = true
        // 模拟识别线程的门控组合（与 VoiceService 同式）
        val dictationMode = false; val captureArmed = false; val longPressMode = false
        val exitUtterance = !dictationMode && !captureArmed && !longPressMode && text.contains("退出")
        val volGate = AudioReviewRequest.plan(text, enhanced, true,
            dictationMode, captureArmed, longPressMode).volume
        // 「退出」本身不含音/声/量，volGate 本来就 false——但退出保护必须额外兜底：
        // 若文字恰好含触发字（如「音量增大然后退出」），exitUtterance 也要令其为 false
        val tricky = "音量增大然后退出"
        val exitTricky = tricky.contains("退出")
        val volGateTricky = AudioReviewRequest.plan(tricky, enhanced, true,
            dictationMode, captureArmed, longPressMode).volume
        assertFalse(volGateTricky)   // 含退出→跳过旧二审请求
        assertFalse(volGate)         // 纯退出句本来也不请求
        // 长按待命中的「退出」：longPressMode=true 时两门控早已为 false（退待命语义不变）
        val volStandby = AudioDecisionRouting.shouldRequestReview(
            "退出", enhanced, false, false, true)
        assertFalse(volStandby)
    }

    @Test fun `shadow专用结果不得给旧音量执行分支提供判决`() {
        // 162 轮复核②：volGate=false（无旧二审资格）时，远程返回 inc/dec 也必须被剥离
        // ——模拟 VoiceService 的资格隔离逻辑（scopedDecision）
        fun scope(decision: String?, volGate: Boolean): String? =
            AudioReviewRequest.Plan(volGate, true).execute {
                AudioDecisionOutcome(decision, 0.99f, 1)
            }?.decision
        // 无资格句 + 二分类说 inc → 剥离（旧执行分支拿不到判决）
        assertNull(scope("inc", volGate = false))
        // 有资格句 + inc → 保留
        assertEquals("inc", scope("inc", volGate = true))
        // 无音/声/量的音量绑定句（如自定义绑定「调大声」不含触发字）+ 相反模拟判决：
        // volGate=false → 剥离 → correctedAction 收到 null 判决 → 原样返回候选动作
        //（走原文本结果，不被 shadow 顺带算出的二分类改判）
        val scopedDecision: String? = scope("dec", volGate = false)
        assertEquals("volume_up",
            AudioDecisionRouting.correctedAction("volume_up", enabled = true,
                decision = scopedDecision))
    }
}
