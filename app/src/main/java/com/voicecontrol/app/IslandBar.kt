package com.voicecontrol.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.transition.ChangeBounds
import android.transition.TransitionManager
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * iOS 灵动岛式顶部胶囊（v0.29.0 全量重写）。
 *
 * 第一性原理：一个视图、一个状态机、一种过渡。
 *  · 形状：GradientDrawable 超大圆角由系统钳制——任何尺寸恒为胶囊，「变直」在代码上不存在路径
 *  · 状态：四态（聆听/命令文字/预警/错误），每次更新恰好落入一态，路由即全部逻辑
 *  · 过渡：全类唯一机制 = ChangeBounds 延迟过渡（先挂过渡、后改内容），尺寸连续、圆角恒圆
 *  · 声波：仅聆听态绘制，高度=麦克风实时振幅（30fps 上限，会话外零开销）；有声音才有波浪
 *
 * 文本前缀（🎤⚡✅⚠️😴🔵）由 VoiceService 的既有合同产生，路由表负责翻译成四态。
 */
class IslandBar(private val context: Context) {

    companion object {
        private const val COLOR_BLUE = 0xFF0A84FF.toInt()
        private const val COLOR_AMBER = 0xFFFF9F0A.toInt()
        private const val COLOR_RED = 0xFFFF453A.toInt()
        private const val WARN_REVERT_MS = 2200L
        private val TEXT_PREFIXES = listOf("⚠️", "😴", "✅", "⚡", "🎤", "🔵")
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density + 0.5f).toInt()

