package com.voicecontrol.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class NumberBadgeLayoutTest {
    @Test
    fun scrolledOffscreenTargetsAreNotEligibleForNumbering() {
        val visible = NumberBadgeLayout.Bounds(20f, 100f, 200f, 180f)
        val scrolledAbove = NumberBadgeLayout.Bounds(20f, -300f, 200f, -220f)
        val scrolledBelow = NumberBadgeLayout.Bounds(20f, 1300f, 200f, 1380f)
        val partlyVisibleButUntappable = NumberBadgeLayout.Bounds(20f, -300f, 200f, 100f)
        assertTrue(NumberBadgeLayout.isTargetOnScreen(visible, 580f, 1200f))
        assertTrue(!NumberBadgeLayout.isTargetOnScreen(scrolledAbove, 580f, 1200f))
        assertTrue(!NumberBadgeLayout.isTargetOnScreen(scrolledBelow, 580f, 1200f))
        assertTrue(!NumberBadgeLayout.isTargetOnScreen(partlyVisibleButUntappable, 580f, 1200f))
    }

    @Test
    fun collapsedScrollableHeaderBehindFixedToolbarIsNotNumbered() {
        val collapsedHeader = NumberBadgeLayout.Bounds(52f, 148f, 1218f, 324f)
        val toolbarButton = NumberBadgeLayout.Bounds(1047f, 156f, 1151f, 338f)
        val answerCard = NumberBadgeLayout.Bounds(0f, 474f, 1268f, 847f)
        assertTrue(NumberBadgeLayout.isHiddenBehindTopBar(collapsedHeader, 474f, true))
        assertTrue(!NumberBadgeLayout.isHiddenBehindTopBar(toolbarButton, 474f, false))
        assertTrue(!NumberBadgeLayout.isHiddenBehindTopBar(answerCard, 474f, true))
        // 即使坐标完全相同，普通滚动列表的节点也不能凭“顶栏可能遮挡”猜测后删除。
        assertTrue(!NumberBadgeLayout.isHiddenBehindTopBar(collapsedHeader, 474f, false))
    }

    @Test
    fun laterNumberMovesRightOnlyWhenOriginalBadgesOverlap() {
        val target = NumberBadgeLayout.Bounds(100f, 100f, 200f, 200f)
        val separate = NumberBadgeLayout.Bounds(350f, 100f, 450f, 200f)
        val positions = NumberBadgeLayout.place(listOf(target, target, separate),
            580, 1200, 24f, 3f).filterNotNull()
        assertEquals(3, positions.size)
        assertEquals(109f, positions[0].x, 0.01f) // 原版 18% 锚点原样保留
        assertEquals(109f, positions[0].y, 0.01f)
        assertTrue(positions[1].x >= positions[0].x + 27f)
        assertEquals(positions[0].y, positions[1].y, 0.01f)
        assertEquals(359f, positions[2].x, 0.01f) // 没有碰撞就不挪动
    }

    @Test
    fun overlappingAnswerAndAvatarKeepBothNumbersVisible() {
        // 用户截图里的整条回答、头像和文字目标在左上区域挤在一起。
        val targets = listOf(
            NumberBadgeLayout.Bounds(0f, 580f, 580f, 810f),
            NumberBadgeLayout.Bounds(18f, 595f, 59f, 638f),
            NumberBadgeLayout.Bounds(51f, 599f, 160f, 641f),
            NumberBadgeLayout.Bounds(0f, 830f, 580f, 1050f),
            NumberBadgeLayout.Bounds(18f, 845f, 59f, 888f)
        )
        val placed = NumberBadgeLayout.place(targets, 580, 1200, 24f, 3f)
        assertEquals(targets.size, placed.size)
        placed.forEach { assertNotNull(it) }
        val positions = placed.filterNotNull()
        for (i in positions.indices) {
            for (j in i + 1 until positions.size) {
                assertTrue("$i and $j overlap",
                    abs(positions[i].x - positions[j].x) >= 26.9f ||
                        abs(positions[i].y - positions[j].y) >= 26.9f)
            }
        }
    }

    @Test
    fun crowdedTopBarDoesNotPileNumbersAtScreenEdge() {
        val targets = (0 until 30).map { index ->
            NumberBadgeLayout.Bounds((index % 10) * 5f, 0f, (index % 10) * 5f + 32f, 48f)
        }
        val positions = NumberBadgeLayout.place(targets, 580, 1200, 24f, 3f).filterNotNull()
        assertEquals(30, positions.size)
        assertEquals(30, positions.toSet().size)
        assertTrue(positions.all { it.x in 12f..568f && it.y in 12f..1188f })
    }
}
