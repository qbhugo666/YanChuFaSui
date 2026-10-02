package com.voicecontrol.app

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 2026-09-29 用户截图及手机 usage_log 的参数指令回归；只做计划，不操作手机。 */
class ParameterRecoveryTest {
    private val matcher by lazy {
        val file = listOf(File("src/main/assets/commands.json"), File("app/src/main/assets/commands.json"))
            .first { it.isFile }
        CommandMatcher.fromJson(file.readText(Charsets.UTF_8))
    }

    @Test fun repeatFragmentsWorkWithLabelsAndGrid() {
        for ((labels, grid) in listOf(false to false, true to false, false to true)) {
            for (text in listOf("丰富五次", "富五次", "复五次", "夫五次", "负三次", "风副两遍")) {
                val expected = if (text == "负三次") 3 else if (text == "风副两遍") 2 else 5
                assertEquals("$text labels=$labels grid=$grid", expected,
                    CommandRouting.extractLooseRepeat(text, labels, grid, true))
            }
        }
    }

    @Test fun doubledRepeatFragmentSchedulesOneBatch() {
        assertEquals(5, CommandRouting.extractLooseRepeat("丰富五次丰富五次", true, false, true))
        assertEquals(10, CommandRouting.extractLooseRepeat("复十次", true, false, true))
    }

    @Test fun recoveryDoesNotExpandToArbitraryPhrasesOrNoPreviousAction() {
        for (text in listOf("支付五次", "回复五次", "答复五次", "我只是", "又不是", "五", "第五格", "点击五", "复五四次", "丰富", "复五次以后")) {
            assertNull(text, CommandRouting.extractLooseRepeat(text, true, false, true))
            assertNull(text, CommandRouting.extractLooseRepeat(text, false, true, true))
        }
        assertNull(CommandRouting.extractLooseRepeat("富五次", true, false, false))
        assertEquals(54, CommandRouting.extractRepeatCount("重复五四")) // 不擅自猜成5；现有10次上限拒绝
        assertEquals(11, CommandRouting.extractRepeatCount("重复十一次"))
        assertEquals(111, CommandRouting.extractLooseRepeat("复111次", true, false, true))
        assertEquals(999, CommandRouting.extractLooseRepeat("复999次", false, true, true))
        assertEquals(11, CommandRouting.extractLooseRepeat("复十一次", true, false, true))
        assertEquals(10, CommandRouting.extractLooseRepeat("复一十次", true, false, true))
    }

    // ===== 2026-09-29 NEXT_SPEECH_STAGE 第二阶段：重复次数专项审计 =====

    /**
     * 修复A（显式入口）：阿拉伯次数直取，不做前缀重复折叠。
     * 原因：parseChineseNumber 的 collapseLeadingRepeat 为「二十二十六」类汉字起音重复设计，
     * 实测把「111」折成「1」——「重复111次」执行成 1 次（次数被吞）。
     * 规则：捕获组先 toIntOrNull；汉字形态保持历史折叠行为。超 10 次由执行层 MAX_REPEAT
     * 拒绝（横条「重复最多 10 次」），不在解析层截断。
     */
    @Test fun explicitArabicRepeatCountIsNotFolded() {
        assertEquals(111, CommandRouting.extractRepeatCount("重复111次"))
        assertEquals(999, CommandRouting.extractRepeatCount("重复999次"))
        assertEquals(100, CommandRouting.extractRepeatCount("重复100次"))
        // 计划层同样不折叠（生产与离线共用同一解析器）
        assertEquals(CommandRouting.Decision.Repeat(111), CommandRouting.planCommand("重复111次",
            CommandRouting.UtteranceContext(false, false, true), matcher))
        // 汉字形态历史行为不变（折叠只对汉字有意义）
        assertEquals(11, CommandRouting.extractRepeatCount("重复十一次"))
        assertEquals(26, CommandRouting.extractTapNumber("点击二十二十六")) // 汉字折叠在编号路径保留
    }

