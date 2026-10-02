package com.voicecontrol.app

import org.junit.Assert.*
import org.junit.Test

class CursorExecutionGuardTest {
    private val valid = CursorExecutionGuard.Facts(true,true,true,true,5,2,2)

    @Test fun `只读资格拒绝隐藏未聚焦禁用空文本及未知选区`() {
        assertTrue(CursorExecutionGuard.usable(valid))
        for (f in listOf(valid.copy(editable=false),valid.copy(visible=false),valid.copy(focused=false),
                valid.copy(enabled=false),valid.copy(length=0),valid.copy(start=-1),valid.copy(start=6),
                valid.copy(end=3))) assertFalse(CursorExecutionGuard.usable(f))
    }

    @Test fun `只允许一个字符方向且边界不伪报派发`() {
        assertEquals(1,CursorExecutionGuard.next(valid,-1))
        assertEquals(3,CursorExecutionGuard.next(valid,1))
        assertNull(CursorExecutionGuard.next(valid,2))
        assertNull(CursorExecutionGuard.next(valid.copy(start=0,end=0),-1))
        assertNull(CursorExecutionGuard.next(valid.copy(start=5,end=5),1))
        assertNull(CursorExecutionGuard.next(valid.copy(focused=false),1))
    }

    @Test fun `次数位置参数不能凭小头补出一个字符`() {
        for (text in listOf("光标挪三格","移动光标5次","光标到第二个字","移光标两个字符")) {
            assertTrue(CursorExecutionGuard.parameterized(text))
        }
        assertFalse(CursorExecutionGuard.parameterized("把光标往左移一下"))
        val c = SilentCommandRecovery.Context(true,true,true,"g1-u1","g1-u1",true,true)
        val general = listOf("text_cursor_left" to 1f)
        val cursor = listOf("text_cursor_left" to 1f,"text_cursor_right" to 0f,"other" to 0f)
        assertNull(SilentCommandRecovery().attempt(CommandRouting.Decision.NoMatch("no_candidate"),
            "光标挪三格",SilentCommandRecovery.TextTapStatus.NOT_ATTEMPTED,
            CommandMatcher.StrictOutcome(null,false),c,general,cursor) { fail(); true })
    }
}
