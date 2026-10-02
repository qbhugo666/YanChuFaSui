package com.voicecontrol.app

/**
 * 识别后路由的纯函数层（2026-09-28 建立，M1/M2「小步提取」；**2026-09-29 全指令收敛轮起
 * 成为生产唯一判定入口**）：把 VoiceService.handleRecognized 的候选判断（数字/网格/长按/
 * 重复/替换/文字点击/严格匹配/歧义）全部集中到 [planCommand]——生产路由与离线 runner
 * 调用同一函数，不存在只供测试使用的第二套路由。
 *
 * 已覆盖路径（planCommand）：替换、重复（显式+结构化恢复+无浮层宽松）、网格（长按格/
 * 点击格/缩放）、编号点击（含裸数字/宽松数字/点七六候选恢复）、长按编号/文字、严格匹配
 * （含同分歧义）、文字点击目标。
 * 仍在 VoiceService 的路径（executor/会话态，勿在此伪造）：听写触发/内容落笔、「继续」
 * 延期、「退出」红线、语气词、点击屏幕直通、说法录入、长按待命模式内部逻辑、
 * 执行失败后的模糊兜底次序。
 */
object CommandRouting {

    /**
     * 取「最后一个」正则匹配（而不是第一个）。
     *
     * 为什么：说话音量偏低时 VAD 会把多条命令合并成一段——实测「点击二十五点击十八」。
     * 取第一个会去执行 25（用户早就不想点的旧编号），而用户真正想要的是最后说的 18。
     * 第一性原理：一句话里出现多条同类命令时，最近说出的才是当前意图。
     */
    private fun lastMatch(regex: Regex, text: String): MatchResult? =
        regex.findAll(text).lastOrNull()

    /** 一句识别时路由可见的真实条件（M2：状态只提供事实，不提供猜测） */
    data class UtteranceContext(
        val gridShowing: Boolean,
        val labelsVisible: Boolean,
        val lastActionPresent: Boolean,
        val visibleLabelCount: Int? = null,
        val gridCellCount: Int = 12,
    )

    /** 路由结论（M2：Matched/NoMatch/Ambiguous/Ignore 不共用 null）
     *  2026-09-29 全指令收敛轮：DispatchCommand 直接携带完整 Match（生产 dispatchMatched
     *  无需重建对象）；Ambiguous 区分编号候选歧义（有 original/alternative 数值，生产给
     *  专属反馈）与词表同分歧义（静默留痕）；Repeat 标记 recovered（残句恢复来源，生产
     *  用于诊断行）。 */
    sealed class Decision {
        /** 命中词表命令（action 可能被二审修正过——修正策略见 AudioDecisionRouting） */
        data class DispatchCommand(val match: CommandMatcher.Match) : Decision() {
            val action: String get() = match.action
            val matchedWord: String get() = match.matchedWord
            val method: String get() = match.method
        }
        data class GridTapCell(val cell: Int, val restored: Boolean = false) : Decision()
        data class GridZoom(val cell: Int) : Decision()
        data class GridLongPress(val cell: Int) : Decision()
        data class TapNumber(val number: Int, val restored: Boolean = false) : Decision()
        data class LongPressNumber(val number: Int) : Decision()
        data class LongPressText(val target: String) : Decision()
        data class TapText(val target: String) : Decision()
        /** recovered=true 表示来自残句恢复（extractLooseRepeat），false=显式重复入口 */
        data class Repeat(val times: Int, val recovered: Boolean = false) : Decision()
        data class Replace(val find: String, val replacement: String) : Decision()

        /**
         * 明确歧义：不许再被文字点击/模糊兜底重新猜。
         * original/alternative 非空=编号候选歧义（「点七六」在已显示范围内 76 与 6 都合法，
         * 生产给「编号不明确」专属反馈）；否则=词表同分歧义（静默忽略+留痕）。
         */
        data class Ambiguous(
            val tiedWords: List<String>,
            val original: Int? = null,
            val alternative: Int? = null,
            /** 编号候选歧义判定时使用的可见范围（诊断用；词表歧义为 null） */
            val range: Int? = null,
        ) : Decision()
        /** 无候选（落此处的句子在产品里走模糊兜底，仍无命中则静默忽略并进听岔样本库） */
        data class NoMatch(val reason: String) : Decision()
    }