    /**
     * 修复B（无浮层宽松重复负例）：旧实现「句中滤数字、无数字默认1」把闲话触发成重复——
     * 本组全是任务书点名或同族的**不应连续操作**负例（无编号/网格、有上一动作的最坏上下文）。
     */
    @Test fun chatPhrasesDoNotTriggerLooseRepeatWithoutOverlays() {
        for (text in listOf("我只是", "又不是", "支付五次", "这次", "每次", "几次", "第一次",
                            "有一次", "又一次", "去一次", "不再是", "不是", "过次", "不是次")) {
            assertNull(text, CommandRouting.extractLooseRepeat(text, false, false, true))
        }
        // 计划层同样不进重复通道（生产路由与测试共用 extractLooseRepeat）
        val decision = CommandRouting.planCommand("我只是",
            CommandRouting.UtteranceContext(false, false, true), matcher)
        assertFalse(decision is CommandRouting.Decision.Repeat)
        // 「第一次」防自伤回归：量词听岔只映射量词位，绝不整句 normalize——
        // 否则 是→十 会把「第一次」变成「第十次」凭空造出 10 次
        assertNull(CommandRouting.boundedLooseRepeatCount("第一次"))
    }

    /** 修复B 正例：v0.57.8~23 历史救回形态在无浮层下全部保持（收益清单，一条不丢） */
    @Test fun historicLooseRepeatRescuesSurviveTightening() {
        val cases = mapOf(
            "过一次" to 1, "不一次" to 1, "试一次" to 1, "再一次" to 1,   // 听岔前缀+数字
            "负三次" to 3, "不两次" to 2,                                  // fu/bu+数字
            "两次" to 2, "三次" to 3, "十次" to 10,                        // 纯数字+量词
            "夫尔茨" to 1, "夫尔词" to 1, "夫尔此" to 1,                   // ≥2字听岔前缀+量词听岔
            "过一是" to 1,                                                 // 是 作量词别名
        )
        for ((text, expected) in cases) {
            assertEquals(text, expected, CommandRouting.extractLooseRepeat(text, false, false, true))
        }
    }

    /** 模式语义：结构化恢复（前缀音集）在编号/网格下可用；旧宽松形态仍不进浮层（上轮拍板保持） */
    @Test fun overlayModeGatingKeepsStructuredOnly() {
        // 结构化前缀音（fu 家族）→ 浮层下也可恢复
        assertEquals(5, CommandRouting.extractLooseRepeat("丰富五次", true, false, true))
        assertEquals(5, CommandRouting.extractLooseRepeat("复五次", false, true, true))
        // 旧宽松前缀音（guo/bu/shi/zai）→ 仅无浮层；浮层下短句是数字意图
        assertEquals(1, CommandRouting.extractLooseRepeat("过一次", false, false, true))
        assertNull(CommandRouting.extractLooseRepeat("过一次", true, false, true))
        assertNull(CommandRouting.extractLooseRepeat("过一次", false, true, true))
        // 无可重复动作 → 一律不触发（既有防线）
        assertNull(CommandRouting.extractLooseRepeat("过一次", false, false, false))
        assertNull(CommandRouting.extractLooseRepeat("丰富五次", false, false, false))
    }

    /** 已知取舍（记录在案）：单字 fu 听岔保留（历史实锤「负三次」），代价是「付N次」类闲话仍会触发 */
    @Test fun documentedTradeOffSingleFuPrefix() {
        assertEquals(5, CommandRouting.extractLooseRepeat("付五次", false, false, true)) // 与「负五次」同音，无法文字层区分
        assertNull(CommandRouting.extractLooseRepeat("支付五次", false, false, true))   // 双字前缀不在集内，已拒
    }

    /** 重复异步结果文案（2026-09-30 执行反馈分层轮）：完成/停止/中止/替换都带真实计数，不虚报 */
    @Test fun repeatOutcomeTextCarriesHonestCounts() {
        val t = CommandRouting.RepeatOutcomeText
        assertEquals("→ 重复 3 次 · 已开始", t.started(3))
        // 2026-09-30 收尾轮口径：无手势完成回调——「已全部派发」而非「已完成」；
        // 750ms 间隔只降低连续长按互相打断的风险，不是完成证明
        assertEquals("→ 重复 3 次 · 已全部派发", t.completed(3))
        assertEquals("→ 重复已停止（第 2 次动作失败，已派发 1/3）", t.stoppedAt(1, 3))
        assertEquals("→ 重复中止（会话结束，已派发 2/5）", t.cancelled(2, 5, "会话结束"))
        assertEquals("→ 重复被新请求替换（已派发 4/10）", t.replaced(4, 10))
        assertEquals("→ 重复被新命令中止（已派发 2/5）", t.supersededByCommand(2, 5))
    }

