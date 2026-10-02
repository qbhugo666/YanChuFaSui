package com.voicecontrol.app

/** 2026-10-01：编号已显示时恢复完整“数字+号”语义；先保留合法文字点击。
 * 仅纠正标准“显示编号”的模糊误路由或无匹配，不采用未过验收的声音救回候选。
 * 生产与离线共用此入口，现有数字/4与10改号仍经原派发器，失败不补点。
 */
internal class NumberSuffixRecovery(private val dispatcher: NumberTapDispatcher = NumberTapDispatcher()) {
    data class Result(val textNumber: Int, val dispatch: NumberTapDispatcher.Result,
                      val replacedShowLabels: Boolean)
    private var lastConsumedUid: String? = null

    /** null=不参与；非null=本句已处理（包括失败/重复），调用方不得再走模糊派发。 */
    fun attempt(plan: CommandRouting.Decision, text: String,
                tapStatus: SilentCommandRecovery.TextTapStatus,
                fuzzy: CommandMatcher.StrictOutcome, matcher: CommandMatcher,
                labelsVisible: Boolean, gridShowing: Boolean,
                request: NumberReviewContext.Request?, live: NumberReviewContext.Live,
                audio: List<Pair<String, Float>>?, pairAudio: List<Pair<String, Float>>?,
                confirmation: List<Pair<String, Float>>? = null,
                tap: (Int) -> Boolean): Result? {
        if (!labelsVisible || gridShowing || NumberReviewContext.rejection(request, live) != null) return null
        val number = parseNumber(text) ?: return null
        if (number !in 1..live.snapshot!!.targets.size) return null
        if (live.uid == lastConsumedUid) return Result(number,
            NumberTapDispatcher.Result(number, null, "already_handled", false, false), false)
        if (plan !is CommandRouting.Decision.TapText ||
            tapStatus != SilentCommandRecovery.TextTapStatus.NOT_FOUND || fuzzy.ambiguous) return null
        val match = fuzzy.match
        if (match != null && (match.action != "show_labels" || match.method != "pinyin_fuzzy" ||
                matcher.isCustomMatch(match))) return null
        // 派发前消费uid；失败也是已处理，不得落回显示编号，更不能再点第二次。
        lastConsumedUid = live.uid
        val result = dispatcher.dispatch(number, text, audio, request, live,
            pairAudio = pairAudio, confirmation = confirmation, tap = tap)
        return Result(number, result, match != null)
    }

    companion object {
        private val canonicalNumbers: Map<String, Int> = buildMap {
            val digits = "零一二三四五六七八九"
            for (number in 1..30) {
                put(number.toString(), number)
                val chinese = if (number < 10) digits[number].toString() else
                    (if (number >= 20) digits[number / 10].toString() else "") + "十" +
                        (if (number % 10 != 0) digits[number % 10].toString() else "")
                put(chinese, number)
            }
            put("两", 2)
        }

        /** 整句规范结构，不做同音替换、数字过滤、末位裁剪或多句截取。 */
        internal fun parseNumber(text: String): Int? {
            val t = text.trim()
            if (t.length !in 2..4 || !t.endsWith('号')) return null
            return canonicalNumbers[t.dropLast(1)]
        }
    }
}
