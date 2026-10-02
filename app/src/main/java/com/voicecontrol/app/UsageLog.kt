package com.voicecontrol.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 使用记录（v0.31.0）：由 SessionState.lastMatch 的自定义 setter 中央挂钩——
 * 一处改动捕获全部命令执行结果（成功✅/失败/提示），零散布点。
 * v0.57.0：记录识别原文（机器听到的话）——handleRecognized 开头 appendHeard() 建条，
 * 之后本句触发的 lastMatch 赋值自动关联进同一条；未触发任何操作的句子（语气词/未命中）
 * text 留空，使用记录页显示「未触发操作」。这样每句话「听到什么 → 做了什么」都能对上，
 * 误识别（说了 A 被听成 B）一眼可查。
 * v0.58（2026-09-28 M4/M5）：条目带 uid（sessionId+句序，识别线程生成）——二审摘要
 * 按 uid 关联而非按文本匹配（同一句连说两遍不再串条目）；decisionLabel 存用户可读的
 * 处置短标签（已复核一致/已纠正/暂时不可用…），专业详情仍在 audioDecision 字段。
 * 存储：内存列表 + filesDir/usage_log.json（上限 200 条，写穿）。
 * 保留期（v0.36.0）：仅保留最近 48 小时（用户拍板：隐私优先 + 不占存储），加载/写入时自动清理。
 * 持久化格式：对象 {"t":时间,"h":原文,"x":结果,"a":二审详情,"u":句ID,"d":处置标签}；
 * 加载兼容旧对象 {"t","h","x","a"}、{"t","x"} 与更旧的数组 [[t,x],...]（v0.43.1 修复过的格式），
 * 旧条目 uid/label 为空照常显示。
 */
object UsageLog {

    data class Entry(
        val time: Long,
        val text: String,
        val heard: String = "",
        val audioDecision: String = "",
        val uid: String = "",
        val decisionLabel: String = "",
        val speechAudit: String = "",
        val confirmedIntent: String = "",
        val observedEffect: String = "",
    )

    private const val MAX_ENTRIES = 200

    // 记录保留期：48 小时，超期自动清除
    private const val RETENTION_MS = 48 * 60 * 60 * 1000L

    private val entries = mutableListOf<Entry>()
    private var file: File? = null
    private val fmt = SimpleDateFormat("HH:mm", Locale.CHINA)

    /** 幂等初始化（VoiceService/MainActivity onCreate 各调一次）；加载历史记录并清理超期项。
     *  兼容三种持久化格式：新对象 {"t","h","x"}、旧对象 {"t","x"}、数组 [[t,x],...]，
     *  单条解析失败跳过、不整批丢弃 */
    @Synchronized fun init(ctx: Context) {
        if (file != null) return
        file = File(ctx.filesDir, "usage_log.json")
        loadLocked()
    }

    /** JVM 单测专用（2026-09-29 收尾项5）：注入持久化文件并立即加载——同文两次 uid 关联、
     *  旧 JSON 兼容等回归不依赖 Android Context；生产代码勿用 */
    @Synchronized fun initForTest(f: File) {
        file = f
        entries.clear()
        loadLocked()
    }

    @Synchronized private fun loadLocked() {
        val f = file ?: return
        runCatching {
            if (f.exists()) {
                val arr = JSONArray(f.readText())
                for (i in 0 until arr.length()) {
                    val item = arr.get(i)
                    val entry = when (item) {
                        is JSONObject -> Entry(
                            item.getLong("t"), item.optString("x"), item.optString("h"),
                            item.optString("a"), item.optString("u"), item.optString("d"),
                            when (val audit = item.opt("s")) {
                                is JSONObject -> audit.toString() // 兼容C1首包的嵌套对象
                                is String -> audit
                                else -> ""
                            }, item.optString("f"), item.optString("o"),
                        )
                        is JSONArray -> Entry(item.getLong(0), item.getString(1))
                        else -> null
                    } ?: continue
                    entries.add(entry)
                }
            }
            prune()
        }
    }

    /** 记录一句识别原文（v0.57.0）：handleRecognized 开头调用，本句后续的 lastMatch
     *  赋值经 append() 自动落到这一条上；uid（v0.58）= 句子身份，供二审摘要精确关联 */
    @Synchronized fun appendHeard(text: String, uid: String = "") {
        if (text.isBlank()) return
        prune()
        entries.add(Entry(System.currentTimeMillis(), "", text.trim(), "", uid))
        while (entries.size > MAX_ENTRIES) entries.removeAt(0)
        persist()
    }

