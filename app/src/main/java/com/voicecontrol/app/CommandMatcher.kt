package com.voicecontrol.app

import net.sourceforge.pinyin4j.PinyinHelper
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType
import org.json.JSONObject

/** 汉字转拼音（无声调、小写、空格分隔音节；非汉字原样保留）。v0.55.12 起公开，供听写触发判定共用；实例与 companion 共用。 */
fun pinyinOf(s: String): String {
    val format = HanyuPinyinOutputFormat().apply {
        caseType = HanyuPinyinCaseType.LOWERCASE
        toneType = HanyuPinyinToneType.WITHOUT_TONE
        vCharType = HanyuPinyinVCharType.WITH_V
    }
    val sb = StringBuilder()
    for (ch in s) {
        if (ch.isWhitespace()) { sb.append(' '); continue }
        val arr = try {
            PinyinHelper.toHanyuPinyinStringArray(ch, format)
        } catch (e: Exception) { null }
        if (arr != null && arr.isNotEmpty()) sb.append(arr[0]) else sb.append(ch)
        sb.append(' ')
    }
    return sb.toString().trim()
}

/**
 * 命令匹配层：把语音识别结果纠正到命令词表。
 *
 * 匹配优先级（从高到低，命中即取最高分）：
 *   1. exact         —— 原文与命令/别名完全一致
 *   2. contains      —— 互相包含（治「向右滑动一下」「向右滑」这类多字/漏字）
 *   3. pinyin_exact  —— 拼音完全一致（治同音字，如「华动」→「滑动」）
 *   4. pinyin_fuzzy  —— 拼音音节级编辑距离，距离越小分越高（治近音、个别音节听错）
 *
 * 纯 Kotlin、无 Android 依赖，可做 JVM 单元测试（阶段一：测试基建）。
 * 用 [fromJson] 从 commands.json 文本构建；Android 端由调用方读 assets 后传入。
 */