    @Test fun recoveredRepeatUsesProductionPlanAndDoesNotStealDictation() {
        assertEquals(CommandRouting.Decision.Repeat(5, recovered = true), CommandRouting.planCommand("丰富五次",
            CommandRouting.UtteranceContext(true, false, true), matcher))
        assertEquals(CommandRouting.DictationContentDecision.InsertText("丰富五次"),
            CommandRouting.planDictationContent("丰富五次"))
    }

    private fun numberedPlan(text: String, count: Int?, grid: Boolean = false) =
        CommandRouting.planCommand(text,
            CommandRouting.UtteranceContext(grid, !grid, false, visibleLabelCount = count), matcher)

    @Test fun clickVerbTailRestoresOnlyExistingAlternative() {
        // 原始参数仍是76；恢复发生在看到真实编号范围后，不能全局把76换成6。
        assertEquals(76, CommandRouting.extractTapNumber("点七六"))
        for (text in listOf("点七六", "点期六", "点其六", "点七六个")) {
            assertEquals(text, CommandRouting.Decision.TapNumber(6, restored = true), numberedPlan(text, 20))
        }
        for ((digit, number) in listOf("一" to 1, "二" to 2, "三" to 3, "四" to 4,
            "五" to 5, "六" to 6, "七" to 7, "八" to 8, "九" to 9)) {
            assertEquals(CommandRouting.Decision.TapNumber(number, restored = true), numberedPlan("点七$digit", 20))
        }
    }

    @Test fun twoExistingCandidatesStopWithoutTextOrFuzzyFallback() {
        val decision = numberedPlan("点七六", 80)
        assertTrue(decision is CommandRouting.Decision.Ambiguous)
        assertEquals(setOf("点击76", "点击6"), (decision as CommandRouting.Decision.Ambiguous).tiedWords.toSet())
    }

    @Test fun absentSnapshotOrAlternativeDoesNotInventTarget() {
        for (count in listOf(null, 0, 5)) {
            assertEquals(CommandRouting.Decision.TapNumber(76), numberedPlan("点七六", count))
        }
        assertEquals(CommandRouting.Decision.TapNumber(76), CommandRouting.planCommand("点七六",
            CommandRouting.UtteranceContext(false, false, false, visibleLabelCount = 20), matcher))
    }

    @Test fun explicitNumbersAndHistoricDigitFormsKeepTheirMeaning() {
        for ((text, number) in listOf("点七十六" to 76, "点击七十六" to 76, "点击76" to 76,
            "点76" to 76, "点击七六" to 76, "点击十八" to 18, "点击十四" to 14,
            "一四" to 14, "一六" to 16, "点击二十二十六" to 26, "点击二十五点击十八" to 18)) {
            assertEquals(text, CommandRouting.Decision.TapNumber(number), numberedPlan(text, 20))
        }
    }

    @Test fun gridUsesGridRangeAndKeepsExplicitTens() {
        assertEquals(CommandRouting.Decision.GridTapCell(6, restored = true), numberedPlan("点七六", null, grid = true))
        assertEquals(CommandRouting.Decision.GridTapCell(7, restored = true), numberedPlan("点七七格", null, grid = true))
        assertEquals(CommandRouting.Decision.GridTapCell(71), numberedPlan("点七十一", null, grid = true))
        assertEquals(CommandRouting.Decision.GridTapCell(76), numberedPlan("点七十六", null, grid = true))
        assertEquals(CommandRouting.Decision.GridZoom(6), numberedPlan("六", null, grid = true))
    }

    @Test fun clickRecoveryCannotStripArbitraryLeadingDigitsOrWords() {
        for ((text, number) in listOf("点八六" to 86, "第七六个" to 76, "点七六下" to 76,
            "点七六你好" to 76, "点七十八" to 78)) {
            val resolution = CommandRouting.resolveTapNumber(text, number, 20)
            assertFalse(text, resolution.restored)
            assertFalse(text, resolution.ambiguous)
        }
        assertNull(CommandRouting.extractTapNumber("点一下"))
        assertNull(CommandRouting.extractTapNumber("点两下"))
    }
}
