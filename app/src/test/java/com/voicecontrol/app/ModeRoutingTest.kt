package com.voicecontrol.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 模式路由回归（2026-09-29 收尾项3）：长按待命计划 / 听写内容计划 / 听写武装门控——
 * 从 VoiceService 提取的纯分类函数，生产委托同一实现（单一事实来源），此处离线固化行为。
 * 执行器行为（真实点按/写输入框/还麦计时）不在本测试范围，属真机待验。
 */
class ModeRoutingTest {

    private fun productionJson(): String {
        val candidates = listOf(
            File("src/main/assets/commands.json"),
            File("app/src/main/assets/commands.json"),
        )
        return candidates.firstOrNull { it.isFile }?.readText(Charsets.UTF_8)
            ?: error("生产词表 commands.json 未找到")
    }

    private val matcher by lazy { CommandMatcher.fromJson(productionJson()) }

    // ---------- 长按待命（planLongPressStandby） ----------

    @Test fun `待命中退出取消只退待命不结束会话`() {
        // 任务书 §4 明确：长按待命里的「退出/取消」退出的是长按模式——与全局退出红线语义不同
        assertEquals(CommandRouting.StandbyDecision.CancelStandby, CommandRouting.planLongPressStandby("退出", gridShowing = false))
        assertEquals(CommandRouting.StandbyDecision.CancelStandby, CommandRouting.planLongPressStandby("取消", gridShowing = false))
        assertEquals(CommandRouting.StandbyDecision.CancelStandby, CommandRouting.planLongPressStandby("取消长按", gridShowing = true))
    }

    @Test fun `待命中的编辑词直通`() {
        // v0.56.28 连环坑：删除被听成长按误入待命，再说删除要能脱困
        assertEquals(
            CommandRouting.StandbyDecision.EditCommand("删除"),
            CommandRouting.planLongPressStandby("删除", gridShowing = false),
        )
        assertEquals(
            CommandRouting.StandbyDecision.EditCommand("光标右移"),
            CommandRouting.planLongPressStandby("光标右移。", gridShowing = false),   // 剥标点后命中
        )
    }

    @Test fun `待命中中间或屏幕是长按屏幕中心`() {
        assertEquals(CommandRouting.StandbyDecision.PressCenter, CommandRouting.planLongPressStandby("中间", gridShowing = false))
        assertEquals(CommandRouting.StandbyDecision.PressCenter, CommandRouting.planLongPressStandby("按屏幕中间", gridShowing = true))
    }

    @Test fun `待命中数字按网格显示状态分流格子或编号`() {
        // v0.57.20 用户实锤：网格定位说「长按」进待命、报数字应归格子而非「长按编号 N」
        assertEquals(
            CommandRouting.StandbyDecision.StandbyNumber(58, gridCell = true),
            CommandRouting.planLongPressStandby("五十八", gridShowing = true),
        )
        assertEquals(
            CommandRouting.StandbyDecision.StandbyNumber(12, gridCell = false),
            CommandRouting.planLongPressStandby("十二", gridShowing = false),
        )
        // 同音字在数字位归一（吧→八）
        assertEquals(
            CommandRouting.StandbyDecision.StandbyNumber(8, gridCell = false),
            CommandRouting.planLongPressStandby("吧", gridShowing = false),
        )
    }

    @Test fun `待命中其他话语保持待命重新计时`() {
        assertEquals(CommandRouting.StandbyDecision.KeepWaiting, CommandRouting.planLongPressStandby("嗯", gridShowing = false))
        assertEquals(CommandRouting.StandbyDecision.KeepWaiting, CommandRouting.planLongPressStandby("等一下", gridShowing = false))
    }

    // ---------- 听写内容（planDictationContent） ----------

    @Test fun `听写内容句四分类`() {
        val p = CommandRouting::planDictationContent
        assertEquals(CommandRouting.DictationContentDecision.CancelDictation, p("取消"))
        assertEquals(CommandRouting.DictationContentDecision.ContinueDictation, p("输入"))      // v0.56.30 续听
        assertEquals(CommandRouting.DictationContentDecision.EditInDictation("删除"), p("删除。"))  // v0.56.25 编辑直达
        assertEquals(CommandRouting.DictationContentDecision.InsertText("今天天气不错"), p("今天天气不错"))
    }

    @Test fun `听写内容句原文落笔不做数字或命令解析`() {
        // 内容含命令片段/数字时仍按内容处理（内容优先于命令拦截）
        val d = CommandRouting.planDictationContent("音量调大一点")
        assertTrue(d is CommandRouting.DictationContentDecision.InsertText)
        assertEquals("音量调大一点", (d as CommandRouting.DictationContentDecision.InsertText).text)
    }

    // ---------- 听写武装门控（shouldArmDictation） ----------

    @Test fun `在册命令永不进听写考场`() {
        // v0.57.12 用户拍板：删除是正式命令——exact 命中就按命令走
        assertFalse(shouldArmDictation("删除", matcher))
        assertFalse(shouldArmDictation("清空输入", matcher))
        assertFalse(shouldArmDictation("向上滑动", matcher))
    }

    @Test fun `触发词与同音容错正常武装`() {
        assertTrue(shouldArmDictation("输入", matcher))     // 词表无「输入」本体（清空输入 contains 不算 exact）
        assertTrue(shouldArmDictation("打字", matcher))
        assertTrue(shouldArmDictation("书入", matcher))     // v0.55.12 同音容错
        assertFalse(shouldArmDictation("你好", matcher))
    }

    @Test fun `自定义指令短语同样让位听写门控`() {
        // 自定义词也是 matchExact 在册——用户显式绑定永远优先
        val m = CommandMatcher.fromJson(
            """{"groups":[{"id":"g","name":"t","commands":[
              {"id":"a","command":"翻页","aliases":[],"action":"swipe_up"}]}]}""",
            customBindings = listOf("写字喽" to "swipe_up"),
        )
        assertFalse(shouldArmDictation("写字喽", m))
        assertTrue(shouldArmDictation("输入", m))
    }
}
