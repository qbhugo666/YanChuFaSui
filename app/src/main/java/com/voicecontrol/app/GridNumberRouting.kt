package com.voicecontrol.app

/** 网格宽松数字分流：在普通命令匹配之前，正式命令不应被句中汉字数字抢走。 */
internal object GridNumberRouting {
    private val looseNumber = Regex("""([0-9]+|[零一二两三四五六七八九十百]+)""")

    fun extractLooseNumber(text: String, gridShowing: Boolean, matcher: CommandMatcher): Int? {
        if (!gridShowing || matcher.matchStrict(text) != null) return null
        val normalized = DigitParser.normalizeDigitHomophones(text)
        val last = looseNumber.findAll(normalized).lastOrNull() ?: return null
        return DigitParser.parseChineseNumber(last.groupValues[1])
    }
}