    // ---------- 重复（从 VoiceService 原样迁出，行为等价） ----------

    /** 「重复」听岔三字形（农夫/农富/农复）并列——历史样本实锤，勿删 */
    private val REPEAT_REGEX = Regex("""(?:重复|再来|农夫|农富|农复)\s*([0-9零一二两三四五六七八九十百]+)?\s*(?:次|遍)?""")

    /** 替换命令匹配（v0.41.0）：把X替换成Y / 把X换成Y / 把X改成Y（X、Y 各 1~10 字，非贪婪） */
    val REPLACE_REGEX = Regex("""^把(.{1,10}?)(?:替换成|换成|改成)(.{1,10})$""")

    fun extractRepeatCount(text: String): Int? {
        val m = lastMatch(REPEAT_REGEX, DigitParser.normalizeDigitHomophones(text)) ?: return null
        val numStr = m.groupValues[1]
        if (numStr.isBlank()) return 1
        // 2026-09-29 NEXT_SPEECH_STAGE：阿拉伯次数直取，不走 parseChineseNumber 的前缀重复折叠
        // ——该折叠为「二十二十六」这类汉字起音重复设计，会把「111」折成「1」（任务书点名审计，
        // 实测确认旧路径 重复111次→1）。汉字形态仍走折叠（历史行为，DigitParserTest 固化）。
        // 超过 10 次由执行层 MAX_REPEAT 拒绝并横条告知，不在这里截断。
        numStr.toIntOrNull()?.let { return it }
        return DigitParser.parseChineseNumber(numStr)
    }

    // 2026-09-29：截图+usage_log 03:12 的「富五次/丰富五次」在显示编号后失效。
    // 有明确量词的命令先按结构恢复，不再被编号/网格一票否决；不是把任意短句都开放。
    private val STRUCTURED_REPEAT = Regex("""^([\p{IsHan}]{1,2}?)\s*([0-9零一二两三四五六七八九十]+)\s*(?:次|遍)$""")
    private val REPEAT_PREFIX_SOUNDS = setOf("fu", "feng fu", "chong fu", "zhong fu", "nong fu")
    private val REPEAT_NUMBER = Regex("""(?:[0-9]{1,3}|[一二两三四五六七八九]|[一二两三四五六七八九]?十[一二三四五六七八九]?)""")

    internal fun extractStructuredRepeat(text: String): Int? {
        var t = text.trim().trim('，', '。', '！', '？', ',', '.', '!', '?')
        if (t.length !in 3..24) return null
        // 仅整段恰为同一句的两份时折叠，之后仍需整句语法通过；只调度一轮。
        if (t.length % 2 == 0 && t.take(t.length / 2) == t.drop(t.length / 2)) {
            t = t.take(t.length / 2)
        }
        // 2026-09-29：结构匹配用**原文**——整句 normalize 会把「夫尔茨」的尔→二，伪造出
        // 「夫二次」凭空多出次数（v0.57.23 该样本本意是重复一次）；同音归一只作用于数字组。
        val match = STRUCTURED_REPEAT.matchEntire(t) ?: return null
        if (pinyinOf(match.groupValues[1]) !in REPEAT_PREFIX_SOUNDS) return null
        val count = DigitParser.normalizeDigitHomophones(match.groupValues[2])
        // 次数不沿用编号的逐位拼接：「复五四次」不猜成五次，也不放大为54次。
        if (!REPEAT_NUMBER.matches(count)) return null
        // 阿拉伯次数不做数字前缀折叠：111 必须交给执行层按超限拒绝，不能折成1。
        return (count.toIntOrNull() ?: DigitParser.parseChineseNumber(count))?.takeIf { it > 0 }
    }

