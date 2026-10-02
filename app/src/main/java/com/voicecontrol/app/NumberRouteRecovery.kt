package com.voicecontrol.app

/** 2026-10-01：编号静默候选，先在缓存回放验证；不改变原数字改号和静默出口。
 * 点击近音只允许一个声母或韵母差异，数字必须完整解析并与声音一致。
 * 不靠答案挑选模型，不忽略越界，不把文字点击失败当作补点理由。 */
internal class NumberRouteRecovery {
    data class Evidence(val number: Int, val prefixCost: Double)
    data class Heads(val original: List<Pair<String, Float>>?,
                     val segments: List<Pair<String, Float>>?,
                     val pair: List<Pair<String, Float>>?)
    data class Verdict(val number: Int?, val source: String?, val reason: String,
                       val evidence: Evidence? = null)
    data class Result(val number: Int, val source: String, val dispatched: Boolean)
    private var lastUid: String? = null

    fun evaluate(plan: CommandRouting.Decision, text: String,
                 tapStatus: SilentCommandRecovery.TextTapStatus,
                 fuzzy: CommandMatcher.StrictOutcome, request: NumberReviewContext.Request?,
                 live: NumberReviewContext.Live, heads: Heads): Verdict {
        fun reject(reason: String, evidence: Evidence? = null) = Verdict(null, null, reason, evidence)
        NumberReviewContext.rejection(request, live)?.let { return reject(it) }
        if (live.uid == lastUid) return reject("already_attempted")
        if (fuzzy.ambiguous || fuzzy.match != null) return reject("existing_fuzzy_or_ambiguous")
        val silent = when (plan) {
            is CommandRouting.Decision.TapText -> tapStatus == SilentCommandRecovery.TextTapStatus.NOT_FOUND
            is CommandRouting.Decision.NoMatch -> tapStatus == SilentCommandRecovery.TextTapStatus.NOT_ATTEMPTED
            else -> false
        }
        if (!silent) return reject("not_silent_source")
        val evidence = textEvidence(text) ?: return reject("text_structure_not_supported")
        if (evidence.number !in 1..live.snapshot!!.targets.size) return reject("text_number_outside_snapshot", evidence)

        val confident = mutableListOf<Pair<String, Int>>()
        var generalRejection = false
        for ((name, values, labels, threshold) in listOf(
            Model("segments", heads.segments, NUMERIC_LABELS, .99f),
            Model("original", heads.original, NUMERIC_LABELS, .99f),
            Model("pair4_10", heads.pair, NumberPairReviewPolicy.LABELS, NumberPairReviewPolicy.THRESHOLD))) {
            if (!validProbabilities(values, labels)) continue
            val ranked = values!!.sortedByDescending { it.second }
            val top = ranked.first()
            if (top.second < threshold) continue
            if (name == "pair4_10" && top.second - ranked[1].second < NumberPairReviewPolicy.MIN_MARGIN) continue
            val number = top.first.toIntOrNull()
            if (number == null) {
                // 三类头的other只说明不是4/10；33类头的明确拒识不能被另一个头越过。
                if (name != "pair4_10") generalRejection = true
            } else confident.add(name to number)
        }
        if (generalRejection) return reject("general_head_rejected", evidence)
        if (confident.isEmpty()) return reject("no_confident_head", evidence)
        if (confident.map { it.second }.distinct().size != 1) return reject("conflicting_heads", evidence)
        val selected = confident.first()
        if (selected.second != evidence.number) return reject("sound_disagrees_with_text_number", evidence)
        return Verdict(selected.second, selected.first, "candidate", evidence)
    }

    fun attempt(plan: CommandRouting.Decision, text: String,
                tapStatus: SilentCommandRecovery.TextTapStatus,
                fuzzy: CommandMatcher.StrictOutcome, request: NumberReviewContext.Request?,
                live: NumberReviewContext.Live, heads: Heads, tap: (Int) -> Boolean): Result? {
        val verdict = evaluate(plan, text, tapStatus, fuzzy, request, live, heads)
        val number = verdict.number ?: return null
        lastUid = live.uid // 派发前占用句子，失败也不重试。
        return Result(number, verdict.source!!, tap(number))
    }

    companion object {
        private data class Model(val name: String, val values: List<Pair<String, Float>>?,
                                 val labels: List<String>, val threshold: Float)
        val NUMERIC_LABELS = (1..30).map(Int::toString) + listOf("out_of_range", "non_number_click", "other")
        private val protectedText = Regex("不|没|别|勿|吗|呢|怎么|哪里|是否|重复|长按|网格|输入|听写|显示|退出|锁屏|取消|停止|[?？]")
        private val clickStarts = listOf("dian", "jian", "jin", "ji")

        internal fun textEvidence(text: String): Evidence? {
            val t = text.trim().filterNot(Char::isWhitespace).trimEnd('。', '，', '.', ',', '！', '!')
            if (t.length !in 2..12 || protectedText.containsMatchIn(t)) return null
            val sounds = pinyinOf(t).split(' ')
            var prefix = 1
            var cost = 0.0
            if (sounds.size >= 2) {
                val pairCost = clickStarts.minOf { first ->
                    CommandMatcher.syllableCost(sounds[0], first) +
                        minOf(CommandMatcher.syllableCost(sounds[1], "ji"),
                              CommandMatcher.syllableCost(sounds[1], "jie"))
                }
                // 第二字若已经是数字，不能作为近音动词剥掉，例如「几四十」不能变成10。
                val secondIsDigit = DigitParser.normalizeDigitHomophones(t[1].toString())
                    .all { it in "0123456789零一二两三四五六七八九十" }
                if (pairCost <= .5 && !secondIsDigit) { prefix = 2; cost = pairCost }
                else if (sounds[0] !in clickStarts) return null
            } else return null
            val raw = t.drop(prefix).removePrefix("编号").removePrefix("第")
                .removeSuffix("号").removeSuffix("个").removeSuffix("啊")
            // 「十吧」既可能是18，也可能是10+语气词；孤立「吧」仍可表示8。
            // 2026-10-01 缓存完整回放发现高分声音头也会误判，双重歧义不猜。
            if (raw.length > 1 && raw.endsWith('吧')) return null
            val digits = DigitParser.normalizeDigitHomophones(raw)
            // 百/千/万等明确大数不删掉；阿拉伯111也不能走重复折叠变成1。
            if (digits.isEmpty() || digits.any { it !in "0123456789零一二两三四五六七八九十" }) return null
            val number = digits.toIntOrNull() ?: DigitParser.parseChineseNumber(digits) ?: return null
            return number.takeIf { it in 1..30 }?.let { Evidence(it, cost) }
        }

        private fun validProbabilities(values: List<Pair<String, Float>>?, labels: List<String>): Boolean =
            values != null && values.size == labels.size && values.map { it.first }.toSet() == labels.toSet() &&
                values.all { it.second.isFinite() && it.second in 0f..1f } &&
                kotlin.math.abs(values.sumOf { it.second.toDouble() } - 1.0) <= .001
    }
}
