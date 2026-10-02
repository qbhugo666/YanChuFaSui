package com.voicecontrol.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 重复回放任务的**生产状态机**回归（2026-09-30 执行安全收尾轮，用户点名②）：
 * 测试驱动 CommandRouting.RepeatTask——VoiceService 的 runnable 每 tick 调 step()、
 * 各取消点调 cancel()，本测试用 FakeHost 走**同一调度路径**（不再是只手工调
 * UsageLog.updateOutcome 的日志层测试）：完成/失败/迟到横条抑制/新命令取消/替换/会话结束。
 */
class RepeatTaskTest {

    private class FakeHost(
        var seq: Int = 5,
        var active: Boolean = true,
        var actionOk: Boolean = true,
    ) : CommandRouting.RepeatTask.Host {
        val records = mutableListOf<Pair<String, String>>()
        val bars = mutableListOf<String>()
        var dispatchCount = 0
        override fun dispatchRepeatAction(): Boolean {
            dispatchCount++
            return actionOk
        }
        override fun recordOutcome(uid: String, text: String) { records += uid to text }
        override fun showBar(text: String) { bars += text }
        override fun currentUtteranceSeq(): Int = seq
        override fun sessionActive(): Boolean = active
    }

    @Test fun `三次全部派发成功记已全部派发且不打扰横条`() {
        val host = FakeHost()
        val task = CommandRouting.RepeatTask("g1-u1", 3, host.seq, host)
        assertTrue(task.step())          // 第 1 次成功 → 继续调度
        assertTrue(task.step())          // 第 2 次
        assertFalse(task.step())         // 第 3 次后结束
        assertEquals(3, task.executed)
        assertEquals(listOf("g1-u1" to "→ 重复 3 次 · 已全部派发"), host.records)
        assertEquals(0, host.bars.size)  // 完成从不覆盖横条（胶囊归当前句）
    }

    @Test fun `第2次失败停止并提示已派发计数`() {
        val host = FakeHost()
        val task = CommandRouting.RepeatTask("g1-u2", 3, host.seq, host)
        assertTrue(task.step())
        host.actionOk = false            // 第 2 次派发失败
        assertFalse(task.step())
        assertEquals(1, task.executed)
        assertEquals(listOf("g1-u2" to "→ 重复已停止（第 2 次动作失败，已派发 1/3）"), host.records)
        assertEquals(listOf("⚠️ 重复已停止：上一动作未成功执行"), host.bars)  // 非迟到：横条提示
    }

    @Test fun `迟到的失败只写回记录不覆盖新句横条`() {
        // 用户在重复进行中又说了新话（seq 前进）——旧任务失败时胶囊归新句，横条必须闭嘴
        val host = FakeHost()
        val task = CommandRouting.RepeatTask("g1-u3", 3, startSeq = 5, host = host)
        assertTrue(task.step())
        host.seq = 6                     // 用户后来的句子被处理（任何新句）
        host.actionOk = false
        assertFalse(task.step())
        assertEquals(listOf("g1-u3" to "→ 重复已停止（第 2 次动作失败，已派发 1/3）"), host.records)
        assertEquals(0, host.bars.size)  // 迟到：不覆盖新命令提示
    }

    @Test fun `新命令取消按旧任务uid记被新命令中止`() {
        val host = FakeHost()
        val task = CommandRouting.RepeatTask("g1-u4", 5, host.seq, host)
        assertTrue(task.step())
        assertTrue(task.step())          // 已派发 2 次
        task.cancel(CommandRouting.RepeatTask.CancelReason.NEW_COMMAND)
        assertEquals(listOf("g1-u4" to "→ 重复被新命令中止（已派发 2/5）"), host.records)
        // 取消后不再执行（VoiceService 侧已 removeCallbacks + repeatTask=null 拒绝迟到回调）
        host.actionOk = true
        assertFalse(task.step())         // remaining 已耗尽视角：取消后 step 不再派发
        assertEquals(2, host.dispatchCount)
    }

    @Test fun `取消族四口径`() {
        val mk = { FakeHost() }
        run {
            val h = mk(); val t = CommandRouting.RepeatTask("u", 4, 0, h); t.step()
            t.cancel(CommandRouting.RepeatTask.CancelReason.REPLACED_BY_REPEAT)
            assertEquals("→ 重复被新请求替换（已派发 1/4）", h.records.single().second)
        }
        run {
            val h = mk(); val t = CommandRouting.RepeatTask("u", 4, 0, h); t.step()
            t.cancel(CommandRouting.RepeatTask.CancelReason.SESSION_END)
            assertEquals("→ 重复中止（会话结束，已派发 1/4）", h.records.single().second)
        }
        run {
            val h = mk(); val t = CommandRouting.RepeatTask("u", 4, 0, h); t.step()
            t.cancel(CommandRouting.RepeatTask.CancelReason.SERVICE_DESTROYED)
            assertEquals("→ 重复中止（服务销毁，已派发 1/4）", h.records.single().second)
        }
        run {
            val h = mk(); val t = CommandRouting.RepeatTask("u", 4, 0, h); t.step()
            t.cancel(CommandRouting.RepeatTask.CancelReason.NEW_COMMAND)
            assertEquals("→ 重复被新命令中止（已派发 1/4）", h.records.single().second)
        }
    }