    /** 有明确结构的残缺重复先恢复；其余旧宽松规则仍只在无编号/网格时参与。 */
    fun extractLooseRepeat(text: String, labelsVisible: Boolean, gridShowing: Boolean, lastActionPresent: Boolean): Int? {
        if (!lastActionPresent) return null
        extractStructuredRepeat(text)?.let { return it }
        if (labelsVisible || gridShowing) return null
        val t = text.trim()
        if (t.length !in 2..4) return null
        val actionVerbs = listOf("点", "按", "滑", "摇", "打", "退", "长", "显", "网格", "编号", "音量", "锁", "通知", "控制", "继续")
        if (actionVerbs.any { t.contains(it) }) return null
        return boundedLooseRepeatCount(t)
    }

    // 2026-09-29 NEXT_SPEECH_STAGE 第二阶段（无浮层宽松重复收紧）：
    // 旧实现=「句中滤数字、无数字默认 1」——「我只是/又不是/这次/第一次」全触发成重复 1 次，
    // 「支付五次」触发成 5 次（闲话里的数字被当次数）。这与「从整句话随便抽几个数字」同病。
    // 新规则=结构化边界：剥量词后剩余必须是 ①纯数字（「两次」）②听岔前缀+数字（「负三次/
    // 过一次/试一次/再一次」）③≥2 字听岔前缀无量词数字（「夫尔茨」）。前缀不在集内一律拒绝。
    // 量词听岔（词/此/茨→次）只映射量词位——绝不整句 normalize（「是→十」会把「第一次」
    // 变成「第十次」凭空造出数字）。历史救回与新增拒绝逐条固化在 ParameterRecoveryTest。
    private val LOOSE_REPEAT_PREFIX_SOUNDS = setOf(
        "fu", "feng fu", "chong fu", "zhong fu", "nong fu",   // 复/丰富/重复/中富/农夫（含结构化集同源音）
        "guo", "bu", "shi", "zai", "fu er",                    // 过/不/试/再/夫尔——v0.57.8~23 历史救回字形
    )
    private val LOOSE_REPEAT_SHAPE = Regex("""^([\p{IsHan}]{0,2}?)([0-9零一二两三四五六七八九十]*)$""")

