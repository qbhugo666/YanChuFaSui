package com.voicecontrol.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 使用记录页（v0.31.0）：显示最近执行的命令（时间+结果文本）。
 * 数据来自 UsageLog（SessionState.lastMatch 中央挂钩），零新依赖，代码动态生成行。
 */
class UsageActivity : ThemedActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UsageLog.init(applicationContext)
        pruneFeedbackFiles()
        setContentView(R.layout.activity_usage)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }

        findViewById<View>(R.id.btn_clear).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("清空使用记录")
                .setMessage("确定要清空全部记录吗？")
                .setPositiveButton("清空") { _, _ ->
                    UsageLog.clear()
                    render()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // 导出反馈（v0.36.0）：版本/设备/使用记录/应用日志 打包成文本，走系统分享发给开发者
        findViewById<View>(R.id.btn_export).setOnClickListener {
            runCatching {
                val report = buildReport()
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "言出法随 问题反馈")
                    if (FeedbackSharePolicy.needsFile(report)) {
                        val dir = java.io.File(cacheDir, "speech_feedback").apply { mkdirs() }
                        val name = "speech-feedback-${java.util.UUID.randomUUID().toString().replace("-", "")}.txt"
                        java.io.File(dir, name).writeText(report, Charsets.UTF_8)
                        val uri = android.net.Uri.Builder().scheme("content").authority("$packageName.feedback").appendPath(name).build()
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_TEXT, "言出法随完整反馈见附件（含使用记录和诊断信息，不含录音）")
                        clipData = android.content.ClipData.newRawUri("问题反馈", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        pruneFeedbackFiles()
                    } else putExtra(Intent.EXTRA_TEXT, report)
                }
                startActivity(android.content.Intent.createChooser(send, "把反馈信息发送给开发者"))
            }.onFailure {
                Toast.makeText(this, "打不开分享，请稍后再试", Toast.LENGTH_SHORT).show()
            }
        }

        render()
    }

    private fun pruneFeedbackFiles() {
        val dir = java.io.File(cacheDir, "speech_feedback")
        val files = dir.listFiles()?.filter { it.isFile && FeedbackSharePolicy.validFilename(it.name) }
            ?.sortedByDescending { it.lastModified() }.orEmpty()
        val cutoff = System.currentTimeMillis() - FeedbackSharePolicy.RETENTION_MS
        files.forEachIndexed { i, file -> if (i >= FeedbackSharePolicy.MAX_FILES || file.lastModified() < cutoff) file.delete() }
    }

    /** 组装问题反馈文本：设备环境 + 内存画像 + 崩溃记录 + 使用记录 + 应用日志 + 系统错误日志 */
    private fun buildReport(): String {
        val sb = StringBuilder()
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
        sb.appendLine("===== 言出法随 · 问题反馈 =====")
        sb.appendLine("导出时间：${df.format(Date())}")
        runCatching {
            val info = packageManager.getPackageInfo(packageName, 0)
            sb.appendLine("版本：v${info.versionName} (${info.longVersionCode})")
        }
        sb.appendLine("设备：${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        sb.appendLine("系统：Android ${android.os.Build.VERSION.RELEASE} (${android.os.Build.VERSION.INCREMENTAL})")
        // 内存画像（v0.43.0）：低配机诊断一线信息
        runCatching { sb.appendLine("内存：${CrashCatcher.memoryLine(this)}") }
        sb.appendLine()
        sb.appendLine("---- 崩溃记录 ----")
        val crashes = CrashCatcher.dumpAll(this)
        sb.appendLine(crashes ?: "（无崩溃记录）")
        sb.appendLine()
        sb.appendLine("---- 使用记录（最近在前；机器听到=ASR 转写原文，箭头行=执行结果）----")
        val all = UsageLog.all()
        if (all.isEmpty()) {
            sb.appendLine("（无记录）")
        } else {
            val f = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA)
            all.asReversed().forEach { e ->
                sb.appendLine("${f.format(Date(e.time))}  ${e.text.ifBlank { "（未触发操作）" }}${if (e.heard.isNotBlank()) "  ｜原文：${e.heard}" else ""}${if (e.decisionLabel.isNotBlank()) "  ｜复核：${e.decisionLabel}" else ""}${if (e.audioDecision.isNotBlank()) "  ｜二审详情：${e.audioDecision}" else ""}")
            }
        }
        sb.appendLine()
        sb.appendLine("---- 高频二审账目（用户标记的意图；未标记不计正确率；不含录音或声学特征）----")
        sb.appendLine(SpeechAudit.export(all).toString())
        sb.appendLine()
        sb.appendLine("---- 诊断事件（关键事件环形缓冲，logcat 被冲掉后的真相来源）----")
        sb.appendLine(DiagnosticsHelper.dumpEvents())
        sb.appendLine()
        sb.appendLine("---- 应用日志（最近 400 行）----")
        runCatching {
            val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "400", "-s", "VoiceControl"))
            val out = p.inputStream.bufferedReader().readText()
            sb.append(if (out.isBlank()) "（暂无日志）" else out)
        }.onFailure {
            sb.appendLine("（日志读取失败：${it.message}）")
        }
        sb.appendLine()
        sb.appendLine("---- 系统错误日志（崩溃/错误，最近 200 行）----")
        runCatching {
            // -b crash = Java/native 崩溃专用缓冲；*:E = 全局错误级（含低内存杀进程记录）
            val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-b", "crash", "-t", "200"))
            val crash = p.inputStream.bufferedReader().readText()
            sb.append(if (crash.isBlank()) "（无）" else crash)
            sb.appendLine()
            val p2 = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "200", "*:E"))
            val errs = p2.inputStream.bufferedReader().readText()
            sb.append(if (errs.isBlank()) "（无错误级日志）" else errs)
        }.onFailure {
            sb.appendLine("（系统日志读取失败：${it.message}）")
        }
        return sb.toString()
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
