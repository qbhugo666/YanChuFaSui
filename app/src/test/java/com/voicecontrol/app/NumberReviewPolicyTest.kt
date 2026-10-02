package com.voicecontrol.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NumberReviewPolicy 行为测试（2026-10-01，194 审计§A1）——
 * 无文件依赖的纯策略测试；模拟候选只证明策略行为，不冒充真实声学效果。
 * 覆盖：原号正确/错误 × 声音正确/错误 四类，完整数字/同音字/未知字/语气词/NaN/Inf/超范围。
 */
class NumberReviewPolicyTest {

    @Test fun `编号语法后缀不降级为部分解析`() {
        for (text in listOf("点击一号", "点击第十二个", "点击编号十八", "点六个")) {
            assertTrue(text, NumberReviewPolicy.isCleanParse(text))
            assertNull(NumberReviewPolicy.correctBeforeTap(1, 30, true,
                listOf("5" to .94f), NumberReviewPolicy.isCleanParse(text)))
        }
        assertFalse(NumberReviewPolicy.isCleanParse("点击号"))
        assertFalse(NumberReviewPolicy.isCleanParse("点击一领"))
    }

    private fun n(vararg pairs: Pair<String, Float>) = pairs.toList()
    private val high = 0.99f    // 远超两阈
    private val mid = 0.85f     // 超部分解析阈但不超干净文字阈
    private val low = 0.60f     // 低于两阈

    // ---------- 四类核心场景（审计表格口径） ----------

    @Test fun `文字部分解析_声音正确_纠正`() {
        // 「点击一领」→文字 1（部分解析），音频 10@0.99 → 纠正为 10
        val c = NumberReviewPolicy.correctBeforeTap(1, 20, true, n("10" to high), false)
        assertNotNull(c); assertEquals(10, c!!.toNumber); assertEquals(1, c.fromNumber)
    }

    @Test fun `文字部分解析_声音错误_改错但不算原正确误伤`() {
        // 「点击一领」→文字 1，音频 8@0.99 → 改为 8（若真值不是 8 则是错误纠正——策略无法知道真值）
        val c = NumberReviewPolicy.correctBeforeTap(1, 20, true, n("8" to high), false)
        assertNotNull(c); assertEquals(8, c!!.toNumber)
    }

    @Test fun `文字完整解析_声音高置信不同意_覆盖（v2 合法错号）`() {
        // 「点击八」→文字 8（干净），音频 18@0.99 ≥0.95 → 纠正为 18（说十八被写成八）
        val c = NumberReviewPolicy.correctBeforeTap(8, 20, true, n("18" to high), true)
        assertNotNull(c); assertEquals(18, c!!.toNumber)
    }

    @Test fun `文字完整解析_声音中等置信不同意_不覆盖（审计反例保护）`() {
        // 「点击十误」→文字 15（干净，同音表），音频 10@0.895 <0.95 → 不纠正
        val c = NumberReviewPolicy.correctBeforeTap(15, 20, true, n("10" to 0.895f), true)
        assertNull(c)
    }

    @Test fun `文字完整解析_声音同意_不纠正`() {
        // 文字 8，音频 8@0.99 → 编号相同，无需纠正
        assertNull(NumberReviewPolicy.correctBeforeTap(8, 20, true, n("8" to high), true))
    }

    // ---------- 边界形态 ----------

    @Test fun `同音字完整解析视为干净`() {
        // 「点击十八」→ 18（完整）；「点击是吧」→同音归一后「四八」=完整
        assertTrue(NumberReviewPolicy.isCleanParse("点击十八"))
        assertTrue(NumberReviewPolicy.isCleanParse("点击是吧"))   // 是→十，吧→八
    }

    @Test fun `未知字为部分解析`() {
        // 「点击一领」→「领」不在同音表→部分
        assertFalse(NumberReviewPolicy.isCleanParse("点击一领"))
        assertFalse(NumberReviewPolicy.isCleanParse("点击十误啊"))  // 「啊」不在表→部分
    }

    @Test fun `语气词尾词使解析不干净`() {
        // 审计点名：「点击十五啊」→ extractTapNumber 解析 15，但「啊」使 isCleanParse=false
        assertFalse(NumberReviewPolicy.isCleanParse("点击十五啊"))
    }

    @Test fun `NaN和Inf拒绝`() {
        assertNull(NumberReviewPolicy.correctBeforeTap(1, 20, true, n("10" to Float.NaN), false))
        assertNull(NumberReviewPolicy.correctBeforeTap(1, 20, true, n("10" to Float.POSITIVE_INFINITY), false))
        assertNull(NumberReviewPolicy.correctBeforeTap(1, 20, true, n("10" to Float.NEGATIVE_INFINITY), false))
    }

    @Test fun `超范围概率拒绝`() {
        assertNull(NumberReviewPolicy.correctBeforeTap(1, 20, true, n("10" to 0f), false))
        assertNull(NumberReviewPolicy.correctBeforeTap(1, 20, true, n("10" to 1.5f), false))
        assertNull(NumberReviewPolicy.correctBeforeTap(1, 20, true, n("10" to -0.1f), false))
    }

    @Test fun `范围外号码不被强裁`() {
        // 原 76 在快照 80 内存在，声音说 6 → 不改（保护 31+/76 正规编号）
        assertNull(NumberReviewPolicy.correctBeforeTap(76, 80, true, n("6" to high), false))
        // 声音给 31+（超数字头 1..30）→ 拒绝
        assertNull(NumberReviewPolicy.correctBeforeTap(8, 20, true, n("31" to high), false))
    }

    @Test fun `目标不在快照内拒绝`() {
        // 文字 25 在快照 20 中不存在→不是本策略场景
        assertNull(NumberReviewPolicy.correctBeforeTap(25, 20, true, n("10" to high), false))
        // 声音 25 不在快照 20 内→拒绝
        assertNull(NumberReviewPolicy.correctBeforeTap(8, 20, true, n("25" to high), false))
    }

    @Test fun `编号显示关闭不纠`() {
        assertNull(NumberReviewPolicy.correctBeforeTap(1, 20, false, n("10" to high), false))
        assertNull(NumberReviewPolicy.correctBeforeTap(1, null, true, n("10" to high), false))
        assertNull(NumberReviewPolicy.correctBeforeTap(1, 0, true, n("10" to high), false))
    }

    @Test fun `拒识类标签不触发`() {
        assertNull(NumberReviewPolicy.correctBeforeTap(8, 20, true, n("other" to high), false))
        assertNull(NumberReviewPolicy.correctBeforeTap(8, 20, true, n("out_of_range" to high), false))
        assertNull(NumberReviewPolicy.correctBeforeTap(8, 20, true, n("non_number_click" to high), false))
    }

    @Test fun `部分解析低置信不纠`() {
        // 部分解析但声学只有 0.60（低于 0.80）→ 弃权
        assertNull(NumberReviewPolicy.correctBeforeTap(1, 20, true, n("10" to low), false))
    }

    @Test fun `干净文字低中置信不覆盖`() {
        // 完整解析但声学只有 0.85（低于 0.95）→ 不覆盖
        assertNull(NumberReviewPolicy.correctBeforeTap(8, 20, true, n("18" to mid), true))
    }
}