    @Synchronized fun append(text: String) {
        if (text.isBlank()) return
        prune()
        val last = entries.lastOrNull()
        if (last != null && last.heard.isNotBlank() && last.text.isBlank()) {
            // 本句识别已建条 → 执行结果关联进去
            entries[entries.size - 1] = last.copy(text = text)
        } else {
            entries.add(Entry(System.currentTimeMillis(), text))
        }
        while (entries.size > MAX_ENTRIES) entries.removeAt(0)
        persist()
    }

    /** 二审摘要关联（v0.58）：优先按 uid 精确定位本句；uid 为空或找不到（旧条目/SIMULATE
     *  注入无 uid）再按识别文本回退旧法（取最后一条同文本）。 */
    @Synchronized fun attachAudioDecision(uid: String, heard: String, label: String, note: String) {
        if (note.isBlank()) return
        val targetIndex = if (uid.isNotBlank()) entries.indexOfLast { it.uid == uid }
        else entries.indexOfLast { it.heard == heard }
        if (targetIndex < 0) {
            // uid 没对上（极端：条目已被 48h 清理/上限挤出）→ 文本回退再试一次
            val fallback = entries.indexOfLast { it.heard == heard }
            if (fallback < 0) return
            entries[fallback] = entries[fallback].copy(audioDecision = note, decisionLabel = label)
            persist()
            return
        }
        entries[targetIndex] = entries[targetIndex].copy(audioDecision = note, decisionLabel = label)
        persist()
    }

    /** 异步动作结果写回（2026-09-30 执行反馈分层轮）：按 utteranceId 定位**发起那句话**的
     *  条目更新结果行——重复回放完成/停止/被替换等迟到事件写回原句，不 append 新条、
     *  不串到用户后来讲的另一句；uid 对不上（旧数据/条目被挤出/空 uid）则 append 新条
     *  保留时间线。 */
    @Synchronized fun updateOutcome(uid: String, text: String) {
        if (text.isBlank()) return
        prune()
        if (uid.isNotBlank()) {
            val idx = entries.indexOfLast { it.uid == uid }
            if (idx >= 0) {
                entries[idx] = entries[idx].copy(text = text)
                persist()
                return
            }
        }
        entries.add(Entry(System.currentTimeMillis(), text))
        while (entries.size > MAX_ENTRIES) entries.removeAt(0)
        persist()
    }

    @Synchronized fun all(): List<Entry> = entries.toList()

    /** C1：诊断与用户标注只认本句 UID。旧条/迟到事件绝不按相同文字猜另一句。 */
    @Synchronized internal fun attachSpeechAudit(uid: String, audit: JSONObject): Boolean {
        prune()
        if (uid.isBlank() || audit.optString("uid") != uid) return false
        val i = entries.indexOfLast { it.uid == uid }
        if (i < 0) return false
        entries[i] = entries[i].copy(speechAudit = audit.toString())
        persist()
        return true
    }

    @Synchronized internal fun markIntent(uid: String, intent: String, observed: SpeechAudit.ObservedEffect): Boolean {
        prune()
        if (uid.isBlank() || !SpeechAudit.validIntent(intent)) return false
        val i = entries.indexOfLast { it.uid == uid }
        if (i < 0) return false
        entries[i] = entries[i].copy(confirmedIntent = intent, observedEffect = observed.name)
        persist()
        return true
    }

    @Synchronized internal fun clearIntent(uid: String): Boolean {
        if (uid.isBlank()) return false
        val i = entries.indexOfLast { it.uid == uid }
        if (i < 0) return false
        entries[i] = entries[i].copy(confirmedIntent = "", observedEffect = "")
        persist()
        return true
    }

    fun timeLabel(t: Long): String = fmt.format(Date(t))

    @Synchronized fun clear() {
        entries.clear()
        persist()
    }

    /** 清除超过保留期（48 小时）的记录 */
    @Synchronized private fun prune() {
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        entries.removeAll { it.time < cutoff }
    }

    @Synchronized private fun persist() {
        val f = file ?: return
        runCatching {
            val arr = JSONArray()
            entries.forEach { e ->
                arr.put(JSONObject().put("t", e.time).put("h", e.heard).put("x", e.text)
                    .put("a", e.audioDecision).put("u", e.uid).put("d", e.decisionLabel)
                    // 识别时不反复解析200条历史诊断；只在导出/评分时按需解析。
                    .put("s", e.speechAudit.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
                    .put("f", e.confirmedIntent).put("o", e.observedEffect))
            }
            f.writeText(arr.toString())
        }
    }
}