    val root: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
    }

    private val pillView: LinearLayout
    private val pillText: TextView
    private val micView: MicIconView
    private val waveView: WaveView
    private val pillBg = GradientDrawable()

    init {
        micView = MicIconView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18))
        }
        waveView = WaveView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(18)).apply {
                marginStart = dp(6)
            }
        }
        pillText = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            visibility = View.GONE
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxWidth = (context.resources.displayMetrics.widthPixels * 0.7f).toInt()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(6) }
        }
        pillView = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
            addView(micView)
            addView(waveView)
            addView(pillText)
            pillBg.apply {
                shape = GradientDrawable.RECTANGLE
                // 超大圆角由系统钳制为高度一半 → 恒为胶囊
                cornerRadius = dp(100).toFloat()
                setColor(COLOR_BLUE)
            }
            background = pillBg
        }
        root.addView(pillView)
    }

    /** 构建悬浮窗参数（由宿主服务 addView，类型沿用 TYPE_ACCESSIBILITY_OVERLAY） */
    fun windowParams(statusBarHeight: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = statusBarHeight + dp(2)
        }

    /** 旧 updateBar 合同的路由：每次调用恰好映射到四态之一 */
    fun update(text: String) {
        mainHandler.post {
            val clean = stripPrefix(text)
            when {
                clean.contains("无障碍已关闭") ->
                    applyState(COLOR_RED, clean, listening = false, transientWarn = false)
                text.startsWith("😴") || text.startsWith("⚠️") ->
                    applyState(COLOR_AMBER, clean, listening = false, transientWarn = isTransientWarn(text))
                clean.contains("正在聆听") ->
                    applyState(COLOR_BLUE, null, listening = true, transientWarn = false)
                else ->
                    applyState(COLOR_BLUE, clean, listening = true, transientWarn = false)
            }
        }
    }

    private fun isTransientWarn(text: String): Boolean =
        text.contains("没有编号") || text.contains("已到最小格") || text.contains("重复最多")

    private fun stripPrefix(text: String): String {
        for (p in TEXT_PREFIXES) if (text.startsWith(p)) return text.removePrefix(p).trim()
        return text
    }

    /**
     * 唯一的状态应用函数：先挂过渡、后改内容。
     * transientWarn=true 时 WARN_REVERT_MS 后自动回到聆听态（防御性：即使 VoiceService 侧
     * 没有安排复位，警告色也不会卡住）。
     *
     * v0.29.1：尺寸变化不做任何动画，交给布局系统一帧完成自然拉伸——
     * 实测一切过渡动画（scaleX/ChangeBounds）都会产生中间畸变，全部移除。
     */
    private fun applyState(color: Int, text: String?, listening: Boolean, transientWarn: Boolean) {
        if (transientWarn) {
            mainHandler.removeCallbacks(revertRunnable)
            mainHandler.postDelayed(revertRunnable, WARN_REVERT_MS)
        }
        // 文字显示时把声波视图从布局中收起（visibility=GONE 释放宽度）——
        // 只停绘制不收视图的话，声波的空位会在胶囊中间留出一大块间隔
        val showText = text != null
        waveView.visibility = if (showText) View.GONE else View.VISIBLE
        pillBg.setColor(color)
        if (showText) {
            pillText.text = text
            pillText.visibility = View.VISIBLE
        } else {
            pillText.visibility = View.GONE
        }
        waveView.setTextShown(showText)
        waveView.setListening(listening && !showText)
    }

    private val revertRunnable = Runnable {
        applyState(COLOR_BLUE, null, listening = true, transientWarn = false)
    }

    /** 悬浮窗参数引用（addView 时由宿主回填）：供运行中挂/摘 KEEP_SCREEN_ON 用 */
    internal var attachedParams: WindowManager.LayoutParams? = null

    /**
     * 会话期间屏幕常亮（v0.57.6 用户拍板方案 B）：胶囊可见期间屏幕不灭——
     * 语音操作不重置系统无操作息屏计时（注入手势也不算真人操作），看抖音非播放页
     * 1 分钟就灭屏，语音用户得反复唤醒。
     * **双保险设计**：FLAG_KEEP_SCREEN_ON 官方语义即「窗口**可见**期间生效」（GONE 本就不生效），
     * 这里仍按会话起止显式挂/摘，绝不依赖单一系统行为——会话结束/退出/锁屏/看门狗到期
     * 走 doHideBar 同一链路，标志必被摘除；亮屏时长被会话看门狗天然封顶（硬顶 25 分钟），
     * 不违反「不长时间强制亮屏」红线（防电量耗尽导致无法求救）。
     */
    fun setKeepScreenOn(keep: Boolean) {
        mainHandler.post {
            val p = attachedParams ?: return@post
            runCatching {
                p.flags = if (keep) p.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                          else p.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
                @Suppress("DEPRECATION")
                (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager)
                    .updateViewLayout(root, p)
            }
        }
    }

    /** 显示浮层（会话开始）：淡入 */
    fun show() {
        mainHandler.post {
            root.visibility = View.VISIBLE
            root.alpha = 0f
            root.animate().alpha(1f).setDuration(200L).start()
        }
    }

    /** 隐藏浮层（会话结束）：淡出后收起 */
    fun hide() {
        mainHandler.post {
            root.animate().cancel()
            root.animate()
                .alpha(0f)
                .setDuration(180L)
                .withEndAction {
                    root.visibility = View.GONE
                    root.alpha = 1f
                }
                .start()
        }
    }

    /** 摇移应答：胶囊朝拖动方向轻移 8dp 再弹回 */
    fun nudgeToward(dx: Float, dy: Float) {
        mainHandler.post {
            val d = dp(8).toFloat()
            pillView.animate().cancel()
            // v0.57.24 修复位移漂移（用户实锤：连说「向上摇移」胶囊被顶出屏幕上缘）：
            // translationXBy/YBy 是增量动画，弹回半途被下一条摇移命令 cancel 时视图冻结在
            // 半途回程位置，下次增量从残余值起算——每次打断净漂移一点，单调累积。
            // 双保险：起跳前硬复位 + 弹回段用绝对值 translationX/Y(0) 而非增量
            pillView.translationX = 0f
            pillView.translationY = 0f
            pillView.animate()
                .translationXBy(dx * d).translationYBy(dy * d)
                .setDuration(130L)
                .withEndAction {
                    pillView.animate()
                        .translationX(0f).translationY(0f)
                        .setDuration(200L)
                        .start()
                }
                .start()
        }
    }


    /** iOS 风格白色麦克风图标（纯 Path 绘制，不依赖图片资源） */
    private class MicIconView(context: Context) : View(context) {
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 0f
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            strokePaint.strokeWidth = w * 0.09f
            // 麦克风网头：竖直胶囊形
            val bodyW = w * 0.36f
            val bodyH = h * 0.46f
            val bodyL = (w - bodyW) / 2f
            val bodyT = h * 0.08f
            canvas.drawRoundRect(bodyL, bodyT, bodyL + bodyW, bodyT + bodyH, bodyW / 2f, bodyW / 2f, fillPaint)
            // U 型支架：下半圆弧
            val cx = w / 2f
            val arcR = w * 0.30f
            val arcTop = bodyT + bodyH * 0.28f
            canvas.drawArc(cx - arcR, arcTop, cx + arcR, arcTop + arcR * 2f, 0f, 180f, false, strokePaint)
            // 竖线底座
            val stemTop = arcTop + arcR * 2f - strokePaint.strokeWidth / 2f
            val stemBottom = h * 0.92f
            canvas.drawLine(cx, stemTop, cx, stemBottom, strokePaint)
        }
    }
}
