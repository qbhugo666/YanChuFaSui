package com.voicecontrol.app

import kotlin.math.floor
import kotlin.math.max

/** 只安排屏幕上的编号徽章；目标列表的顺序和实际点击坐标由调用方保持。 */
internal object NumberBadgeLayout {
    data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float)

    data class Position(val x: Float, val y: Float)
    private data class PlacedBox(val left: Float, val top: Float, val right: Float, val bottom: Float)

    /** 屏幕外节点不能编号：绘制层会把其负坐标钳在边缘，滚动后形成一长排幽灵徽章。 */
    fun isTargetOnScreen(target: Bounds, viewportWidth: Float, viewportHeight: Float): Boolean {
        if (target.right <= target.left || target.bottom <= target.top) return false
        val cx = (target.left + target.right) / 2f
        val cy = (target.top + target.bottom) / 2f
        return cx >= 0f && cx < viewportWidth && cy >= 0f && cy < viewportHeight
    }

    /** 只处理折叠标题；普通滚动列表绝不因顶栏推测而漏掉可点击编号。 */
    fun isHiddenBehindTopBar(target: Bounds, topBarBottom: Float, inCollapsibleHeader: Boolean): Boolean =
        inCollapsibleHeader && topBarBottom > 0f && target.bottom <= topBarBottom

    /**
     * 2026-10-08：用户截图中回答行与头像的徽章压在一起，较下层编号完全看不见。
     * 保留旧版的 18% 锚点。只有发生碰撞时，后一个编号才在同一行向右顺排；
     * 行尾放不下时向下一行续排。与原目标的编号和实际点击坐标均不变。
     */
    fun place(
        targets: List<Bounds>,
        viewportWidth: Int,
        viewportHeight: Int,
        badgeSize: Float,
        gap: Float
    ): List<Position?> {
        if (viewportWidth < badgeSize || viewportHeight < badgeSize || badgeSize <= 0f) {
            return List(targets.size) { null }
        }
        val spacing = max(1f, gap)
        val stride = badgeSize + spacing
        val half = badgeSize / 2f
        val maxLeft = viewportWidth - badgeSize
        val maxTop = viewportHeight - badgeSize
        val occupied = mutableListOf<PlacedBox>()

        fun fitRow(startLeft: Float, top: Float): PlacedBox? {
            var left = startLeft
            // 从左到右扫描已放的徽章：撞到哪个就移到它的右边，不反向挪动较大的编号。
            for (box in occupied) {
                val sameRow = top < box.bottom + spacing && top + badgeSize + spacing > box.top
                if (sameRow && left < box.right + spacing && left + badgeSize + spacing > box.left) {
                    left = box.right + spacing
                }
                if (left > maxLeft) return null
            }
            return PlacedBox(left, top, left + badgeSize, top + badgeSize)
        }

        return targets.map { target ->
            // 与旧版相同的 18% 锚点；仅在被占用时移动绘制位置。
            val startLeft = (target.left + (target.right - target.left) * 0.09f - half)
                .coerceIn(0f, maxLeft)
            val startTop = (target.top + (target.bottom - target.top) * 0.09f - half)
                .coerceIn(0f, maxTop)
            var box: PlacedBox? = null
            // 有限行数内先向下换行；贴近底部时再向上找空行，绝不压住已有编号。
            val downRows = floor((maxTop - startTop) / stride).toInt()
            val upRows = floor(startTop / stride).toInt()
            for (row in 0..downRows) {
                box = fitRow(startLeft, startTop + row * stride)
                if (box != null) break
            }
            if (box == null) {
                for (row in 1..upRows) {
                    box = fitRow(startLeft, startTop - row * stride)
                    if (box != null) break
                }
            }
            if (box == null) null else {
                // 已放列表保持从左到右排序，让下一枚徽章只需线性扫描一次。
                val insertAt = occupied.indexOfFirst { it.left > box.left }
                if (insertAt < 0) occupied.add(box) else occupied.add(insertAt, box)
                Position(box.left + half, box.top + half)
            }
        }
    }
}
