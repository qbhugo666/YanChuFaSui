package com.voicecontrol.app

/** 数字静默救回：先让完整旧链走完；绝不把派发失败当作再点一次的理由。 */
internal class NumberSilentRecovery {
    data class Result(val number: Int, val dispatched: Boolean)
    private var lastUid: String? = null
    private val nonCommand = Regex("不|没|别|勿|吗|呢|怎么|哪里|是否|重复|长按|网格|输入|听写|显示")
    private val clickStarts = setOf("dian", "jian", "jin", "ji")

    fun attempt(plan: CommandRouting.Decision, text: String, tapStatus: SilentCommandRecovery.TextTapStatus,
                fuzzy: CommandMatcher.StrictOutcome, request: NumberReviewContext.Request?,
                live: NumberReviewContext.Live, audio: List<Pair<String, Float>>?, tap: (Int) -> Boolean): Result? {
        if (NumberReviewContext.rejection(request, live) != null || live.uid == lastUid ||
            fuzzy.match != null || fuzzy.ambiguous || !textEvidence(text)) return null
        val eligible = when (plan) {
            is CommandRouting.Decision.NoMatch -> tapStatus == SilentCommandRecovery.TextTapStatus.NOT_ATTEMPTED
            is CommandRouting.Decision.TapText -> tapStatus == SilentCommandRecovery.TextTapStatus.NOT_FOUND
            else -> false
        }
        if (!eligible) return null
        val top = audio?.firstOrNull() ?: return null
        if (!top.second.isFinite() || top.second !in .99f..1f) return null
        val number = top.first.toIntOrNull()?.takeIf { it in 1..30 && it <= live.snapshot!!.targets.size } ?: return null
        lastUid = live.uid
        return Result(number, tap(number))
    }

    /** 不是个人错字绑定：起音保留点击动词音节，后段有完整数字结构才有资格。
     * 缺得只剩「哎」或空串，不凭声音猜；命令型页面文字先点，找不到才检查这里。 */
    internal fun textEvidence(text: String): Boolean {
        if (text.length !in 2..12 || nonCommand.containsMatchIn(text)) return false
        val sounds = pinyinOf(text).split(' ')
        if (sounds.firstOrNull() !in clickStarts) return false
        // 九(jiu)属于编号，不当作「点击」第二音节剥掉。
        val prefix = if (sounds.size > 1 && sounds[1] in setOf("ji", "jie")) 2 else 1
        val raw = text.drop(prefix).trim().removePrefix("编号").removePrefix("第")
            .removeSuffix("号").removeSuffix("个").removeSuffix("啊")
        val number = DigitParser.normalizeDigitHomophones(raw)
        return number.isNotEmpty() && number.all { it in "0123456789零一二两三四五六七八九十" }
    }
}
