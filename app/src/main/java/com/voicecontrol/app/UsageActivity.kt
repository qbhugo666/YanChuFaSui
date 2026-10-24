package com.voicecontrol.app

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.lang.ref.WeakReference
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * 使用记录页（v0.31.0）：显示最近执行的命令（时间+结果文本）。
 * 数据来自 UsageLog（SessionState.lastMatch 中央挂钩），零新依赖，代码动态生成行。
 */
class UsageActivity : ThemedActivity() {
    private val exportWorker = ThreadPoolExecutor(0, 1, 10, TimeUnit.SECONDS, LinkedBlockingQueue()) { work ->
        Thread(work, "feedback-export")
    }
    private val exportTask = FeedbackExportTask<FeedbackReport>(
        exportWorker,
        Executor { Handler(Looper.getMainLooper()).post(it) },
        FeedbackFiles::discard,
    )
    private lateinit var reportBuilder: FeedbackReportBuilder
    private var pageStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UsageLog.init(applicationContext)
        reportBuilder = FeedbackReportBuilder(applicationContext)
        val builder = reportBuilder
        exportWorker.execute { runCatching { builder.pruneFiles() } }
        setContentView(R.layout.activity_usage)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }

        findViewById<View>(R.id.btn_clear).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("清空使用记录")
                .setMessage("确定要清空全部记录吗？")
                .setPositiveButton("清空") { _, _ ->
                    cancelExport()
                    UsageLog.clear()
                    render()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // 导出反馈（v0.36.0）：版本/设备/使用记录/应用日志 打包成文本，走系统分享发给开发者
        findViewById<View>(R.id.btn_export).setOnClickListener {
            val owner = WeakReference(this)
            val started = exportTask.start(builder::build) { result ->
                owner.get()?.let { activity ->
                    activity.findViewById<View>(R.id.btn_export).isEnabled = true
                    if (activity.pageStarted && !activity.isFinishing && !activity.isDestroyed) {
                        activity.deliverReport(result)
                    }
                }
            }
            if (started) findViewById<View>(R.id.btn_export).isEnabled = false
        }

        render()
    }

    override fun onStart() {
        super.onStart()
        pageStarted = true
    }

    override fun onStop() {
        pageStarted = false
        cancelExport()
        super.onStop()
    }

    override fun onDestroy() {
        exportTask.close()
        super.onDestroy()
    }

    private fun cancelExport() {
        exportTask.cancel()
        findViewById<View>(R.id.btn_export).isEnabled = true
    }

    private fun deliverReport(result: Result<FeedbackReport>) {
        result.onSuccess { report ->
            runCatching {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "言出法随 问题反馈")
                    val file = report.attachment
                    if (file != null) {
                        val uri = android.net.Uri.Builder().scheme("content").authority("$packageName.feedback").appendPath(file.name).build()
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_TEXT, "言出法随完整反馈见附件（含使用记录和诊断信息，不含录音）")
                        clipData = android.content.ClipData.newRawUri("问题反馈", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } else putExtra(Intent.EXTRA_TEXT, report.text)
                }
                startActivity(Intent.createChooser(send, "把反馈信息发送给开发者"))
            }.onFailure {
                exportWorker.execute { FeedbackFiles.discard(report) }
                showExportFailure()
            }
        }.onFailure { showExportFailure() }
    }

    private fun showExportFailure() {
        Toast.makeText(this, "打不开分享，请稍后再试", Toast.LENGTH_SHORT).show()
    }

    private fun render() {
        val container = findViewById<LinearLayout>(R.id.usage_container)
        container.removeAllViews()
        val dp = { v: Int -> (v * resources.displayMetrics.density + 0.5f).toInt() }
        val entries = UsageLog.all()

        if (entries.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "还没有使用记录\n开个会话说几句话，这里就会显示机器听到的话和执行的操作"
                textSize = 14f
                setTextColor(getColor(R.color.text_secondary))
                setPadding(dp(4), dp(24), dp(4), 0)
            })
            return
        }

        entries.asReversed().forEach { e ->   // 最新的在最上面
            container.addView(TextView(this).apply {
                text = UsageLog.timeLabel(e.time)
                textSize = 12f
                setTextColor(getColor(R.color.text_secondary))
                setPadding(dp(4), dp(12), dp(4), 0)
            })
            // 识别原文（v0.57.0）：机器听到的话——旧记录没有此字段则跳过。
            // v0.58 措辞修正（M5）：原文是 ASR 机器转写的猜测，不是用户亲口确认的话——
            // 写「机器听到」避免误导排查
            if (e.heard.isNotBlank()) {
                container.addView(TextView(this).apply {
                    text = "机器听到：${e.heard}"
                    textSize = 13f
                    setTextColor(getColor(R.color.text_secondary))
                    setPadding(dp(4), dp(1), dp(4), 0)
                })
            }
            // 声音复核处置（v0.58 M5）：普通列表只显示用户可读短标签；
            // 置信度/耗时等专业详情见导出反馈的「二审详情」。旧记录只有详情串时降级显示详情
            if (e.decisionLabel.isNotBlank()) {
                container.addView(TextView(this).apply {
                    text = "二审：${AudioDecisionRouting.displayLabel(e.decisionLabel, e.audioDecision)}"
                    textSize = 11f
                    setTextColor(getColor(R.color.text_secondary))
                    setPadding(dp(4), dp(1), dp(4), dp(1))
                })
            } else if (e.audioDecision.isNotBlank()) {
                container.addView(TextView(this).apply {
                    text = "增强识别：${e.audioDecision}"
                    textSize = 11f
                    setTextColor(getColor(R.color.text_secondary))
                    setPadding(dp(4), dp(1), dp(4), dp(1))
                })
            }
            // 执行结果；heard 有值而 text 为空 = 这句话没触发任何操作（语气词/未命中）
            container.addView(TextView(this).apply {
                if (e.text.isNotBlank()) {
                    text = e.text
                    textSize = 15f
                    setTextColor(getColor(R.color.text_primary))
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                } else {
                    text = "（未触发操作）"
                    textSize = 13f
                    setTextColor(getColor(R.color.text_secondary))
                }
                setPadding(dp(4), dp(1), dp(4), dp(2))
            })
            if (e.uid.isNotBlank() && e.heard.isNotBlank()) {
                SpeechAudit.intentLabel(e.confirmedIntent)?.let { label ->
                    container.addView(TextView(this).apply {
                        val effect = SpeechAudit.ObservedEffect.entries.firstOrNull { it.name == e.observedEffect }?.label
                        text = "我的本意：$label${effect?.let { " · $it" }.orEmpty()}"
                        textSize = 12f
                        setTextColor(getColor(R.color.text_secondary))
                        setPadding(dp(4), dp(2), dp(4), 0)
                    })
                }
            }
        }
    }
}
