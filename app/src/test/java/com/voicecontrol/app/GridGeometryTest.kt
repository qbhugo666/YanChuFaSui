package com.voicecontrol.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2026-09-30 已装机版本执行回归修复的离线验证：
 * ① 网格误点——gridCellCenterNormalized（生产共用几何）全格 1~12，第 4 格必须落在
 *    第二行第一列（旧实现行号误用 GRID_ROWS(4)，3 列网格按行填充进位是列数 3——
 *    「点击第 4 格」曾落到第一行第一列=第 1 格位置的真机误点）；doZoomGrid 的正确几何一并固化。
 * ② 重复间隔——长按手势 650ms 与默认 620ms 冲突的安全串行间隔。
 * ③ 「重复0次」解析现状与拒绝文案（生产 handleRepeat 当场拒绝，不启动任务）。
 * ④ 反馈串句——旧重复失败事件按发起句 uid 写回，不串到用户后来的句子、不产生孤儿条目
 *   （SessionState.lastMatch 的 append 通道只属于新句上下文）。
 * 纯 JVM；真机网格点击/长按/重复执行链不在本测试范围（见 ROADMAP_PROGRESS 真机待验）。
 */
class GridGeometryTest {

    private fun productionJson(): String {
        val candidates = listOf(
            File("src/main/assets/commands.json"),
            File("app/src/main/assets/commands.json"),
        )
        return candidates.firstOrNull { it.isFile }?.readText(Charsets.UTF_8)
            ?: error("生产词表 commands.json 未找到")
    }

    // ---------- ① 网格格心几何（生产函数，VoiceControlService.companion） ----------

    @Test fun `全格1到12的归一化格心坐标按行填充`() {
        val cols = 3
        val rows = 4
        for (n in 1..12) {
            val (nx, ny) = VoiceControlService.gridCellCenterNormalized(n, cols, rows)
            val expectedCol = (n - 1) % cols
            val expectedRow = (n - 1) / cols   // 行进位=列数（3 列按行填充）
            assertEquals("格 $n 列向中心", (expectedCol + 0.5f) / cols, nx, 1e-6f)
            assertEquals("格 $n 行向中心", (expectedRow + 0.5f) / rows, ny, 1e-6f)
        }
    }

    @Test fun `第4格必须落在第二行第一列`() {
        // 旧行号 bug：row=(4-1)/4=0 → 第一行第一列（=第 1 格位置）→ 点击第 4 格点到第 1 格
        val (nx, ny) = VoiceControlService.gridCellCenterNormalized(4)
        // 第一列：nx ∈ (0, 1/3)，中心 1/6
        assertEquals(0.5f / 3f, nx, 1e-6f)
        // 第二行：ny ∈ (1/4, 2/4)，中心 3/8——必须大于第一行中心 1/8
        assertEquals(1.5f / 4f, ny, 1e-6f)
        assertTrue("第 4 格不能落在第一行（旧 bug 回归）", ny > 0.25f)
        // 与相邻格不重合：第 1 格（第一行第一列）行向中心 1/8
        val (_, ny1) = VoiceControlService.gridCellCenterNormalized(1)
        assertTrue(ny > ny1)
    }

    @Test fun `点击格与缩放使用同一行填充几何`() {
        // doZoomGrid 的正确几何（gridStack 格区间）与格心函数一致：格 n 的区间中心=格心
        // 缩放区间=[left+col*cellW, left+(col+1)*cellW] 的中心 = left+(col+0.5)*cellW
        for (n in 1..12) {
            val col = (n - 1) % 3
            val row = (n - 1) / 3
            val spanCenterX = (col + 0.5f) / 3f   // (col*1/3 + (col+1)*1/3) / 2
            val spanCenterY = (row + 0.5f) / 4f
            val (nx, ny) = VoiceControlService.gridCellCenterNormalized(n)
            assertEquals(spanCenterX, nx, 1e-6f)
            assertEquals(spanCenterY, ny, 1e-6f)
        }
    }

    // ---------- ② 重复回放安全串行间隔 ----------

    @Test fun `长按动作的重复间隔必须大于手势时长`() {
        // 620ms 默认间隔 < 650ms 长按时长：旧逻辑下一次派发会打断未完成的长按
        val lp = CommandRouting.repeatIntervalMs(actionIsLongPress = true, 620L, 650L)
        assertTrue("长按回放间隔($lp)必须 > 长按时长 650ms", lp > 650L)
        assertEquals(750L, lp)
        // 非长按动作维持默认间隔（滑动 550ms < 620ms，历史标定不动）
        assertEquals(620L, CommandRouting.repeatIntervalMs(actionIsLongPress = false, 620L, 650L))
        // 默认间隔本身更大时取默认（不缩短）
        assertEquals(900L, CommandRouting.repeatIntervalMs(actionIsLongPress = true, 900L, 650L))
    }

    // ---------- ③ 「重复0次」边界 ----------

    @Test fun `重复0次解析为0且计划层如实传递由生产拒绝`() {
        // 解析现状固化：0 是合法捕获（不是 null——null 会掉进其他通道猜动作）
        assertEquals(0, CommandRouting.extractRepeatCount("重复0次"))
        val matcher = CommandMatcher.fromJson(productionJson())
        assertEquals(
            CommandRouting.Decision.Repeat(0),
            CommandRouting.planCommand("重复0次", CommandRouting.UtteranceContext(false, false, true), matcher),
        )
        // 生产 handleRepeat 对 times<=0 当场拒绝（横条+发起句 lastMatch），不启动任务——
        // 0 次若启动会立即耗尽 remaining 且无结果事件，记录永久停在「已开始」
        assertEquals("→ 重复次数需至少 1 次（0 次不执行）", CommandRouting.RepeatOutcomeText.rejectedZero())
    }

    // ---------- ④ 反馈串句（旧重复未结束时用户又说新命令） ----------

    @Test fun `旧重复失败按发起句写回不串新句不产生孤儿条目`() {
        val f = kotlin.io.path.createTempDirectory("grid_geo_test").toFile().resolve("usage.json")
        UsageLog.initForTest(f)
        // u1：旧重复开始
        UsageLog.appendHeard("重复五次", "g8-u1")
        UsageLog.append("→ 重复 5 次 · 已开始")
        // 用户又说新命令 u2（走 SessionState.lastMatch→append 的正常通道，关联最新空结果条）
        UsageLog.appendHeard("向上滑动", "g8-u2")
        UsageLog.append("→ 向上滑动 ✅ 已执行")
        // 旧重复失败：正确路径=按 u1 写回（修复后 VoiceService 只调 updateOutcome，不碰 lastMatch）
        UsageLog.updateOutcome("g8-u1", "→ 重复已停止（第 2 次动作失败，已完成 1/5）")
        val all = UsageLog.all()
        assertEquals(2, all.size)                                   // 无孤儿条目
        assertEquals("→ 重复已停止（第 2 次动作失败，已完成 1/5）", all[0].text)
        assertEquals("→ 向上滑动 ✅ 已执行", all[1].text)           // 新句不被旧任务覆盖
        // 反例演示（为何修复）：若旧任务误走 SessionState.lastMatch=append 通道，
        // 会 append 一条不属于任何句子的孤儿记录
        UsageLog.append("→ 重复已停止（动作执行失败）")
        assertEquals(3, UsageLog.all().size)                        // 串句实锤：多出孤儿条
    }
}
