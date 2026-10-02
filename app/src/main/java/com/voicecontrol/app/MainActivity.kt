package com.voicecontrol.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import android.widget.LinearLayout
import android.widget.TextView

/** 会话状态：供前台服务与界面之间共享（同一进程内的简单单例） */
object SessionState {

    /** 2026-10-03：主页只显示会话开关，单条命令反馈由胶囊和使用记录承接。 */
    enum class Phase { IDLE, LISTENING }

    @Volatile var lastText: String = ""

    /** 最近一次执行结果；setter 中央挂钩 → 使用记录，独立于主页会话显示。 */
    var lastMatch: String = ""
        set(value) {
            field = value
            if (value.isNotBlank()) {
                UsageLog.append(value)
            }
        }

    @Volatile var status: String = "会话进行中…"
    @Volatile var phase: SessionState.Phase = SessionState.Phase.IDLE
}

class MainActivity : ThemedActivity() {

    companion object {
        const val PERMISSION_REQUEST = 100

        // 爱发电主页（用户真实链接，2026-09-09 提供；SettingsActivity 共用）
        const val AFDIAN_URL = "https://afdian.com/a/hugoqb"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var pendingSessionStart = false

    // 从电池优化/自启动授权页返回后，是否要自动续接启动链（推进到下一环）
    private var pendingChainResume = false

    // 主页只切换未启动/会话中两张卡，不再逐句展示执行状态。
    private lateinit var heroIdle: View
    private lateinit var heroStatus: View
    private var renderedIdle: Boolean? = null
    private lateinit var heroLogo: LogoCircleView   // 未启动卡的圆形（v0.55.6 水波涟漪载体）

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RetiredAudioFiles.clean(applicationContext)
        UsageLog.init(applicationContext)
        CrashCatcher.register(applicationContext)   // 崩溃记录器（v0.43.0，幂等）
        setContentView(R.layout.activity_main)
        findViewById<TextView>(R.id.tv_version).text =
            "v${packageManager.getPackageInfo(packageName, 0).versionName}"

        // 英雄区双态
        heroIdle = findViewById(R.id.hero_idle)
        heroStatus = findViewById(R.id.hero_status)

        // v0.56.4：双态英雄卡高度对齐。状态卡多一颗「结束」按钮，天然比未启动卡高一截，
        // 切换瞬间卡片会跳一下。首次布局后按较高一态给两卡定高（按像素定，随系统字号自适应，
        // 以后改文案也不影响）
        heroIdle.post {
            val w = heroIdle.width
            if (w <= 0) return@post
            heroStatus.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val h = maxOf(heroIdle.height, heroStatus.measuredHeight)
            heroIdle.layoutParams.height = h
            heroStatus.layoutParams.height = h
            heroIdle.requestLayout()
            heroStatus.requestLayout()
        }

        // 未启动态：点击开始会话
        heroIdle.setOnClickListener {
            vibrateFeedback()   // v0.55.10：点下即触感确认（跟随设置页「震动反馈」开关，与执行指令震感同源）
            tryStartSession()
        }
        heroLogo = findViewById(R.id.logo_idle)
        // 会话中：结束按钮
        findViewById<View>(R.id.btn_end_session).setOnClickListener {
            val i = Intent(this, VoiceService::class.java)
            i.action = VoiceService.ACTION_STOP
            startService(i)
        }

        findViewById<View>(R.id.row_commands).setOnClickListener { showHelpDialog() }

        findViewById<View>(R.id.row_usage).setOnClickListener {
            startActivity(Intent(this, UsageActivity::class.java))
        }

        findViewById<View>(R.id.tv_about).setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }

        findViewById<View>(R.id.tv_support).setOnClickListener { showSupportDialog() }

