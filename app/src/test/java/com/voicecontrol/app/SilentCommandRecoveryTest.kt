package com.voicecontrol.app

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SilentCommandRecoveryTest {
    private val ctx = SilentCommandRecovery.Context(true, true, true, "g1-a-u1", "g1-a-u1")
    private val noFuzzy = CommandMatcher.StrictOutcome(null, false)
    private val audio = listOf("open_recents" to 0.95f)
    private val miss = CommandRouting.Decision.NoMatch("no_candidate")
    private val noTap = SilentCommandRecovery.TextTapStatus.NOT_ATTEMPTED
    private val missingTap = SilentCommandRecovery.TextTapStatus.NOT_FOUND
    private val matcher = CommandMatcher.fromJson(File("src/main/assets/commands.json").readText())
    private val routingCtx = CommandRouting.UtteranceContext(false, false, true)

    @Test fun `生产路由隐式文字兜底失败才救回真实错句`() {
        val text = "开F接款器"
        val plan = CommandRouting.planCommand(text, routingCtx, matcher)
        assertTrue(plan is CommandRouting.Decision.TapText)
        val fuzzy = matcher.matchFuzzyDetailed(text)
        var count = 0
        val recovery = SilentCommandRecovery()
        assertNull(recovery.attempt(plan, text, SilentCommandRecovery.TextTapStatus.DISPATCHED,
            fuzzy, ctx, audio) { count++; true })
        val result = recovery.attempt(plan, text, missingTap, fuzzy, ctx, audio) {
            assertEquals("open_recents", it); count++; true
        }
        assertEquals(true, result?.dispatched)
        assertEquals(1, count)
    }

    @Test fun `NoMatch救回只派发一次失败不补发`() {
        val recovery = SilentCommandRecovery()
        var count = 0
        val first = recovery.attempt(miss, "无法识别的命令", noTap, noFuzzy, ctx, audio) { count++; false }
        assertEquals(false, first?.dispatched)
        assertNull(recovery.attempt(miss, "无法识别的命令", noTap, noFuzzy, ctx, audio) { count++; true })
        assertEquals(1, count)
    }

    @Test fun `显式文字目标以及失败或未知页面都不能变成最近任务`() {
        val plan = CommandRouting.Decision.TapText("返回")
        val recovery = SilentCommandRecovery()
        for (text in listOf("点击返回", "点返回", "按返回")) {
            assertNull(recovery.attempt(plan, text, missingTap, noFuzzy, ctx, audio) { fail(); true })
        }
        for (status in listOf(SilentCommandRecovery.TextTapStatus.FAILED,
                SilentCommandRecovery.TextTapStatus.UNAVAILABLE, noTap)) {
            assertNull(recovery.attempt(plan, "开F接款器", status, noFuzzy, ctx, audio) { fail(); true })
        }
    }

    @Test fun `原命令参数和两层歧义都不可抢`() {
        val recovery = SilentCommandRecovery()
        for (plan in listOf(CommandRouting.Decision.Repeat(5), CommandRouting.Decision.TapNumber(18),
                CommandRouting.Decision.GridZoom(5), CommandRouting.Decision.LongPressNumber(2),
                CommandRouting.Decision.Ambiguous(listOf("a", "b")))) {
            assertNull(recovery.attempt(plan, "命令", noTap, noFuzzy, ctx, audio) { fail(); true })
        }
        assertNull(recovery.attempt(miss, "经变", noTap,
            CommandMatcher.StrictOutcome(null, true, listOf("轻点", "静音")), ctx, audio) { fail(); true })
        assertNull(recovery.attempt(miss, "返回", noTap,
            CommandMatcher.StrictOutcome(matcher.matchFuzzyDetailed("返回").match, false),
            ctx, audio) { fail(); true })
    }

    @Test fun `关闭增强保护模式迟到跨会话空UID均不执行`() {
        val recovery = SilentCommandRecovery()
        for (state in listOf(ctx.copy(enabled=false), ctx.copy(active=false), ctx.copy(normalMode=false),
                ctx.copy(latestUid="g1-a-u2"), ctx.copy(latestUid="g2-b-u1"), ctx.copy(uid=""))) {
            assertNull(recovery.attempt(miss, "命令", noTap, noFuzzy, state, audio) { fail(); true })
        }
    }

    @Test fun `模型缺失低分非有限越界非白名单都不执行`() {
        val recovery = SilentCommandRecovery()
        for (value in listOf<Float>(0.79f, Float.NaN, Float.POSITIVE_INFINITY, 1.1f, -1f)) {
            assertNull(recovery.attempt(miss, "命令", noTap, noFuzzy, ctx,
                listOf("open_recents" to value)) { fail(); true })
        }
        for (label in listOf("text_cursor_right", "volume_up", "exit_session", "bogus", "other")) {
            assertNull(recovery.attempt(miss, "命令", noTap, noFuzzy, ctx,
                listOf(label to 1f)) { fail(); true })
        }
        assertNull(recovery.attempt(miss, "命令", noTap, noFuzzy, ctx, null) { fail(); true })
    }

    @Test fun `生产请求入口退出与保护模式零IPC普通句会请求`() {
        var requests = 0
        fun request(): AudioDecisionOutcome { requests++; return AudioDecisionOutcome("inc", 0.9f, 1) }
        for (text in listOf("退出", "音量增大然后退出")) {
            assertNull(AudioReviewRequest.plan(text, true, true, false, false, false).execute(::request))
        }
        assertNull(AudioReviewRequest.plan("音量", false, true, false, false, false).execute(::request))
        for (mode in 0..2) {
            assertNull(AudioReviewRequest.plan("音量", true, true, mode==0, mode==1, mode==2).execute(::request))
        }
        assertEquals(0, requests)
        AudioReviewRequest.plan("打开最近任务", true, true, false, false, false).execute(::request)
        assertEquals(1, requests)
    }

    @Test fun `生产请求入口剥离无资格音量判决保留M6`() {
        val response = AudioDecisionOutcome("dec", 0.99f, 1, m6Top=audio)
        val scoped = AudioReviewRequest.plan("变大", true, true, false, false, false).execute { response }!!
        assertNull(scoped.decision)
        assertNull(scoped.confidence)
        assertEquals(audio, scoped.m6Top)
        assertEquals("volume_up", AudioDecisionRouting.correctedAction("volume_up", true, scoped.decision))
        assertEquals(response, AudioReviewRequest.plan("音量", true, true, false, false, false).execute { response })
    }
}
