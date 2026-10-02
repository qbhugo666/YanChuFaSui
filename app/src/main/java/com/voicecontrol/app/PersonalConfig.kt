package com.voicecontrol.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 个人配置导出/导入（v0.49.0）：换手机/重装 App 免重设。
 * 载荷 = 自定义指令 + 个人词典 + 3 项偏好（识别灵敏度/深色模式/震动反馈）。
 * 不含设备相关数据：无障碍授权（照护者重新给）、滑动学习缓存、使用记录、崩溃记录——
 * 那些换了机器也用不上或本就不该跟着走。
 *
 * 格式：{"_v":1,"app":"yanchufasui","bindings":[..],"vocab":[..],"prefs":{..}}
 * （两个存储文件本体都没有版本字段，导出包自带 _v，将来格式升级靠它识别兼容，
 *  先例：scroll_mech_cache.json 的 _v、UsageLog 双格式兼容解析）
 *
 * 导入是「合并」不是「替换」（2026-09-14 用户拍板）：逐条走现有校验器
 * （CustomBindings.upsert / CustomVocab.validateWord + 指令撞车检查），
 * 只增改、绝不删除手机上已有数据；无效条目跳过计数，最后由调用方汇报。
 */
object PersonalConfig {

    /** 导入文本防御上限（字符）：正常配置几 KB，超限视为不是配置文本 */
    private const val MAX_INPUT_CHARS = 200_000

    /** 指令条数防御上限（正常用户 <20 条） */
    private const val MAX_BINDINGS = 300

    /**
     * 布尔偏好安全读取（2026-09-29 收尾项1）：仅当 JSON 值**确实是布尔**才返回它；
     * 字段缺失或类型错误（字符串"true"/数字1/null）一律返回 null = 调用方保持现值不动。
     * 旧代码 optBoolean(key, false) 会把类型错误悄悄当成 false 落盘——用户开着的功能
     * 被一份坏备份无声关闭。纯函数可 JVM 单测（PersonalConfigTest）。
     */
    internal fun booleanOrNull(prefs: JSONObject, key: String): Boolean? = when (val v = prefs.opt(key)) {
        is Boolean -> v
        else -> null
    }

    /** 导入结果计数（给用户的汇报素材） */
    data class ImportCounts(
        val bindings: Int,
        val vocab: Int,
        val prefs: Int,
        val skipped: Int,
    )

    /** 打包当前个人配置为可分享的文字（格式化 JSON，人也能读） */
    fun build(context: Context): String {
        val prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)
        val root = JSONObject()
            .put("_v", 1)
            .put("app", "yanchufasui")
            .put("bindings", JSONArray().apply {
                CustomBindings.all(context).forEach { b ->
                    put(JSONObject().put("phrase", b.phrase).put("action", b.action))
                }
            })
            .put("vocab", JSONArray().apply {
                CustomVocab.all(context).forEach { put(it) }
            })
            .put(
                "prefs", JSONObject()
                    .put("dark_mode", prefs.getInt("dark_mode", ThemedActivity.DARK_FOLLOW_SYSTEM))
                    .put("vibrate_feedback", prefs.getBoolean("vibrate_feedback", false))
                    .put("sensitivity_level", RecognitionSensitivity.level(context))
            )
        return root.toString(2)
    }

    /**
     * 合并导入：逐条校验、只增改不删。格式不对抛 IllegalArgumentException（消息直接给用户看）。
     * 偏好只收 3 个白名单键；深色模式若被改动，由调用方对比后 recreate()。
     * 文件量级仅 KB，与 VocabActivity 同口径在调用线程直接执行。
     */
    fun apply(context: Context, text: String): ImportCounts {
        val t = text.trim()
        if (t.length > MAX_INPUT_CHARS) throw IllegalArgumentException("内容太长，不是配置文字")
        val root = runCatching { JSONObject(t) }.getOrNull()
            ?: throw IllegalArgumentException("不是有效的配置内容（应为导出的那段文字）")
        if (root.optInt("_v", -1) != 1 || root.optString("app") != "yanchufasui") {
            throw IllegalArgumentException("不是本软件导出的配置")
        }

        var okBindings = 0
        var okVocab = 0
        var okPrefs = 0
        var skipped = 0

        // 自定义指令：逐条走 upsert（保护词/长度/动作合法性/语气词都在校验器里）
        val bindings = root.optJSONArray("bindings") ?: JSONArray()
        if (bindings.length() > MAX_BINDINGS) throw IllegalArgumentException("指令条数超出上限")
        for (i in 0 until bindings.length()) {
            val o = bindings.optJSONObject(i)
            if (o == null) {
                skipped++
                continue
            }
            val err = CustomBindings.upsert(context, o.optString("phrase"), o.optString("action"))
            if (err == null) okBindings++ else skipped++
        }

        // 个人词典：撞车词表只加载一次；先批量校验再一次性落盘（50 词上限由 validateWord 把关）
        val vocab = root.optJSONArray("vocab") ?: JSONArray()
        if (vocab.length() > 0) {
            val matcherJson = context.assets.open("commands.json")
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
            val matcher = CommandMatcher.fromJson(matcherJson)
            val existing = CustomVocab.all(context).toMutableList()
            for (i in 0 until vocab.length()) {
                val word = vocab.optString(i)
                val err = CustomVocab.validateWord(word, existing) { w ->
                    matcher.commandCollision(w)?.let { "与指令「$it」发音相近" }
                }
                if (err == null) {
                    existing.add(word)
                    okVocab++
                } else {
                    skipped++
                }
            }
            if (okVocab > 0) CustomVocab.save(context, existing)
        }

        // 偏好：白名单三键，范围合法才收（autostart_guided 等设备状态一律不进）
        val prefsIn = root.optJSONObject("prefs")
        if (prefsIn != null) {
            val dark = prefsIn.optInt("dark_mode", -1)
            if (dark in 0..2) {
                context.getSharedPreferences("app", Context.MODE_PRIVATE).edit()
                    .putInt("dark_mode", dark).apply()
                okPrefs++
            }
            if (prefsIn.has("vibrate_feedback")) {
                // 类型错误不导入（booleanOrNull），保持现值——坏备份不得无声改用户设置
                booleanOrNull(prefsIn, "vibrate_feedback")?.let { v ->
                    context.getSharedPreferences("app", Context.MODE_PRIVATE).edit()
                        .putBoolean("vibrate_feedback", v).apply()
                    okPrefs++
                }
            }
            val sens = prefsIn.optInt("sensitivity_level", -1)
            if (sens in RecognitionSensitivity.MIN_LEVEL..RecognitionSensitivity.MAX_LEVEL) {
                RecognitionSensitivity.save(context, sens)
                okPrefs++
            }
            // 2026-10-03：声音二审已移除，旧备份的 enhanced_decision_enabled 不再导入。
        }
        return ImportCounts(okBindings, okVocab, okPrefs, skipped)
    }
}
