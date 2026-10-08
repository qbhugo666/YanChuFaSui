package com.voicecontrol.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Window
import android.widget.EditText
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * 设置页（v0.31.0，对标设计稿）：语音 / 反馈(震动) / 服务(无障碍) / 关于。
 * 震动开关默认关（用户拍板），SharedPreferences 持久化，VoiceService 执行命令时读取。
 */
class SettingsActivity : ThemedActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<android.view.View>(R.id.btn_back).setOnClickListener { finish() }

        // 深色模式：跟随系统（默认）/ 浅色 / 深色，选中立即整页生效
        val darkValue = findViewById<TextView>(R.id.tv_dark_value)
        val modes = arrayOf("跟随系统", "浅色", "深色")
        fun refreshDarkLabel() {
            val mode = getSharedPreferences("app", MODE_PRIVATE)
                .getInt("dark_mode", ThemedActivity.DARK_FOLLOW_SYSTEM)
            darkValue.text = modes[mode.coerceIn(0, 2)]
        }
        refreshDarkLabel()
        // v0.56.22：单一动作的卡（外观/反馈/服务）整卡可按——按下整块下沉（NeuCardView 按压反转阴影）
        findViewById<android.view.View>(R.id.card_appearance).setOnClickListener {
            showDarkModeDialog()
        }

        // 震动反馈开关（默认关）；点卡片任意处=切换开关，开关自身点击照常
        val sw = findViewById<Switch>(R.id.sw_vibrate)
        sw.isChecked = getSharedPreferences("app", MODE_PRIVATE).getBoolean("vibrate_feedback", false)
        sw.setOnCheckedChangeListener { _, checked ->
            getSharedPreferences("app", MODE_PRIVATE).edit()
                .putBoolean("vibrate_feedback", checked).apply()
        }
        findViewById<android.view.View>(R.id.card_feedback).setOnClickListener {
            sw.isChecked = !sw.isChecked
        }

        // 识别灵敏度滑块（v0.38.0，1~10 格，默认 5=作者日常基准；VAD 每会话新建→下次会话生效）
        val sensValue = findViewById<TextView>(R.id.tv_sens_value)
        val seek = findViewById<SeekBar>(R.id.seek_sensitivity)
        // v0.56.11：MIUI 换肤引擎会在加载时盖掉 XML 里声明的滑轨/滑块（真机实锤变绿皮），
        // 代码里再钉一次，运行时赋值优先级最高
        seek.progressDrawable = resources.getDrawable(R.drawable.neu_slider_track, theme)
        seek.thumb = resources.getDrawable(R.drawable.neu_slider_thumb, theme)
        fun refreshSensText(level: Int) {
            sensValue.text = when {
                level == RecognitionSensitivity.DEFAULT_LEVEL -> "$level（默认推荐）"
                level >= RecognitionSensitivity.MAX_LEVEL -> "$level · 远场"
                level == 9 -> "$level · 弱声"
                else -> "$level"
            }
        }
        // 档位色（v0.56.11）：1~8 品牌蓝（与开关指示灯同源）· 9 橙=弱声 · 10 红=远场，
        // 只染进度细线——滑块保持中性凸面，不再整条同色（v0.55 的绿在雾蓝底上像贴纸）
        fun refreshSensColor(level: Int) {
            val color = when {
                level >= RecognitionSensitivity.MAX_LEVEL -> 0xFFFF3B30
                level == 9 -> 0xFFFF9500
                else -> 0xFF246BFE
            }.toInt()
            seek.progressTintList = android.content.res.ColorStateList.valueOf(color)
        }
        val savedLevel = RecognitionSensitivity.level(this)
        seek.max = RecognitionSensitivity.MAX_LEVEL - RecognitionSensitivity.MIN_LEVEL
        seek.progress = savedLevel - RecognitionSensitivity.MIN_LEVEL
        refreshSensText(savedLevel)
        refreshSensColor(savedLevel)
        // 拖动档位轻震（v0.56.21）：每跨一格"哒"一下，连续拖动=连续短震；跟随震动反馈开关
        var lastTickLevel = savedLevel
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val level = progress + RecognitionSensitivity.MIN_LEVEL
                refreshSensText(level)
                refreshSensColor(level)
                if (fromUser && level != lastTickLevel) {
                    lastTickLevel = level
                    tickVibrate()
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}

            override fun onStopTrackingTouch(sb: SeekBar?) {
                val level = (sb?.progress ?: 0) + RecognitionSensitivity.MIN_LEVEL
                RecognitionSensitivity.save(this@SettingsActivity, level)
                Toast.makeText(this@SettingsActivity, "灵敏度已设为 $level，下次会话生效", Toast.LENGTH_SHORT).show()
            }
        })

        // 自定义说法（v0.39.0）：语音把自己的说法绑到动作
        findViewById<android.view.View>(R.id.row_custom_say).setOnClickListener {
            startActivity(Intent(this, BindingActivity::class.java))
        }

        // 常用词（v0.42.0）：个人热词，识别优先成这些字
        findViewById<android.view.View>(R.id.row_custom_vocab).setOnClickListener {
            startActivity(Intent(this, VocabActivity::class.java))
        }

        // 备份（v0.49.0）：导出/导入个人配置（指令+词典+3 项偏好），换机/重装免重设
        findViewById<android.view.View>(R.id.row_config_export).setOnClickListener {
            exportPersonalConfig()
        }
        findViewById<android.view.View>(R.id.row_config_import).setOnClickListener {
            showImportDialog()
        }

        // 无障碍服务：状态展示 + 点击跳系统设置
        val state = findViewById<TextView>(R.id.tv_a11y_state)
        fun refreshA11y() {
            val on = AccessibilityHelper.isServiceEnabled(this)
            state.text = if (on) "已开启" else "未开启"
            state.setTextColor(if (on) 0xFF34C759.toInt() else 0xFFFF3B30.toInt())
        }
        refreshA11y()
        findViewById<android.view.View>(R.id.card_service).setOnClickListener {
            runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }

        // 关于 → 关于页（详细介绍，替代原三行弹窗）
        findViewById<android.view.View>(R.id.row_about).setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
        findViewById<android.view.View>(R.id.row_contact).setOnClickListener {
            ContactDialog.show(this)
        }

        // 请作者喝杯咖啡 → 爱发电
        findViewById<android.view.View>(R.id.row_coffee).setOnClickListener {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(MainActivity.AFDIAN_URL)))
            }.onFailure {
                Toast.makeText(this, "打不开浏览器，请稍后再试", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 导出个人配置（v0.49.0）：打包成文字 → 复制到剪贴板 + 系统分享（发微信收藏/文件传输助手即存档） */
    private fun exportPersonalConfig() {
        val text = runCatching { PersonalConfig.build(this) }.getOrElse {
            Toast.makeText(this, "打包失败，请稍后再试", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("言出法随配置", text))
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "言出法随 个人配置")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        runCatching {
            startActivity(Intent.createChooser(send, "把配置文字发给微信收藏/文件传输助手保存"))
        }.onFailure {
            Toast.makeText(this, "已复制到剪贴板，可手动粘贴保存", Toast.LENGTH_LONG).show()
        }
    }

    /** 导入个人配置（v0.49.0）：粘贴 → 合并（只增改不删）→ 汇报计数；深色模式若被改则整页重载 */
    private fun showImportDialog() {
        val dialog = AlertDialog.Builder(this)
            .setTitle("导入个人配置")
            .setView(R.layout.dialog_config_import)
            .setPositiveButton("导入", null)   // 先校验再关弹窗，监听在 show() 之后替换
            .setNegativeButton("取消", null)
            .create()
        dialog.show()
        val input = dialog.findViewById<EditText>(R.id.et_config_import)!!
        val hint = dialog.findViewById<TextView>(R.id.tv_config_hint)!!
        val darkBefore = getSharedPreferences("app", MODE_PRIVATE)
            .getInt("dark_mode", ThemedActivity.DARK_FOLLOW_SYSTEM)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isEmpty()) {
                hint.text = "请先粘贴导出的配置文字"
                return@setOnClickListener
            }
            val counts = runCatching { PersonalConfig.apply(this, text) }.getOrElse { e ->
                hint.text = e.message ?: "导入失败"
                return@setOnClickListener
            }
            dialog.dismiss()
            val msg = buildString {
                append("导入完成：指令 ${counts.bindings} 条、词典 ${counts.vocab} 词、设置 ${counts.prefs} 项")
                if (counts.skipped > 0) append("；跳过无效 ${counts.skipped} 条")
            }
            // Toast 用 applicationContext：深色模式被导入改动时 recreate 不至于把提示吞掉
            Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
            val darkAfter = getSharedPreferences("app", MODE_PRIVATE)
                .getInt("dark_mode", ThemedActivity.DARK_FOLLOW_SYSTEM)
            if (darkAfter != darkBefore) recreate()
        }
    }

    /** 随选朗读是否已启用（微信编号/点击/听写的白名单钥匙） */
    private fun stsEnabled(): Boolean {
        val s = android.provider.Settings.Secure.getString(
            contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return s.contains("SelectToSpeakService", ignoreCase = true)
    }

    override fun onResume() {
        super.onResume()
        // 从系统设置页返回时刷新无障碍状态
        val state = findViewById<TextView>(R.id.tv_a11y_state)
        val on = AccessibilityHelper.isServiceEnabled(this)
        state.text = if (on) "已开启" else "未开启"
        state.setTextColor(if (on) 0xFF34C759.toInt() else 0xFFFF3B30.toInt())
    }

    /** 灵敏度拖动档位轻震（v0.56.21）：跟随「震动反馈」开关，12ms 短震=哒的一声 */
    private fun tickVibrate() {
        if (!getSharedPreferences("app", MODE_PRIVATE).getBoolean("vibrate_feedback", false)) return
        val vib = getSystemService(VIBRATOR_SERVICE) as? android.os.Vibrator ?: return
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            vib.vibrate(android.os.VibrationEffect.createOneShot(12, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vib.vibrate(12)
        }
    }

    /** 卡片式深色模式选择弹窗：居中标题 + 三行单选（右侧蓝点指示）+ 完成胶囊；选中即生效 */    private fun showDarkModeDialog() {
        val modes = arrayOf("跟随系统", "浅色", "深色")
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(R.layout.dialog_dark_mode)
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)

        val radios = listOf(
            dialog.findViewById<ImageView>(R.id.radio_follow),
            dialog.findViewById<ImageView>(R.id.radio_light),
            dialog.findViewById<ImageView>(R.id.radio_dark)
        )
        fun refreshRadios() {
            val current = getSharedPreferences("app", MODE_PRIVATE)
                .getInt("dark_mode", ThemedActivity.DARK_FOLLOW_SYSTEM)
            radios.forEachIndexed { i, iv ->
                iv.setImageResource(if (i == current.coerceIn(0, 2)) R.drawable.ic_radio_on else R.drawable.ic_radio_off)
            }
        }
        refreshRadios()

        val rows = listOf(R.id.row_follow, R.id.row_light, R.id.row_dark)
        rows.forEachIndexed { i, rowId ->
            dialog.findViewById<android.view.View>(rowId).setOnClickListener {
                getSharedPreferences("app", MODE_PRIVATE).edit().putInt("dark_mode", i).apply()
                refreshRadios()
                // 给用户 180ms 看到勾选反馈，再按新主题整页重载
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    dialog.dismiss()
                    recreate()
                }, 180)
            }
        }
        dialog.findViewById<android.view.View>(R.id.btn_dark_done).setOnClickListener { dialog.dismiss() }

        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        val dm = resources.displayMetrics
        dialog.window?.setLayout((dm.widthPixels * 0.82).toInt(), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}