class CommandMatcher private constructor(
    private val entries: List<Entry>,
    private val customWords: Set<String> = emptySet(),
) {
    data class Entry(
        val id: String,
        val action: String,
        val group: String,
        val word: String,
        val pinyin: String,
    )

    data class Match(
        val id: String,
        val action: String,
        val group: String,
        val matchedWord: String,
        val method: String,
    )

    /**
     * matchStrict 的明细结果（2026-09-28 M2 统一候选语义）：把「无候选」与「同分歧义」
     * 从共用 null 里拆开——match==null 且 ambiguous=false 是没匹配到；ambiguous=true 是
     * 同分、同长度却指向不同动作的明确冲突（如整句同时 contains「显示网格」与「隐藏网格」），
     * 调用方不得再交给文字点击/拼音模糊重新猜（旧链路会兜成一个动作，等于替用户拍板）。
     * 用户自定义绑定（customWords）不受歧义判定压制——同分自定义优先是既有显式规则（v0.39.0）。
     */
    data class StrictOutcome(val match: Match?, val ambiguous: Boolean, val tiedWords: List<String> = emptyList())

    /** 自定义绑定可能复用标准命令的id/group，归属必须查实际绑定词集合。 */
    internal fun isCustomMatch(match: Match): Boolean = match.matchedWord in customWords

    /** 整词精确命中（v0.57.12）：折叠后与命令词/别名**完全相等**才算（contains/拼音不算）。
     *  用途：在册命令优先级判定——听写触发容错不得劫持正式命令（用户点破「删除被听写抢走太蠢」）。
     *  例：「删除」exact 命中 text_delete → 永远按删除命令走；「输入」不是词表词 → 不拦听写 */
    fun matchExact(text: String): Match? {
        val t = collapseDoubled(text.trim())
        if (t.isEmpty()) return null
        for (e in entries) {
            if (t == e.word) return Match(e.id, e.action, e.group, e.word, "exact")
        }
        return null
    }

    /** 精确匹配：exact / contains / pinyin_exact（可靠，最优先）。匹配不到返回 null。
     *  同分歧义（详见 [matchStrictDetailed]）也返回 null——兼容旧调用方；需要区分
     *  「无候选」与「歧义」的调用方（路由层）请用明细版。 */
    fun matchStrict(text: String): Match? {
        val outcome = matchStrictDetailed(text)
        return if (outcome.ambiguous) null else outcome.match
    }

    fun matchStrictDetailed(text: String): StrictOutcome {
        val t = collapseDoubled(text.trim())
        if (t.isEmpty()) return StrictOutcome(null, false)
        val tp = pinyinOf(t)

        var best: Match? = null
        var bestScore = 0
        var bestLength = 0
        var ambiguousBest = false
        val tied = mutableSetOf<String>()

        for (e in entries) {
            val score: Int
            val method: String
            when {
                t == e.word -> { score = 100; method = "exact" }
                t.contains(e.word) && e.word.length >= 2 -> { score = 90; method = "contains" }
                e.word.contains(t) && t.length >= 2 -> { score = 88; method = "contains" }
                tp == e.pinyin -> { score = 80; method = "pinyin_exact" }
                else -> continue
            }
            // 自定义绑定不进歧义判定：同分自定义优先是用户显式意图（v0.39.0 既有规则）
            val isCustom = e.word in customWords
            when {
                score > bestScore || (score == bestScore && e.word.length > bestLength) -> {
                    bestScore = score
                    bestLength = e.word.length
                    best = Match(e.id, e.action, e.group, e.word, method)
                    ambiguousBest = false
                    tied.clear()
                }
                score == bestScore && e.word.length == bestLength && best != null && !isCustom &&
                    best.action != e.action && best.matchedWord !in customWords -> {
                    // 同级、同长度却指向相反动作时不按 JSON 排列顺序猜一个。
                    // 2026-09-28 从 contains(90) 一档推广到全部同级（exact 除外不可能重复；
                    // pinyin_exact 同音不同动作同理是明确冲突）
                    ambiguousBest = true
                    tied.add(best.matchedWord); tied.add(e.word)
                }
            }
        }
        return if (ambiguousBest) StrictOutcome(null, true, tied.toList()) else StrictOutcome(best, false)
    }

    /** 模糊匹配：拼音音节级编辑距离（兜底纠错，仅在精确匹配和文字点击都未命中时用）。
     *  同分且指向不同动作=明确歧义 → 返回 null（2026-09-29 收尾项2：旧实现按词表顺序
     *  取第一个，等于替用户猜动作）；需要区分「无候选/歧义」的调用方用 [matchFuzzyDetailed] */
    fun matchFuzzy(text: String): Match? {
        val outcome = matchFuzzyDetailed(text)
        return if (outcome.ambiguous) null else outcome.match
    }

    /** 模糊匹配明细（2026-09-29）：同分、不同动作、双方都非自定义绑定 → Ambiguous
     *  （自定义豁免：同分自定义优先是 v0.39.0 既有显式规则）；同分同动作=别名等价，取先出现者 */
    fun matchFuzzyDetailed(text: String): StrictOutcome {
        val t = collapseDoubled(text.trim())
        if (t.isEmpty()) return StrictOutcome(null, false)
        val tp = pinyinOf(t)

        var best: Match? = null
        var bestScore = -1.0
        var ambiguousBest = false
        val tied = mutableSetOf<String>()

        for (e in entries) {
            val d = pinyinFuzzyDist(tp, e.pinyin)
            if (d < 0) continue
            val score = 70 - d * 10
            val isCustom = e.word in customWords
            when {
                score > bestScore -> {
                    bestScore = score
                    best = Match(e.id, e.action, e.group, e.word, "pinyin_fuzzy")
                    ambiguousBest = false
                    tied.clear()
                }
                score == bestScore && best != null -> {
                    if (e.action != best.action) {
                        if (best.matchedWord in customWords) {
                            // 已持有自定义候选：自定义优先，保持
                        } else if (isCustom) {
                            // 自定义候选替换标准候选（用户显式意图压过歧义）
                            best = Match(e.id, e.action, e.group, e.word, "pinyin_fuzzy")
                            ambiguousBest = false
                            tied.clear()
                        } else {
                            ambiguousBest = true
                            tied.add(best.matchedWord); tied.add(e.word)
                        }
                    }
                    // 同分同动作 = 同一动作的别名等价，保持先出现者（合并语义）
                }
            }
        }
        return if (ambiguousBest) StrictOutcome(null, true, tied.toList()) else StrictOutcome(best, false)
    }

    /** 在候选词里找与 text 拼音最接近的词（文字点击目标纠错，如「抖婴」→「抖音」）；无相近返回 null */
    fun resolveClosest(text: String, candidates: List<String>): String? {
        val t = text.trim()
        if (t.isEmpty()) return null
        if (candidates.any { it == t }) return t
        val tp = pinyinOf(t)
        // 单音节目标不做热词纠错：App 名/屏幕词都是多字词，单字（如「吧」ba）会被拼音模糊距离
        // 误匹配到含该字的双字词（「贴吧」tie ba），造成「点击8→吧→贴吧」这类错误。
        if (tp.split(" ").count { it.isNotEmpty() } <= 1) return null
        var best: String? = null
        var bestDist = Double.MAX_VALUE
        for (c in candidates) {
            val d = pinyinFuzzyDist(tp, pinyinOf(c))
            if (d >= 0 && d < bestDist) {
                bestDist = d
                best = c
            }
        }
        return best
    }

    /** 提取所有热词（命令词 + 别名，去重、长词优先），供识别引擎做 contextual biasing */
    fun hotwords(): List<String> {
        return entries.map { it.word }.distinct().sortedByDescending { it.length }
    }

    /**
     * 指令撞车检查（v0.42.0 常用词准入）：候选词若与任何指令词/别名发音相近（拼音模糊距离达标），
     * 返回撞车的指令词；无撞车返回 null。用于把「能抢指令的常用词」挡在准入门外——
     * 例：「话动」撞「滑动」。纯比对，无副作用，JVM 可测。
     */
    fun commandCollision(word: String): String? {
        val wp = pinyinOf(word)
        if (wp.split(" ").count { it.isNotEmpty() } == 0) return null
        var best: String? = null
        var bestDist = Double.MAX_VALUE
        for (e in entries) {
            val d = pinyinFuzzyDist(wp, e.pinyin)
            if (d >= 0 && d < bestDist) {
                bestDist = d
                best = e.word
            }
        }
        return best
    }

    companion object {
        // 拼音模糊匹配：音节级编辑距离，最多容忍相差几个音节
        private const val PINYIN_FUZZY_MAX_DIST = 3

        /**
         * 连说两遍折叠（2026-09-22）：命令没触发时用户自然会说第二遍，VAD 未断句就被 ASR
         * 合成一句「X X」（如「经典经典」「上滑上滑」）——长度翻倍后 contains/拼音距离全失效
         * （「经典经典」vs「轻点」4:2 音节 dist 3 归一化 0.75 > 0.65 被拒，用户使用记录实锤）。
         * 同一 2~4 字片段精确重复两遍时折叠成单份再匹配，全部命令通用受益；
         * 听写内容不经命令匹配层，不受影响。
         */
        internal fun collapseDoubled(t: String): String {
            if (t.length % 2 != 0 || t.length < 4 || t.length > 8) return t
            val half = t.length / 2
            return if (t.substring(0, half) == t.substring(half)) t.substring(0, half) else t
        }

        // 拼音模糊匹配：归一化距离阈值（音节数越多越宽松）。
        // v0.55.3 0.5→0.65（半夜小声残缺触发不了，用户拍板牺牲误触换触发）；
        // v0.57.9 声母韵母拆分计分配套校准 0.65→0.55：拆分把差异计细分（原 1 分差可记 0.5），
        // 同阈值实际容差放宽 ~40%，闲话「个活动」0.625 钻进 0.65 门（单测拦截）——收回等量。
        // 校验：半夜残缺「死边号」新计分 0.375 仍兜住（比旧 0.67 被拒更好）；「经变」0.5 在内。
        // 回退条件：声母/韵母混淆样本又开始漏 → 0.55→0.6 微调
        private const val PINYIN_FUZZY_THRESHOLD = 0.55

        /**
         * 在 text 中寻找与 target 拼音最接近的字符窗口（v0.41.0 替换功能用）：
         * 先精确 indexOf；否则按 target 字数滑窗，取拼音编辑距离最小且达标的窗口——
         * 治「屏幕上是『不错』、用户照读却被听成『不措』」的听岔（拼音同即命中）。
         * 返回命中的字符区间（含头不含尾）；找不到返回 null。
         */
        fun findFuzzyRange(text: String, target: String): IntRange? {
            if (target.isEmpty()) return null
            val exact = text.indexOf(target)
            if (exact >= 0) return IntRange(exact, exact + target.length - 1)
            if (text.isEmpty()) return null
            val targetPy = pinyinOf(target)
            if (targetPy.split(" ").count { it.isNotEmpty() } == 0) return null
            var best: IntRange? = null
            var bestDist = Double.MAX_VALUE
            for (start in 0..text.length - target.length) {
                val window = text.substring(start, start + target.length)
                val d = pinyinFuzzyDist(pinyinOf(window), targetPy)
                if (d >= 0 && d < bestDist) {
                    bestDist = d
                    best = IntRange(start, start + target.length - 1)
                    if (d == 0.0) break   // 同音即最优，无需继续滑
                }
            }
            return best
        }

        /** 拼音音节级编辑距离（v0.57.9 声母韵母拆分计分）：返回距离（不匹配返回 -1），距离越小越接近。
         *  音节代价格局：全同=0 / 只差声母或只差韵母=0.5 / 全差=1——「经变」(jing bian) 对
         *  「轻点」(qing dian)：jing/qing 声母差 j≠q 韵母同 ing=0.5，bian/dian 声母差 b≠d 韵母同
         *  ian=0.5，合计 1.0 达标（旧整音节计分 2/2=1.0 被拒，2026-09-22 用户实测「说轻点多次没用」）。
         *  声母混淆（q/j、d/b、ch/n…）与韵母混淆（an/ang、in/ing…）是 ASR 两大高发家族，一次覆盖 */
        private fun pinyinFuzzyDist(a: String, b: String): Double {
            val aa = a.split(" ").filter { it.isNotEmpty() }
            val bb = b.split(" ").filter { it.isNotEmpty() }
            if (aa.isEmpty() || bb.isEmpty()) return -1.0
            val dist = levenshteinArr(aa, bb)
            val maxLen = maxOf(aa.size, bb.size)
            return if (dist <= PINYIN_FUZZY_MAX_DIST && dist / maxLen <= PINYIN_FUZZY_THRESHOLD) dist else -1.0
        }

        private val INITIALS = listOf(
            "zh", "ch", "sh",  // 双字母声母先匹配，防 z/h 被拆
            "b", "p", "m", "f", "d", "t", "n", "l", "g", "k", "h", "j", "q", "x",
            "r", "z", "c", "s", "y", "w"
        )

        /** 拆单音节为 (声母, 韵母)；无合法声母（a/ai/an 等零声母音节）时声母为空串。
         *  internal（v0.57.10）：DictationTriggers 听写触发容差共用同一声韵口径，勿各写一套 */
        internal fun splitInitial(s: String): Pair<String, String> {
            for (ini in INITIALS) {
                if (s.startsWith(ini)) return ini to s.substring(ini.length)
            }
            return "" to s
        }

        /** 音节相似代价：全同 0 / 只差声母或只差韵母 0.5 / 全差 1（internal 供听写触发共用） */
        internal fun syllableCost(x: String, y: String): Double {
            if (x == y) return 0.0
            val (ix, vx) = splitInitial(x)
            val (iy, vy) = splitInitial(y)
            val sameIni = ix == iy
            val sameFin = vx == vy
            return when {
                sameIni || sameFin -> 0.5
                else -> 1.0
            }
        }

        private fun levenshteinArr(a: List<String>, b: List<String>): Double {
            if (a.isEmpty()) return b.size.toDouble()
            if (b.isEmpty()) return a.size.toDouble()
            val dp = Array(a.size + 1) { DoubleArray(b.size + 1) }
            for (i in 0..a.size) dp[i][0] = i.toDouble()
            for (j in 0..b.size) dp[0][j] = j.toDouble()
            for (i in 1..a.size) {
                for (j in 1..b.size) {
                    val cost = syllableCost(a[i - 1], b[j - 1])
                    dp[i][j] = minOf(dp[i - 1][j] + 1.0, dp[i - 1][j - 1] + cost)
                }
            }
            return dp[a.size][b.size]
        }

        /**
         * 从 commands.json 的文本内容构建匹配器；解析失败返回空匹配器（匹配不到任何命令）。
         * customBindings = (自定义说法, 目标动作)：v0.39.0 用户语音绑定的外挂别名——
         * 复用目标命令的 id/action/group 生成条目并**前插**（匹配同分时自定义优先=用户显式意图）；
         * 目标动作不存在则跳过（UI 只列既有动作，正常不会发生）。
         */
        fun fromJson(
            json: String,
            customBindings: List<Pair<String, String>> = emptyList(),
        ): CommandMatcher {
            val entries = mutableListOf<Entry>()
            return try {
                val root = JSONObject(json)
                val groups = root.getJSONArray("groups")
                for (gi in 0 until groups.length()) {
                    val g = groups.getJSONObject(gi)
                    val gid = g.getString("id")
                    val cmds = g.getJSONArray("commands")
                    for (ci in 0 until cmds.length()) {
                        val c = cmds.getJSONObject(ci)
                        val id = c.getString("id")
                        val action = c.getString("action")
                        entries.add(Entry(id, action, gid, c.getString("command"), pinyinOf(c.getString("command"))))
                        val aliases = c.optJSONArray("aliases")
                        if (aliases != null) {
                            for (ai in 0 until aliases.length()) {
                                val w = aliases.getString(ai)
                                entries.add(Entry(id, action, gid, w, pinyinOf(w)))
                            }
                        }
                    }
                }
                val customs = mutableSetOf<String>()
                for ((phrase, action) in customBindings) {
                    val base = entries.firstOrNull { it.action == action }
                    if (base != null) {
                        entries.add(0, Entry(base.id, base.action, base.group, phrase, pinyinOf(phrase)))
                        customs.add(phrase)
                    } else if (action.startsWith("tap_number_") || action.startsWith("grid_tap_")) {
                        // 数字绑定（v0.50.0）：commands.json 无本体可搭车，自成条目前插（自定义优先）。
                        // action 合法性由 CustomBindings.isValidAction 在存储层把关，此处信任存储
                        entries.add(0, Entry("custom_$action", action, "custom", phrase, pinyinOf(phrase)))
                        customs.add(phrase)
                    }
                    // 动作既不存在也非数字 → 跳过（UI 只列可绑动作，正常不会发生）
                }
                CommandMatcher(entries, customs)
            } catch (e: Exception) {
                CommandMatcher(emptyList())
            }
        }
    }
}
