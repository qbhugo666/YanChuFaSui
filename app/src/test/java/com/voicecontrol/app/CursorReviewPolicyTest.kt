package com.voicecontrol.app

import org.junit.Assert.*
import org.junit.Test

class CursorReviewPolicyTest {
    private val group = listOf("text_cursor_right" to .99f, "other" to .01f)
    private fun left(p: Float = .9999f) = listOf("text_cursor_left" to p,
        "text_cursor_right" to (1f-p)/2, "other" to (1f-p)/2)
    private val ctx = SilentCommandRecovery.Context(true, true, true, "g1-a-u1", "g1-a-u1",
        focusedEditable=true, cursorEnabled=true)
    private val miss = CommandRouting.Decision.NoMatch("no_candidate")
    private val none = CommandMatcher.StrictOutcome(null, false)
    private val status = SilentCommandRecovery.TextTapStatus.NOT_ATTEMPTED

    @Test fun `专用头纠正组内方向而非照抄全类头方向`() {
        assertEquals("text_cursor_left", CursorReviewPolicy.candidate("以光标", group, left()))
        assertEquals("text_cursor_left", CursorReviewPolicy.candidate("以光标", group, left().reversed()))
    }

    @Test fun `全类高分光标遇专用头other必须拒绝`() {
        val reject = listOf("other" to .999f, "text_cursor_left" to .0005f, "text_cursor_right" to .0005f)
        assertNull(CursorReviewPolicy.candidate("以光标", group, reject))
        assertNull(CursorReviewPolicy.candidate("以光标", group, null))
        assertNull(CursorReviewPolicy.candidate("以光标", null, left()))
    }

    @Test fun `非光标组不能被专用头单独抢成光标`() {
        for (a in listOf("volume_down", "open_recents", "other", "swipe_right")) {
            assertNull(CursorReviewPolicy.candidate("以光标", listOf(a to 1f), left()))
        }
        assertNull(CursorReviewPolicy.candidate("以光标", listOf("text_cursor_left" to .4f), left()))
    }

    @Test fun `否定问句与按钮描述不触发新增救回`() {
        for (text in listOf("不要光标左移", "别右移光标", "请勿左移光标", "不是光标右移",
                "光标左移还是右移", "怎么左移光标", "光标在哪里", "光标右移按钮", "移光标吗")) {
            assertNull(text, CursorReviewPolicy.candidate(text, group, left()))
        }
    }

    @Test fun `只有方位的残句不凭输入框补出光标动作`() {
        for (text in listOf("讲左边", "往右边", "在左侧", "朝右", "那边在右面")) {
            assertNull(CursorReviewPolicy.candidate(text,group,left()))
        }
        assertEquals("text_cursor_left",CursorReviewPolicy.candidate("光标向左",group,left()))
    }

    @Test fun `孤字纯描述与动作读音全丢时不凭高分补动作`() {
        for (text in listOf("友","以","照不到光标了","找不到光标","光标在右边","早晨光")) {
            assertNull(CursorReviewPolicy.candidate(text,group,left()))
        }
        for (text in listOf("以光标","衣光表","将弓标向左挪一下","光雕像")) {
            assertEquals("text_cursor_left",CursorReviewPolicy.candidate(text,group,left()))
        }
    }

    @Test fun `分数不完整重复标签非有限越界及非概率都拒绝`() {
        val invalid = listOf(left(.7f), left(Float.NaN), left(Float.POSITIVE_INFINITY), left(1.01f),
            listOf("text_cursor_left" to 1f),
            listOf("text_cursor_left" to 1f, "text_cursor_left" to 0f, "other" to 0f),
            listOf("text_cursor_left" to 1f, "text_cursor_right" to .3f, "other" to 0f))
        for (a in invalid) assertNull(CursorReviewPolicy.candidate("以光标", group, a))
    }

    @Test fun `生产静默救回必须同时有焦点与光标资格`() {
        for (c in listOf(ctx.copy(focusedEditable=false), ctx.copy(cursorEnabled=false),
                ctx.copy(enabled=false), ctx.copy(active=false), ctx.copy(normalMode=false),
                ctx.copy(latestUid="g1-a-u2"), ctx.copy(latestUid="g2-b-u1"))) {
            assertNull(SilentCommandRecovery().attempt(miss,"以光标",status,none,c,group,left()) { fail(); true })
        }
        var actions = mutableListOf<String>()
        val result = SilentCommandRecovery().attempt(miss,"以光标",status,none,ctx,group,left()) {
            actions.add(it); true
        }
        assertEquals("text_cursor_left", result?.action)
        assertEquals(listOf("text_cursor_left"), actions)
    }

    @Test fun `新增光标救回一次失败也不补发`() {
        val recovery = SilentCommandRecovery()
        var count = 0
        assertFalse(recovery.attempt(miss,"以光标",status,none,ctx,group,left()) { count++; false }!!.dispatched)
        assertNull(recovery.attempt(miss,"以光标",status,none,ctx,group,left()) { count++; true })
        assertEquals(1, count)
    }

    @Test fun `已有动作歧义和真实文字目标依然优先`() {
        val plans = listOf<CommandRouting.Decision>(CommandRouting.Decision.Repeat(5),
            CommandRouting.Decision.TapNumber(6), CommandRouting.Decision.Ambiguous(listOf("左移", "右移")))
        for (p in plans) assertNull(SilentCommandRecovery().attempt(p,"以光标",status,none,ctx,group,left()) { fail(); true })
        val tap = CommandRouting.Decision.TapText("光标")
        for (s in listOf(SilentCommandRecovery.TextTapStatus.DISPATCHED,
                SilentCommandRecovery.TextTapStatus.FAILED, SilentCommandRecovery.TextTapStatus.UNAVAILABLE)) {
            assertNull(SilentCommandRecovery().attempt(tap,"以光标",s,none,ctx,group,left()) { fail(); true })
        }
        assertNull(SilentCommandRecovery().attempt(miss,"以光标",status,
            CommandMatcher.StrictOutcome(null,true),ctx,group,left()) { fail(); true })
    }
}