    @Test fun `会话已结束时tick写回中止不再派发`() {
        val host = FakeHost(active = false)
        val task = CommandRouting.RepeatTask("g1-u5", 3, host.seq, host)
        assertFalse(task.step())
        assertEquals(0, host.dispatchCount)   // 不派发（防旧点击在新页面继续执行）
        assertEquals(listOf("g1-u5" to "→ 重复中止（会话结束，已派发 0/3）"), host.records)
    }

    // ---------- 收尾漏洞①②回归（2026-09-30 用户点名）：终态不可改写 + 发起句序号 ----------

    @Test fun `完成后新命令与退出不得改写已定结果`() {
        // 完成 → 终态：之后的新命令取消/退出取消全部静默，「已全部派发」不被覆盖
        val host = FakeHost()
        val task = CommandRouting.RepeatTask("g1-u6", 2, host.seq, host)
        assertTrue(task.step())
        assertFalse(task.step())   // 第 2 次后完成（终态）
        assertEquals(listOf("g1-u6" to "→ 重复 2 次 · 已全部派发"), host.records)
        // VoiceService 侧此时已清句柄（runnable 见 !again 即置 null）——即使句柄意外残留，
        // 状态机终态也拒绝改写：
        task.cancel(CommandRouting.RepeatTask.CancelReason.NEW_COMMAND)
        task.cancel(CommandRouting.RepeatTask.CancelReason.SESSION_END)
        task.cancel(CommandRouting.RepeatTask.CancelReason.REPLACED_BY_REPEAT)
        assertEquals(1, host.records.size)   // 仍只有完成记录
        assertEquals("→ 重复 2 次 · 已全部派发", host.records.single().second)
        assertFalse(task.step())             // 终态后 tick 也不再派发
        assertEquals(2, host.dispatchCount)
    }

    @Test fun `失败后新命令不得改写已定结果`() {
        val host = FakeHost()
        val task = CommandRouting.RepeatTask("g1-u7", 3, host.seq, host)
        assertTrue(task.step())
        host.actionOk = false
        assertFalse(task.step())   // 失败 → 终态
        val afterFail = host.records.toList()
        assertEquals(1, afterFail.size)
        task.cancel(CommandRouting.RepeatTask.CancelReason.NEW_COMMAND)
        task.cancel(CommandRouting.RepeatTask.CancelReason.SESSION_END)
        assertEquals(afterFail, host.records)   // 失败结果原样，无改写
        assertEquals("→ 重复已停止（第 2 次动作失败，已派发 1/3）", host.records.single().second)
    }

    @Test fun `下一句已识别尚未处理时迟到失败不覆盖新句胶囊`() {
        // 收尾漏洞②：startSeq 必须来自**发起句**的序号（uid "g1-u8" → 8），不能重读全局
        // utteranceSeq——下一句（u9）已被识别线程递增但尚未处理完时，旧读法会把 startSeq
        // 错取成 9，本句的迟到失败就会顶掉 u9 的胶囊
        val host = FakeHost(seq = 9)   // 全局句序已增长到 9（下一句已识别、尚未处理完）
        val startSeqFromUid = "g1-u8".substringAfterLast("-u").toIntOrNull() ?: host.seq
        val task = CommandRouting.RepeatTask("g1-u8", 3, startSeqFromUid, host)
        assertTrue(task.step())
        host.actionOk = false
        assertFalse(task.step())
        // 迟到（9 > 8）：失败只写回记录，横条闭嘴
        assertEquals(listOf("g1-u8" to "→ 重复已停止（第 2 次动作失败，已派发 1/3）"), host.records)
        assertEquals(0, host.bars.size)
        // 反例（漏洞演示）：若误用已增长的全局序号 9 作 startSeq，迟到判定失效 → 横条会被顶掉
        host.actionOk = true
        val wrongTask = CommandRouting.RepeatTask("g1-u8", 3, 9, host)   // 旧读法的错误 startSeq
        assertTrue(wrongTask.step())
        host.actionOk = false
        assertFalse(wrongTask.step())
        assertEquals(1, host.bars.size)   // 错误 startSeq 下迟到的失败发出了横条（覆盖新句）
    }
}