        // 设置齿轮 → 设置页（v0.56.20：点下有反应——水波纹（XML 背景）+ 齿轮转 30° + 震动确认，
        // 140ms 后再跳转，让转动能被看见；与深色模式行的延迟生效同款手法）
        val gear = findViewById<ImageView>(R.id.btn_settings)
        gear.setOnClickListener {
            vibrateFeedback()
            gear.animate().rotation(gear.rotation + 30f).setDuration(220L)
                .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                startActivity(Intent(this, SettingsActivity::class.java))
            }, 140L)
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), PERMISSION_REQUEST)
        } else if (!isBatteryOptimizationIgnored()) {
            // 长期存活链第①环：电池优化豁免（防 MIUI 省电杀进程→无障碍被解绑）
            requestBatteryExemption()
        } else if (!isAutostartGuided()) {
            // 长期存活链第②环：MIUI 自启动授权引导（一次性）
            showAutostartGuide()
        } else if (!AccessibilityHelper.isServiceEnabled(this) &&
            // 已授权 WRITE_SECURE_SETTINGS 时静默自愈（写回开关），不再打扰用户手动开
            !AccessibilityHelper.trySelfHeal(this)
        ) {
            // 自愈不可用（未做过电脑授权）：退回原有手动引导弹窗（商用标准路径）
            showAccessibilityGuide()
        } else {
            // 全部就绪 → 自动开始（纯语音用户零点击依赖，必须保留）
            startSession("冷启动自动（应用启动）")
        }
    }

    // 仅前台检查会话开关；状态没变时不修改视图，后台停止轮询。
    private val statusPoll = object : Runnable {
        override fun run() {
            renderSessionCard(SessionState.phase)
            handler.postDelayed(this, 500)
        }
    }

    override fun onStart() {
        super.onStart()
        handler.post(statusPoll)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(statusPoll)
    }

    /** 2026-10-03 用户要求移除命令完成展示；只在会话开始/结束时切卡。 */
    private fun renderSessionCard(p: SessionState.Phase) {
        val idle = p == SessionState.Phase.IDLE
        if (renderedIdle == idle) return
        renderedIdle = idle
        heroIdle.visibility = if (idle) View.VISIBLE else View.GONE
        heroStatus.visibility = if (idle) View.GONE else View.VISIBLE
        if (idle) return
        // 会话建立后结束启动等待涟漪，卡片保持「正在聆听」。
        heroLogo.stopWaitingRipple()
    }

    /** 触感反馈（v0.55.10）：点「开始控制」时短震一下，跟随设置页「震动反馈」开关（与 VoiceService 执行指令震感同参数） */
    private fun vibrateFeedback() {
        if (!getSharedPreferences("app", MODE_PRIVATE).getBoolean("vibrate_feedback", false)) return
        val vib = getSystemService(VIBRATOR_SERVICE) as? android.os.Vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vib.vibrate(android.os.VibrationEffect.createOneShot(30, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vib.vibrate(30)
        }
    }

    /** 点「开始会话」入口：录音权限 → 无障碍自检 → 静默自愈/弹引导 → 开会话 */
    private fun tryStartSession() {        heroLogo.startWaitingRipple()   // v0.55.6：水波涟漪=启动等待中，会话建立（renderSessionCard 切卡）即停
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), PERMISSION_REQUEST)
            heroLogo.stopWaitingRipple()
            return
        }
        if (!isBatteryOptimizationIgnored()) {
            requestBatteryExemption()
            heroLogo.stopWaitingRipple()
            return
        }
        if (!isAutostartGuided()) {
            showAutostartGuide()
            heroLogo.stopWaitingRipple()
            return
        }
        if (!AccessibilityHelper.isServiceEnabled(this) &&
            !AccessibilityHelper.trySelfHeal(this)
        ) {
            showAccessibilityGuide()
            heroLogo.stopWaitingRipple()
            return
        }
        startSession("手动点击/授权链续接")
    }

    // ===== 长期存活保障（商用核心诉求：无障碍几天不掉线）=====
    // 第一性原理：无障碍掉线只有两条路——①进程被杀（MIUI 省电/清理）②被系统或用户关闭。
    // ① 用「电池优化白名单 + 自启动授权」根治（一次授权，系统级豁免，几天几周不掉）；
    // ② 无法阻止（系统安全设计），用 v0.9 的检测+引导闭环兜底。
    // 不搞守护进程/双进程保活那套——复杂、耗电、被系统打击，简单的事不复杂化。

    /** 是否已豁免电池优化（Doze/省电策略不杀本进程） */
    private fun isBatteryOptimizationIgnored(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    /** 弹系统标准授权框：一键加入电池优化白名单（国内保活标准做法，一次授权永久生效） */
    private fun requestBatteryExemption() {
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_battery)
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)

        dialog.findViewById<View>(R.id.btn_battery_allow).setOnClickListener {
            dialog.dismiss()
            pendingChainResume = true
            runCatching {
                startActivity(Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                ))
            }.onFailure { showBatterySettingsFallback() }
        }
        dialog.findViewById<View>(R.id.btn_battery_skip).setOnClickListener {
            dialog.dismiss()
            android.widget.Toast.makeText(this, "已跳过：无障碍可能被系统自动关闭", android.widget.Toast.LENGTH_LONG).show()
        }

        dialog.show()
        val dm = resources.displayMetrics
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        dialog.window?.setLayout((dm.widthPixels * 0.82).toInt(), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    /** 少数机型没有标准授权弹框：退回到电池优化设置列表页 */
    private fun showBatterySettingsFallback() {
        runCatching {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    /** 自启动引导是否已展示过（一次性，不反复烦用户） */
    private fun isAutostartGuided(): Boolean =
        getSharedPreferences("app", MODE_PRIVATE).getBoolean("autostart_guided", false)

    /** MIUI 自启动授权引导：进程被杀后系统能否自动拉起无障碍服务，取决于这个开关 */
    private fun showAutostartGuide() {
        AlertDialog.Builder(this)
            .setTitle("最后一步：允许自启动")
            .setMessage(
                "建议允许「自启动」权限：进程万一被系统清理后，" +
                "只有允许自启动，无障碍服务才能被自动拉起，不用你手动重开。\n\n" +
                "在打开的页面里找到「言出法随」，把开关打开即可" +
                "（若页面中没有该选项，说明你的手机不需要此设置，点「已开过了」继续）。\n\n" +
                "（另外建议：在最近任务页长按本应用卡片 → 加锁，双保险）"
            )
            .setPositiveButton("去开启") { _, _ ->
                markAutostartGuided()
                pendingChainResume = true
                openAutostartSettings()
            }
            .setNegativeButton("已开过了") { _, _ -> markAutostartGuided() }
            .show()
    }

    private fun markAutostartGuided() {
        getSharedPreferences("app", MODE_PRIVATE).edit().putBoolean("autostart_guided", true).apply()
    }

    /** 打开 MIUI 自启动管理页；打不开（非 MIUI 或入口变更）退回应用详情页 */
    private fun openAutostartSettings() {
        val miui = Intent().setClassName(
            "com.miui.securitycenter",
            "com.miui.permcenter.autostart.AutoStartManagementActivity"
        )
        runCatching { startActivity(miui) }.onFailure {
            runCatching {
                startActivity(Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null)
                ))
            }
        }
    }

    /** 商用级引导弹框：讲清「为什么需要 + 怎么开」，一键直达系统无障碍设置 */
    private fun showAccessibilityGuide() {
        AlertDialog.Builder(this)
            .setTitle("开启无障碍服务")
            .setMessage(
                "言出法随需要借助系统「无障碍服务」执行点按、滑动等语音指令。" +
                "该权限仅用于实现语音控制功能，不会收集或上传您的任何信息。\n\n" +
                "点「去开启」直达系统设置；也可在设置中搜索「无障碍」或「辅助功能」，" +
                "在「已下载的应用」中找到言出法随并开启（不同机型路径略有差异）。" +
                "开启后返回即自动继续。\n\n" +
                "建议在最近任务中锁定本应用，以保持后台长期运行。"
            )
            .setPositiveButton("去开启") { _, _ ->
                pendingSessionStart = true
                openAccessibilitySettings()
            }
            .setNegativeButton("暂不", null)
            .show()
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (_: Exception) {
            // 极少数机型没有标准入口，退回应用详情页（手动找无障碍）
            runCatching {
                startActivity(Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null)
                ))
            }
        }
    }

    /** 从设置页回来：开着了就自动续接（用户不用再点开始）；没开则温和提示 */
    override fun onResume() {
        super.onResume()
        // 从电池优化/自启动授权页返回：重新跑一遍启动链，推进到下一环（已满足的自动跳过）
        if (pendingChainResume) {
            pendingChainResume = false
            tryStartSession()
            return
        }
        if (pendingSessionStart) {
            if (AccessibilityHelper.isServiceEnabled(this)) {
                pendingSessionStart = false
                android.widget.Toast.makeText(this, "无障碍已开启，自动开始会话…", android.widget.Toast.LENGTH_SHORT).show()
                startSession()
            } else {
                android.widget.Toast.makeText(this, "等待无障碍开启——开好后回到这里即可自动继续", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (granted) {
                // 权限刚批下来，继续走长期存活+无障碍自检链路（不是直接开会话）
                tryStartSession()
            } else {
                android.widget.Toast.makeText(this, "录音权限被拒绝，请到系统设置里允许麦克风权限", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startSession(source: String = "手动/未知") {
        // 会话启动来源留痕（2026-09-15 反锁案取证结论）：冷启动自动开始 vs 手动点击 vs 授权续接
        // 分不清时（如家人切回软件触发冷启动自动开会话）事后可从导出的诊断事件段回查
        com.voicecontrol.app.DiagnosticsHelper.log("SESSION_START 来源=$source")
        val i = Intent(this, VoiceService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i)
        } else {
            startService(i)
        }
    }

    // ===== 指令说明（iOS 卡片式弹窗，v0.25 重排）=====

    /** 帮助数据：分区 → (命令, 说明)。正式说法为标准命令，日常口语说法同样可以识别 */
    private val helpSections = listOf(
        "基础操作" to listOf(
            "向上滑动" to "屏幕内容向上滚动，浏览下方内容",
            "向下滑动" to "屏幕内容向下滚动，返回上方内容",
            "向左滑动" to "画面向左切换，查看右侧内容",
            "向右滑动" to "画面向右切换，查看左侧内容",
            "轻点" to "轻点一次屏幕中心位置",
            "双击" to "快速连续轻点两次屏幕中心",
        ),
        "滑动微调" to listOf(
            "向上摇移 / 向下摇移" to "屏幕内容小幅度上下移动，便于对准位置",
            "向左摇移 / 向右摇移" to "屏幕内容小幅度左右移动",
        ),
        "画面缩放" to listOf(
            "双指放大" to "模拟双指张开，放大图片或网页细节",
            "双指缩小" to "模拟双指合拢，将画面恢复原样",
        ),
        "页面导航" to listOf(
            "返回" to "返回上一级页面",
            "前往主屏幕" to "回到手机桌面",
            "打开 App 切换器" to "查看并切换最近运行的应用",
        ),
        "点击屏幕内容" to listOf(
            "点击 抖音" to "点击屏幕上显示「抖音」字样的元素",
            "点击 设置" to "直接说出屏幕上的文字即可点击对应内容",
        ),
        "文字输入" to listOf(
            "输入" to "说出接下来要写的内容，停顿后自动填入输入框",
            "把 A 替换成 B" to "将输入框中的 A 改为 B，如「把不错替换成很好」",
            "光标左移 / 光标右移" to "移动输入框中的光标位置",
            "删除" to "删除光标前的一个字",
            "清空输入框" to "清空输入框中的全部文字",
        ),
        "编号与网格" to listOf(
            "显示编号" to "为可点击元素标注数字，说「点击 5」即可点击对应元素",
            "显示网格" to "将屏幕划分为网格，说「点击 5」点击对应格子",
            "退回" to "网格放大后返回上一级网格",
            "隐藏显示" to "关闭编号或网格显示",
        ),
        "长按操作" to listOf(
            "长按" to "进入长按待命，再说数字或「中间」执行长按",
        ),
        "设备控制" to listOf(
            "增加音量 / 降低音量" to "调节媒体音量（上限 80%，保护听力）",
            "静音" to "关闭媒体声音",
            "锁屏" to "熄灭屏幕并结束当前会话",
            "通知中心 / 控制中心" to "打开对应的系统面板",
        ),
        "结束会话" to listOf(
            "退出" to "结束当前会话，释放麦克风",
        ),
        "使用说明" to listOf(
            "支持日常口语说法" to "如「上滑」「左滑」与正式命令均可识别",
            "同音字自动纠正" to "如说「华动」将自动识别为「滑动」",
            "安静环境识别更准确" to "媒体外放声音可能干扰识别效果",
            "自动休眠与继续" to "每 5 分钟需说「继续」续期（最多 4 次，单次会话最长 25 分钟）；到期前 20 秒预警，预警出现前说「继续」不算数",
        ),
    )

    /** 渲染 iOS 卡片式帮助弹窗：分区灰标题 + 命令(黑 medium)/说明(灰)行 + 胶囊完成 */
    private fun showHelpDialog() {
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_help)
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)

        val container = dialog.findViewById<LinearLayout>(R.id.help_container)
        val dp = { v: Int -> (v * resources.displayMetrics.density + 0.5f).toInt() }
        helpSections.forEach { (section, rows) ->
            container.addView(TextView(this).apply {
                text = section
                textSize = 13f
                setTextColor(getColor(R.color.text_secondary))
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                setPadding(dp(2), dp(14), dp(2), dp(4))
            })
            rows.forEach { (cmd, desc) ->
                container.addView(TextView(this).apply {
                    text = cmd
                    textSize = 15f
                    setTextColor(getColor(R.color.text_primary))
                    typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
                    setPadding(dp(2), dp(5), dp(2), 0)
                })
                container.addView(TextView(this).apply {
                    text = desc
                    textSize = 13.5f
                    setTextColor(getColor(R.color.text_secondary))
                    setPadding(dp(14), dp(1), dp(2), dp(2))
                })
            }
        }

        dialog.findViewById<View>(R.id.btn_help_done).setOnClickListener { dialog.dismiss() }

        dialog.show()
        val dm = resources.displayMetrics
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        dialog.window?.setLayout((dm.widthPixels * 0.88).toInt(), (dm.heightPixels * 0.72).toInt())
    }

    // ===== 支持作者（v0.55.14：爱发电 + 微信 + 支付宝 三通道卡片，主界面零新增元素） =====

    private fun showSupportDialog() {
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_support)
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)
        // 点遮罩（半透明背景区域）→ 关闭弹窗
        dialog.findViewById<View>(R.id.scrim).setOnClickListener { dialog.dismiss() }
        dialog.findViewById<View>(R.id.btn_afdian).setOnClickListener {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AFDIAN_URL))) }
        }
        dialog.findViewById<View>(R.id.btn_support_done).setOnClickListener { dialog.dismiss() }
        // 点码 → 全屏查看器（微信看图式：全屏内长按 → 底部弹「保存到相册」）
        dialog.findViewById<ImageView>(R.id.qr_wechat).setOnClickListener {
            showQrFullscreen(R.drawable.support_qr_wechat)
        }
        dialog.findViewById<ImageView>(R.id.qr_alipay).setOnClickListener {
            showQrFullscreen(R.drawable.support_qr_alipay)
        }
        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        // v0.56.15：窗口必须全屏 + 关掉系统压暗——此前窗口 88%x82%，窗口内自绘遮罩与
        // 窗口外系统压暗叠加，屏幕中央会叠出一块更黑的矩形（用户实机反馈）
        dialog.window?.setLayout(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        )
        dialog.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    }

    /** 点码 → 全屏查看器：黑底大图，点任意处退出，长按弹底部保存菜单（微信看图式交互） */
    private fun showQrFullscreen(resId: Int) {
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = android.widget.LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF000000.toInt())
            gravity = Gravity.CENTER
            setOnClickListener { dialog.dismiss() }
        }
        val iv = ImageView(this).apply {
            setImageResource(resId)
            adjustViewBounds = true
            setOnLongClickListener { showQrSaveSheet(resId); true }
        }
        root.addView(iv, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        dialog.setContentView(root)
        dialog.setCancelable(true)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        dialog.show()
    }

    /** 全屏查看器长按 → 底部操作菜单：保存到相册 / 取消 */
    private fun showQrSaveSheet(resId: Int) {
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.sheet_qr_save)
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
        dialog.window?.setGravity(Gravity.BOTTOM)
        dialog.findViewById<View>(R.id.btn_save_gallery).setOnClickListener {
            saveQrToGallery(resId); dialog.dismiss()
        }
        dialog.findViewById<View>(R.id.btn_sheet_cancel).setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    /** 长按收款码保存到相册（Pictures/言出法随；Android 10+ MediaStore 免存储权限，失败弹提示不静默） */
    private fun saveQrToGallery(resId: Int) {
        val name = if (resId == R.drawable.support_qr_alipay) "言出法随_支付宝收款码.png"
        else "言出法随_微信收款码.png"
        runCatching {
            val bitmap = android.graphics.BitmapFactory.decodeResource(resources, resId)
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/言出法随")
                }
            }
            val uri = contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore insert failed")
            contentResolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            }
            Toast.makeText(this, "已保存到相册（Pictures/言出法随）", Toast.LENGTH_LONG).show()
        }.onFailure {
            Toast.makeText(this, "保存失败：${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
