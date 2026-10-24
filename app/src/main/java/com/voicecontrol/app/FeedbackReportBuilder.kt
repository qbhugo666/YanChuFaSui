package com.voicecontrol.app

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 2026-10-23：耗时收集只持 applicationContext，不把记录页留在后台线程中。 */
internal class FeedbackReportBuilder(context: Context) {
    private val context = context.applicationContext
    private val files = FeedbackFiles(File(this.context.cacheDir, "speech_feedback"))

    fun pruneFiles() = files.prune()

    fun build(): FeedbackReport {
        FeedbackFiles.checkCancellation()
        val sb = StringBuilder()
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
        sb.appendLine("===== 言出法随 · 问题反馈 =====")
        sb.appendLine("导出时间：${df.format(Date())}")
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            @Suppress("DEPRECATION")
            val versionCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
            sb.appendLine("版本：v${info.versionName} ($versionCode)")
        }
        sb.appendLine("设备：${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("系统：Android ${Build.VERSION.RELEASE} (${Build.VERSION.INCREMENTAL})")
        runCatching { sb.appendLine("内存：${CrashCatcher.memoryLine(context)}") }
        sb.appendLine()
        sb.appendLine("---- 崩溃记录 ----")
        FeedbackFiles.checkCancellation()
        sb.appendLine(CrashCatcher.dumpAll(context) ?: "（无崩溃记录）")
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
        appendLog(sb, listOf("logcat", "-d", "-t", "400", "-s", "VoiceControl"), "（暂无日志）", "日志读取失败")
        sb.appendLine()
        sb.appendLine("---- 系统错误日志（崩溃/错误，最近 200 行）----")
        appendLog(sb, listOf("logcat", "-d", "-b", "crash", "-t", "200"), "（无）", "系统日志读取失败")
        sb.appendLine()
        appendLog(sb, listOf("logcat", "-d", "-t", "200", "*:E"), "（无错误级日志）", "系统日志读取失败")
        FeedbackFiles.checkCancellation()
        return files.prepare(sb.toString())
    }

    private fun appendLog(sb: StringBuilder, command: List<String>, empty: String, failure: String) {
        FeedbackFiles.checkCancellation()
        try {
            val output = FeedbackProcessReader.read(command)
            sb.append(if (output.text.isBlank()) empty else output.text)
            if (output.timedOut) sb.appendLine("\n（日志读取超时，已停止收集）")
            if (output.truncated) sb.appendLine("\n（日志过长，已截断）")
        } catch (cancelled: InterruptedException) {
            throw cancelled
        } catch (error: Exception) {
            sb.appendLine("（$failure：${error.message}）")
        }
    }
}
