package com.voicecontrol.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * 常用词页（v0.42.0）：个人热词（人名/地名），加入识别引擎热词偏置——
 * 说 huang xin hao 优先识别成「黄信豪」。录入=语音捕获预填 + 可编辑确认框（同音错字照护者改一次），
 * 保存后下次会话生效（识别器每会话按热词文件重建）。
 * 与指令词表的隔离：CustomVocab.validateWord 内 commandCollision 拦截与指令发音相近的词。
 */
class VocabActivity : ThemedActivity() {

    private val handler = Handler(Looper.getMainLooper())

    // 轮询语音捕获结果（录入会话把识别原文写进 VoiceService.lastCaptured）
    private val capturePoll = object : Runnable {
        override fun run() {
            VoiceService.lastCaptured?.let { captured ->
                VoiceService.lastCaptured = null
                showAddDialog(captured)
                return
            }
            handler.postDelayed(this, 400)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vocab)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<View>(R.id.btn_add).setOnClickListener { startCapture() }
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
        if (VoiceService.captureArmed) handler.post(capturePoll)
    }

    override fun onPause() {
        handler.removeCallbacks(capturePoll)
        super.onPause()
    }

    /** 语音录入：武装捕获标志并启动正常会话（安全链全在），第一句识别原文带回弹窗 */
    private fun startCapture(preFill: String = "") {
        VoiceService.lastCaptured = null
        VoiceService.captureArmed = true
        showAddDialog(preFill)
        try {
            val i = Intent(this, VoiceService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(i)
            } else {
                startService(i)
            }
            Toast.makeText(this, "请说出词语，说完自动带回", Toast.LENGTH_LONG).show()
            handler.post(capturePoll)
        } catch (e: Exception) {
            VoiceService.captureArmed = false
            Toast.makeText(this, "录入启动失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /** 添加确认弹窗：识别原文预填、可编辑（同音错字在此纠正，照护者可代改）、可重录 */
    private fun showAddDialog(preFill: String) {
        val dialog = AlertDialog.Builder(this)
            .setTitle("添加常用词")
            .setView(R.layout.dialog_vocab_add)
            .setPositiveButton("保存", null)   // 保存前先校验，监听在 show() 之后替换
            .setNeutralButton("重新录入") { _, _ -> startCapture() }
            .setNegativeButton("取消", null)
            .create()
        dialog.show()
        val input = dialog.findViewById<EditText>(R.id.et_vocab_word)!!
        input.setText(preFill)
        val hint = dialog.findViewById<TextView>(R.id.tv_vocab_hint)!!
        // 2026-10-23：词表在本次弹窗内固定，首次需要撞词检查时加载一次。
        // 每次输入仍执行相同校验，并重新读取已有个人词；不跨弹窗保留缓存。
        val matcher by lazy(LazyThreadSafetyMode.NONE) {
            val json = assets.open("commands.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            CommandMatcher.fromJson(json)
        }
        fun refreshHint() {
            val err = CustomVocab.validateWord(
                input.text.toString(),
                CustomVocab.all(this),
            ) { word ->
                // 指令撞车检查：与指令词/别名发音相近的词不准进名单（防抢指令）
                matcher.commandCollision(word)?.let { cmd ->
                    "与指令「$cmd」发音相近，会影响控制，请更换"
                }
            }
            hint.text = err ?: (if (input.text.length >= CustomVocab.MIN_LEN) "✓ 可保存" else "")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = err == null
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = refreshHint()
        })
        refreshHint()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            val err = CustomVocab.add(this, input.text.toString())
            if (err == null) {
                Toast.makeText(this, "已添加，下次会话生效", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                render()
            } else {
                hint.text = err
            }
        }
    }

    private fun render() {
        val container = findViewById<LinearLayout>(R.id.vocab_container)
        container.removeAllViews()
        val dp = { v: Int -> (v * resources.displayMetrics.density + 0.5f).toInt() }
        val words = CustomVocab.all(this)

        if (words.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "暂无词条\n点击右上角「添加」，说人名或地名即可（同音错字可手动更正）"
                textSize = 14f
                setTextColor(getColor(R.color.text_secondary))
                setPadding(dp(4), dp(24), dp(4), 0)
            })
            return
        }

        words.forEach { w ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = getDrawable(R.drawable.bg_card)
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(10) }
                setOnClickListener {
                    AlertDialog.Builder(this@VocabActivity)
                        .setTitle("删除常用词？")
                        .setMessage("「$w」\n删除后下次会话起不再优先识别")
                        .setPositiveButton("删除") { _, _ ->
                            CustomVocab.remove(this@VocabActivity, w)
                            render()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
                addView(TextView(this@VocabActivity).apply {
                    text = w
                    textSize = 16f
                    setTextColor(getColor(R.color.text_primary))
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                })
                addView(TextView(this@VocabActivity).apply {
                    text = "点击删除"
                    textSize = 12f
                    setTextColor(getColor(R.color.text_secondary))
                })
            }
            container.addView(row)
        }
    }
}
