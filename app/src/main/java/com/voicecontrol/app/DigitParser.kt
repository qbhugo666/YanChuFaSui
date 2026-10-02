package com.voicecontrol.app

/**
 * 数字命令解析器（v0.55.11 从 VoiceService 抽出为纯 Kotlin，可 JVM 单元测试固化）。
 *
 * 同音表大扩容的背景：用户实测 1~10 数字识别准确度一般——中文数字单音节、同音字
 * 密集（四=斯/思/撕/死…，十=是/时/实/师…），是全词表误识别重灾区，而「点击编号 N」
 * 又是目标用户使用频率最高的命令。
 * 策略：本表只作用于数字命令解析环节（点编号/网格/长按/裸数字/重复 N 次），
 * 把 ASR 输出的同音/近音汉字纠正回数字汉字；听写原文与其他命令路径不经过这里。
 */
object DigitParser {

    /** 数字同音字：ASR 常把数字听成同音/近音汉字（8→「吧」、1→「衣」、4→「似」），解析前纠正回数字 */
    val digitHomophones = mapOf(
        // 8
        '吧' to '八', '扒' to '八', '疤' to '八', '爸' to '八', '拔' to '八',
        '靶' to '八', '坝' to '八', '罢' to '八', '捌' to '八',
        // 1
        '衣' to '一', '依' to '一', '伊' to '一', '医' to '一', '已' to '一',
        '以' to '一', '椅' to '一', '意' to '一', '易' to '一', '移' to '一',
        '疑' to '一', '翼' to '一', '艺' to '一',
        // 2
        '尔' to '二', '而' to '二', '耳' to '二', '儿' to '二', '饵' to '二',
        // 3
        '伞' to '三', '散' to '三', '山' to '三', '叁' to '三',
        // 4
        '似' to '四', '寺' to '四', '肆' to '四', '斯' to '四', '思' to '四',
        '撕' to '四', '死' to '四', '司' to '四', '丝' to '四', '私' to '四',
        '饲' to '四',
        // 5
        '午' to '五', '舞' to '五', '武' to '五', '伍' to '五', '无' to '五',
        '吴' to '五', '乌' to '五', '误' to '五', '悟' to '五', '雾' to '五',
        '物' to '五', '勿' to '五',
        // 6
        '陆' to '六', '留' to '六', '流' to '六', '刘' to '六', '榴' to '六',
        '溜' to '六',
        // 7
        '妻' to '七', '期' to '七', '柒' to '七', '齐' to '七', '其' to '七',
        '奇' to '七', '骑' to '七', '棋' to '七', '旗' to '七', '起' to '七',
        '气' to '七', '汽' to '七', '器' to '七',
        // 9
        '久' to '九', '酒' to '九', '玖' to '九', '就' to '九', '旧' to '九',
        '救' to '九', '揪' to '九', '究' to '九', '舅' to '九', '韭' to '九',
        // 10（shi 音节全家桶：是/事/市…在数字位只可能是想读「十」）
        '时' to '十', '石' to '十', '拾' to '十', '是' to '十', '实' to '十',
        '识' to '十', '食' to '十', '师' to '十', '狮' to '十', '失' to '十',
        '施' to '十', '什' to '十', '式' to '十', '试' to '十', '势' to '十',
        '事' to '十', '市' to '十', '世' to '十', '室' to '十', '视' to '十',
        '适' to '十', '饰' to '十', '释' to '十',
        // 0 / 百 / 重复量词
        '玲' to '零', '铃' to '零', '凌' to '零', '灵' to '零',
        '白' to '百', '摆' to '百', '拜' to '百',
        '词' to '次', '此' to '次', '茨' to '次',   // v0.53.1：「五词/五此」→「五次」（重复家族量词被听岔）；v0.57.23 补「茨」——用户实测「重复一次」被吞成「夫尔茨」，错别字版量词致宽松兜底失配
    )
    // v0.55.3 补缺：耳→二、斯/思/撕→四、留→六（半夜小声听岔高发）
    // v0.55.11 大扩容：一到十同音/近音全量覆盖（用户实测数字准确度一般）

    /** 把数字同音字替换回数字汉字（数字命令解析前调用） */
    fun normalizeDigitHomophones(s: String): String =
        s.map { digitHomophones[it] ?: it }.joinToString("")

    /**
     * 数字前缀重复折叠（2026-09-22）：ASR 起音重复会把「二十六」输出成「二十二十六」
     * （A+A+rest 形态，用户使用记录实锤：说 26 →「二十二十六」，说 29 同理——识别引擎侧
     * 现象，与发音无关）。不折叠的话含「十」串会被静默截成 22（点错编号），逐位串则超界
     * 未触发。开头重复段折掉再解析：「二十二十六」→「二十六」、「一六一六」→「一六」；
     * 正常 0~99 中文数无此形态，零误伤。
     */
    internal fun collapseLeadingRepeat(s: String): String {
        // 两位相同数字是有效编号（11、22……），不能当成 ASR 重复前缀折掉。
        // 例如 11 旧逻辑会变成 1；「十十」仍由下面的中文数解析器按 10 处理。
        if (s.length <= 2) return s
        var t = s
        while (t.length >= 2) {
            val half = t.length / 2
            var folded = false
            for (i in 1..half) {
                if (t.substring(0, i) == t.substring(i, 2 * i)) {
                    t = t.substring(i); folded = true; break
                }
            }
            if (!folded) return t
        }
        return t
    }

    /**
     * 宽松重复次数提取（v0.57.8）：剥掉尾部 次/遍/是 → 句中滤出数字字符 → 解析；提不到数字默认 1 次。
     * ⚠️ 2026-09-29 起**生产路由不再调用本函数**（旧「无数字默认1/句中滤数字」把「我只是/支付五次」
     * 等闲话触发成重复）——改用 CommandRouting.boundedLooseRepeatCount（结构化边界版）。
     * 保留本函数作为原始提取器与 DigitParserTest 历史锚点，新代码勿再引用。
     */
    fun looseRepeatCount(s: String): Int {
        var t = s.trim()
        if (t.isEmpty()) return 1
        val last = t.last()
        if (last == '次' || last == '遍' || last == '是') t = t.dropLast(1)
        val digits = t.filter { it in DIGIT_CHARS }
        if (digits.isEmpty()) return 1
        return parseChineseNumber(digits) ?: 1
    }

    private val DIGIT_CHARS = "零一二两三四五六七八九十百0123456789".toSet()

    /** 中文/阿拉伯数字 → Int（支持 0~99） */
    fun parseChineseNumber(s: String): Int? {
        val t = collapseLeadingRepeat(s.trim())
        t.toIntOrNull()?.let { return it }
        if (t.isEmpty()) return null
        val digits = mapOf(
            '零' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
            '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9
        )
        if (t == "十") return 10
        if (t.length == 1) return digits[t[0]]
        if (t.contains('十')) {
            val parts = t.split('十')
            val tens = if (parts[0].isEmpty()) 1 else (digits[parts[0][0]] ?: return null)
            val ones = if (parts.size > 1 && parts[1].isNotEmpty()) (digits[parts[1][0]] ?: return null) else 0
            return tens * 10 + ones
        }
        // 逐位读法兑底：「一四」=14、「一三」=13（ASR 常输出「一四」而非「十四」）
        var result = 0
        for (c in t) {
            val d = digits[c] ?: return null
            result = result * 10 + d
        }
        return result
    }
}