    internal fun boundedLooseRepeatCount(raw: String): Int? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        val lastChar = t.last()
        val isMeasure = lastChar == '次' || lastChar == '遍' || lastChar == '是' ||
            lastChar == '词' || lastChar == '此' || lastChar == '茨'   // 量词听岔（v0.57.23 茨；词/此同族）
        if (!isMeasure) return null
        val rest = t.dropLast(1)
        if (rest.isEmpty()) return null
        val m = LOOSE_REPEAT_SHAPE.matchEntire(rest) ?: return null
        val prefix = m.groupValues[1]
        val digits = m.groupValues[2]
        if (prefix.isEmpty()) {
            // 纯数字+量词（「两次/三次」）——历史救回形态；无数字的裸量词不成句
            if (digits.isEmpty()) return null
            return DigitParser.parseChineseNumber(digits)?.takeIf { it > 0 }
        }
        if (pinyinOf(prefix) !in LOOSE_REPEAT_PREFIX_SOUNDS) return null
        if (digits.isEmpty()) {
            // 无数字只容 ≥2 字的听岔前缀（「夫尔茨」）；单字（「不是」「过次」）歧义太大拒绝
            return if (prefix.length >= 2) 1 else null
        }
        return DigitParser.parseChineseNumber(digits)?.takeIf { it > 0 }
    }

    // ---------- 网格（从 VoiceService 原样迁出，行为等价） ----------

    private val GRID_TAP_REGEX = Regex("""(?:网格|格子)\s*([0-9零一二两三四五六七八九十百]+)|第\s*([0-9零一二两三四五六七八九十百]+)\s*格""")
    private val GRID_TAP_CELL_REGEX = Regex("""(?:点击|点)\s*第?\s*([0-9零一二两三四五六七八九十百]+)\s*格?(?!下)""")
    private val GRID_LONG_PRESS_REGEX = Regex("""(?:长按|按住)\s*第?\s*([0-9零一二两三四五六七八九十百]+)\s*格?""")
    private val GRID_BACK_KEYWORDS = listOf("退回", "回退", "取消缩放")

    fun extractGridTapCell(text: String): Int? {
        val m = lastMatch(GRID_TAP_CELL_REGEX, DigitParser.normalizeDigitHomophones(text)) ?: return null
        return DigitParser.parseChineseNumber(m.groupValues[1])
    }

    fun extractGridLongPressNumber(text: String): Int? {
        val m = lastMatch(GRID_LONG_PRESS_REGEX, DigitParser.normalizeDigitHomophones(text)) ?: return null
        return DigitParser.parseChineseNumber(m.groupValues[1])
    }

    fun extractGridNumber(text: String, loose: Boolean, matcher: CommandMatcher): Int? {
        // 撤销类命令（不含数字）优先走命令匹配，双保险排除
        if (GRID_BACK_KEYWORDS.any { text.contains(it) }) return null
        val norm = DigitParser.normalizeDigitHomophones(text)
        val m = lastMatch(GRID_TAP_REGEX, norm)
        if (m != null) {
            val numStr = m.groupValues[1].ifBlank { m.groupValues[2] }
            return DigitParser.parseChineseNumber(numStr)
        }
        return GridNumberRouting.extractLooseNumber(text, loose, matcher)
    }

    // ---------- 编号点击 / 长按 / 文字点击（从 VoiceService 原样迁出，行为等价） ----------

    private val TAP_LABEL_REGEX = Regex("""(?:点击|点|第)\s*[^0-9零一二两三四五六七八九十百下\s]{0,1}\s*([0-9零一二两三四五六七八九十百]+)\s*(?:个)?(?!下)""")
    private val BARE_NUMBER_REGEX = Regex("""^[0-9零一二两三四五六七八九十百]+$""")
    private val LONG_PRESS_LABEL_REGEX = Regex("""(?:长按|按住)\s*(?:第)?\s*([0-9零一二两三四五六七八九十百]+)\s*(?:个)?(?!下)""")
    private val LONG_PRESS_TEXT_REGEX = Regex("""^(?:长按|按住)\s*(.+)$""")
    // 2026-09-28 缺陷修复（M1 语料发现，单列预期变化）：备选顺序原为「打开|点|点击|…」，
    // 正则备选从左到右先试，「点击抖音」被「点」先吃掉、目标提取成「击抖音」——与 FEATURES
    // 既有承诺（「点击X」照常走文字点击）相悖，用户说「点击X」几乎必然落空后掉进模糊兜底。
    // 修复=「点击」排在「点」前。影响面：仅「点击X」形态的目标文字（X 不含数字时）；
    // 「点击N」（编号）、「点击第N格」（网格）、「点击屏幕」（整句直通）在更早的分支，零影响。
    private val TEXT_TAP_REGEX = Regex("""^(?:打开|点击|点|按|进入|启动)\s*(.+)$""")

    fun extractTapNumber(text: String): Int? {
        val m = lastMatch(TAP_LABEL_REGEX, DigitParser.normalizeDigitHomophones(text)) ?: return null
        return DigitParser.parseChineseNumber(m.groupValues[1])
    }

    data class TapNumberResolution(val number: Int, val original: Int, val ambiguous: Boolean = false) {
        val restored: Boolean get() = !ambiguous && number != original
    }

    /**
     * 2026-09-29 用户说「点击六」被转成「点七六」：七可能是击的错字，也可能真是76。
     * 只处理整句「点+七的同音字+一个中文数字」，按实际已显示的目标范围选唯一候选。
     * 无快照不纠正、两个目标都存在则拒绝猜。显式十位/阿拉伯数/点击前缀/裸数字均保持原义。
     * 这是执行前的候选判断；执行失败后不换目标重试。
     */
    fun resolveTapNumber(text: String, parsed: Int, availableCount: Int?): TapNumberResolution {
        val original = TapNumberResolution(parsed, parsed)
        if (availableCount == null || availableCount <= 0) return original
        val norm = DigitParser.normalizeDigitHomophones(text.trim())
        val match = Regex("""^点七([零一二两三四五六七八九])(?:个|格)?$""").matchEntire(norm) ?: return original
        val alternative = DigitParser.parseChineseNumber(match.groupValues[1]) ?: return original
        if (alternative !in 1..availableCount || alternative == parsed) return original
        return TapNumberResolution(alternative, parsed, ambiguous = parsed in 1..availableCount)
    }

    fun extractBareNumber(text: String): Int? {
        val t = DigitParser.normalizeDigitHomophones(text.trim())
        if (!BARE_NUMBER_REGEX.matches(t)) return null
        return DigitParser.parseChineseNumber(t)
    }

    /** 编号模式下的宽松数字音节集（「四是」=40 这类；只在编号显示时兜底） */
    internal val LOOSE_DIGIT_SYLLABLES = setOf(
        '零', '一', '衣', '依', '医', '已', '以', '椅', '意', '易', '移', '疑',
        '二', '两', '尔', '而', '耳', '儿', '饵',
        '三', '伞', '散', '山', '叁',
        '四', '是', '似', '寺', '事', '斯', '思', '撕', '死', '司', '丝', '私', '饲',
        '五', '午', '舞', '无', '伍', '吴', '乌', '误', '悟', '雾', '物', '勿',
        '六', '陆', '路', '留', '流', '刘', '榴', '溜',
        '七', '期', '妻', '气', '柒', '齐', '其', '奇', '骑', '棋', '旗', '起', '汽', '器',
        '八', '吧', '把', '爸', '扒', '疤', '拔', '靶', '坝', '罢', '捌',
        '九', '久', '酒', '就', '玖', '旧', '救', '揪', '究', '舅', '韭',
        '十', '时', '石', '拾', '实', '识', '食', '师', '狮', '失', '施', '什',
        '式', '试', '势', '市', '世', '室', '视', '适', '饰', '释',
        '百', '白', '摆', '拜', '点', '栋', '动'
    )

    fun extractBareNumberLoose(text: String): Int? {
        val t = text.trim()
        if (t.length !in 1..3) return null
        if (t.any { it !in LOOSE_DIGIT_SYLLABLES }) return null
        return DigitParser.parseChineseNumber(DigitParser.normalizeDigitHomophones(t))
    }

    fun extractLongPressNumber(text: String): Int? {
        val m = lastMatch(LONG_PRESS_LABEL_REGEX, DigitParser.normalizeDigitHomophones(text)) ?: return null
        return DigitParser.parseChineseNumber(m.groupValues[1])
    }

    fun extractLongPressText(text: String): String? {
        val m = LONG_PRESS_TEXT_REGEX.find(text) ?: return null
        val t = m.groupValues[1].trim()
        return t.ifEmpty { null }
    }

    fun extractTextToTap(text: String): String? {
        val trimmed = text.trim()
        val m = TEXT_TAP_REGEX.find(trimmed)
        if (m != null) {
            val t = m.groupValues[1].trim()
            return t.ifEmpty { null }
        }
        return if (trimmed.length in 2..8) trimmed else null
    }

    // ---------- 模式路由计划（2026-09-29 收尾项3：长按待命 + 听写内容，纯分类） ----------
    // 只做「文本+模式 → 意图分类」；执行（点格子/点编号/写输入框/还麦计时）留在 VoiceService。
    // 次序镜像 handleLongPressMode / handleRecognized 听写块，改一处必须同步另一处。

    /** 长按待命模式的下一句意图（两步式：说「长按」进入后） */
    sealed class StandbyDecision {
        /** 退出/取消 → 退出长按模式（**不结束会话**——待命里的退出是取消待命，非会话退出红线） */
        data object CancelStandby : StandbyDecision()
        /** 待命中说文字编辑命令（删除/清空/光标移动）→ 退出待命直接执行 */
        data class EditCommand(val word: String) : StandbyDecision()
        /** 中间/屏幕 → 长按屏幕正中间 */
        data object PressCenter : StandbyDecision()
        /** 数字 → 网格显示时长按格子，否则长按编号 */
        data class StandbyNumber(val number: Int, val gridCell: Boolean) : StandbyDecision()
        /** 其他：保持待命模式重新计时 */
        data object KeepWaiting : StandbyDecision()
    }

    fun planLongPressStandby(text: String, gridShowing: Boolean): StandbyDecision {
        // 「退出/取消」→ 退出长按模式（不结束整个会话）
        if (text.contains("退出") || text.contains("取消")) return StandbyDecision.CancelStandby
        val edit = text.trim().trim('，', '。', '！', '？', '…', ',', '.', '!', '?').trim()
        if (edit in TEXT_EDIT_WORDS) return StandbyDecision.EditCommand(edit)
        if (text.contains("中间") || text.contains("屏幕")) return StandbyDecision.PressCenter
        val num = extractBareNumber(text)
        if (num != null) return StandbyDecision.StandbyNumber(num, gridShowing)
        return StandbyDecision.KeepWaiting
    }

    /** 听写模式内容句的意图（「退出」红线在进听写块之前已处理，此处不再判） */
    sealed class DictationContentDecision {
        data object CancelDictation : DictationContentDecision()
        /** 内容句恰好是「输入」→ 几乎总是想继续听写，重新武装不打字面 */
        data object ContinueDictation : DictationContentDecision()
        /** 内容句恰好是文字编辑命令 → 按编辑执行不落笔（v0.56.25 连环坑） */
        data class EditInDictation(val word: String) : DictationContentDecision()
        /** 常规内容 → 原文写入输入框（个人词典纠错在执行层做） */
        data class InsertText(val text: String) : DictationContentDecision()
    }

    fun planDictationContent(text: String): DictationContentDecision {
        if (text.contains("取消")) return DictationContentDecision.CancelDictation
        val trimmed = text.trim().trim('，', '。', '！', '？', '…', ',', '.', '!', '?').trim()
        if (trimmed == "输入") return DictationContentDecision.ContinueDictation
        if (trimmed in TEXT_EDIT_WORDS) return DictationContentDecision.EditInDictation(trimmed)
        return DictationContentDecision.InsertText(text)
    }

    // ---------- 重复异步结果文案（2026-09-30 执行反馈分层轮，纯函数可 JVM 测） ----------
    // 写回口径：completed=N 次回放全部执行器成功（tapLabel 类含 A 线指纹观察、点位类=手势
    // 派发成功）——不宣称页面业务效果（指纹观察仅诊断，选择/开关类控件本就不改结构）；
    // 停止/中止/被替换如实写已完成次数。结果经 UsageLog.updateOutcome 按发起句 uid 写回
    // 原条目，迟到事件不 append 新条、不串到后来的句子。
    // ---------- 重复异步结果文案（2026-09-30 执行反馈分层轮，纯函数可 JVM 测） ----------
    // 写回口径：completed=**N 次手势全部派发成功**（无手势完成回调，2026-09-30 收尾轮按
    // 用户口径从「已完成」改为「已全部派发」——750ms 间隔只降低连续长按互相打断的风险，
    // 不是完成证明；tapLabel 类含 A 线指纹观察、点位类=dispatchGesture 接受派发）；
    // 停止/中止/替换/被新命令中止如实写已派发计数。结果经 UsageLog.updateOutcome 按发起句
    // uid 写回原条目，迟到事件不 append 新条、不串到后来的句子。
    object RepeatOutcomeText {
        fun started(times: Int) = "→ 重复 $times 次 · 已开始"
        fun completed(times: Int) = "→ 重复 $times 次 · 已全部派发"
        fun stoppedAt(done: Int, times: Int) = "→ 重复已停止（第 ${done + 1} 次动作失败，已派发 $done/$times）"
        fun cancelled(done: Int, times: Int, reason: String) = "→ 重复中止（$reason，已派发 $done/$times）"
        fun replaced(done: Int, times: Int) = "→ 重复被新请求替换（已派发 $done/$times）"
        /** 用户发出新的可执行命令：旧重复剩余次数作废（防旧点击在新页面继续执行） */
        fun supersededByCommand(done: Int, times: Int) = "→ 重复被新命令中止（已派发 $done/$times）"
        /** 「重复0次」当场拒绝（2026-09-30 边界修复：0 次不该启动任务，否则永久停在「已开始」） */
        fun rejectedZero() = "→ 重复次数需至少 1 次（0 次不执行）"
    }

    /**
     * 一次「重复 N 次」回放任务的状态机（2026-09-30 执行安全收尾轮）。
     * 生产由 VoiceService 驱动（runnable 每 tick 调 [step]，各取消点调 [cancel]）；
     * 状态转移、executed 计数、**呈现决策**（迟到事件不动横条）与记录文案全部集中在此——
     * RepeatTaskTest 驱动**生产用的同一状态机**覆盖真实调度路径（完成/失败/迟到/取消族），
     * 不再只手工调 UsageLog.updateOutcome。执行器/记录/横条经 [Host] 注入，无 Android 依赖。
     *
     * 迟到判定=host.currentUtteranceSeq() > startSeq（发起后用户又说过新句子）：
     * 迟到的失败事件只写回发起句记录，**不覆盖横条**（胶囊归新句）。
     */
    internal class RepeatTask(
        val uid: String,
        val times: Int,
        val startSeq: Int,
        private val host: Host,
    ) {
        interface Host {
            /** 派发一次回放动作；返回执行器结果（手势派发成功与否） */
            fun dispatchRepeatAction(): Boolean
            /** 按发起句 uid 写回结果（UsageLog.updateOutcome） */
            fun recordOutcome(uid: String, text: String)
            /** 横条提示（本状态机已按迟到与否决策是否调用） */
            fun showBar(text: String)
            /** 当前句序号（识别线程递增；迟到判定依据） */
            fun currentUtteranceSeq(): Int
            /** 会话是否仍在聆听（false=已退出/释放） */
            fun sessionActive(): Boolean
        }

        enum class CancelReason { NEW_COMMAND, SESSION_END, SERVICE_DESTROYED, REPLACED_BY_REPEAT }

        var executed = 0
            private set
        private var remaining = times
        /** 终态（2026-09-30 收尾漏洞①）：完成/失败/会话中止/取消后进入——不可再次取消，
         *  后续任何 cancel() 静默返回，已经确定的结果（已全部派发/已停止/中止口径）不被
         *  迟到的新命令/新重复/退出改写。 */
        private var terminal = false

        private fun late() = host.currentUtteranceSeq() > startSeq

        /** 取消未完任务：按发起句 uid 写回取消原因与已派发计数（各取消点的唯一出口）。
         *  已进入终态的任务不可再取消（结果已定）；取消后 [step] 拒绝再派发。
         *  VoiceService 侧另有句柄清除双保险（任务终结即置 null）。 */
        fun cancel(reason: CancelReason) {
            if (terminal) return
            terminal = true
            val text = when (reason) {
                CancelReason.NEW_COMMAND -> RepeatOutcomeText.supersededByCommand(executed, times)
                CancelReason.SESSION_END -> RepeatOutcomeText.cancelled(executed, times, "会话结束")
                CancelReason.SERVICE_DESTROYED -> RepeatOutcomeText.cancelled(executed, times, "服务销毁")
                CancelReason.REPLACED_BY_REPEAT -> RepeatOutcomeText.replaced(executed, times)
            }
            host.recordOutcome(uid, text)
        }

        /**
         * 执行一 tick（VoiceService 的 runnable 调用）。返回是否需要继续调度下一次；
         * 返回 false=任务终结（完成/失败/会话中止，进入终态，调用方应清除任务句柄）。
         * 完成=times 次手势全部派发成功——不是页面业务完成证明（无手势完成回调）。
         */
        fun step(): Boolean {
            if (terminal || remaining <= 0) return false
            if (!host.sessionActive()) {
                terminal = true
                host.recordOutcome(uid, RepeatOutcomeText.cancelled(executed, times, "会话结束"))
                return false
            }
            val ok = host.dispatchRepeatAction()
            if (!ok) {
                terminal = true
                host.recordOutcome(uid, RepeatOutcomeText.stoppedAt(executed, times))
                // 迟到的失败不覆盖新句的胶囊/主页提示（2026-09-30 收尾轮用户点名）
                if (!late()) host.showBar("⚠️ 重复已停止：上一动作未成功执行")
                return false
            }
            executed++
            remaining--
            if (remaining > 0) return true
            terminal = true
            host.recordOutcome(uid, RepeatOutcomeText.completed(times))
            return false
        }
    }

    /**
     * 重复回放的安全串行间隔（2026-09-30 冲突修复）：长按手势时长（650ms）> 默认间隔
     * （620ms）——旧逻辑下一次派发会在上一次长按**未完成**时打断它（「手势派发成功」被
     * 当成了「手势完成」）。长按类动作取 max(默认间隔, 长按时长+100ms 缓冲)；其余动作
     * 维持默认。不依赖手势回调、不自动补点/重试——纯时序保守。纯函数 JVM 可测。
     */
    fun repeatIntervalMs(actionIsLongPress: Boolean, defaultIntervalMs: Long, longPressDurationMs: Long): Long =
        if (actionIsLongPress) maxOf(defaultIntervalMs, longPressDurationMs + 100L) else defaultIntervalMs

    // ---------- 统一判断入口（生产+离线同一实现） ----------
    // 2026-09-29 全指令收敛轮起：VoiceService.handleRecognized 主链（替换→重复→网格→编号→
    // 长按→严格→文字点击）直接调用 planCommand 做全部候选/参数/模式/歧义判定，生产与离线
    // 回归（ProductionRoutingCorpusTest/ParameterRecoveryTest 等 126+ 用例）走同一次调用。
    // 执行器相关回退（tapText/长按文字失败后的模糊兜底、「继续」会话态、「退出」红线、
    // 听写/待命模式）仍在 VoiceService——planCommand 的 Decision 已把这些前置模式让位处理完。

    fun planCommand(text: String, ctx: UtteranceContext, matcher: CommandMatcher): Decision {
        // 次序严格镜像 handleRecognized：替换（^把…锚定）→ 重复 → 网格 → 编号 → 长按 → 严格 → 文字点击
        // 2026-09-29 全指令收敛轮起，handleRecognized 主链直接调用本函数做统一判断，
        // 此处即生产唯一判定入口（此前是镜像注释维系，现已是同一次调用）。
        REPLACE_REGEX.find(text)?.let { m ->
            return Decision.Replace(m.groupValues[1].replace(" ", ""), m.groupValues[2].replace(" ", ""))
        }

        val explicitRepeat = extractRepeatCount(text)
        val repeatTimes = explicitRepeat
            ?: extractLooseRepeat(text, ctx.labelsVisible, ctx.gridShowing, ctx.lastActionPresent)
        if (repeatTimes != null) return Decision.Repeat(repeatTimes, recovered = explicitRepeat == null)

        val gridLP = if (ctx.gridShowing) extractGridLongPressNumber(text) else null
        val gridTap = if (gridLP == null && ctx.gridShowing) extractGridTapCell(text) else null
        val gridNum = if (gridLP == null && gridTap == null) extractGridNumber(text, ctx.gridShowing, matcher) else null
        if (gridLP != null) return Decision.GridLongPress(gridLP)
        if (gridTap != null) {
            val resolved = resolveTapNumber(text, gridTap, ctx.gridCellCount)
            if (resolved.ambiguous) return Decision.Ambiguous(
                listOf("点击${resolved.original}", "点击${resolved.number}"), resolved.original, resolved.number, ctx.gridCellCount)
            return Decision.GridTapCell(resolved.number, resolved.restored)
        }
        if (gridNum != null) return Decision.GridZoom(gridNum)

        val tapNum = extractTapNumber(text)
            ?: if (ctx.labelsVisible) extractBareNumber(text) ?: extractBareNumberLoose(text) else null
        if (tapNum != null) {
            val count = if (ctx.labelsVisible) ctx.visibleLabelCount else null
            val resolved = resolveTapNumber(text, tapNum, count)
            if (resolved.ambiguous) return Decision.Ambiguous(
                listOf("点击${resolved.original}", "点击${resolved.number}"), resolved.original, resolved.number, count)
            return Decision.TapNumber(resolved.number, resolved.restored)
        }

        extractLongPressNumber(text)?.let { return Decision.LongPressNumber(it) }
        extractLongPressText(text)?.let { return Decision.LongPressText(it) }

        val strict = matcher.matchStrictDetailed(text)
        if (strict.ambiguous) return Decision.Ambiguous(strict.tiedWords)
        strict.match?.let { return Decision.DispatchCommand(it) }

        // 文字点击目标提取（产品路径：tapText 失败才继续模糊兜底——runner 只给首选目标）
        extractTextToTap(text)?.let { return Decision.TapText(it) }
        return Decision.NoMatch("no_candidate")
    }
}
