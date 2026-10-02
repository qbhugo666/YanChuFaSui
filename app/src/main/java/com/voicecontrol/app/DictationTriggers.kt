package com.voicecontrol.app

/**
 * 听写触发词与判定（v0.40.0 仅「输入/听写」整句精确匹配；v0.55.12 扩容）。
 *
 * 背景：用户实测「输入」难触发——二字皆软辅音（sh+r）极易被听糊，而原判定是
 * 整句精确等于，识别输出「书入/叔入」就完全不认。三招扩容：
 *   1. 更多好念的触发词（打字/我要打字…——爆破音 d/z 响亮清晰）；
 *   2. 短句拼音容错：整句 2 音节、与核心词（输入/听写/打字）至多差 1 个音节即命中
 *      （「书入」= shu ru 同音必中；「大字」近音也救回）；
 *   3. 抽为纯 Kotlin 顶层函数，可 JVM 单元测试固化。
 * 已知代价（可接受）：整句「属于」音近也会触发听写——无实害（12 秒无输入自动超时退出，
 * 且听写落笔需要输入框在场，无框只提示不静默）。
 */

/** 听写触发词（整句精确命中；长短语给足声学信号更稳，短词保快） */
val DICTATION_TRIGGERS = listOf(
    "输入", "听写", "打字",
    "我要输入", "我要打字", "开始输入", "开始打字",
    "语音输入", "帮我输入", "帮我打字",
)

/** 核心短触发词：短句拼音容错以此为基准 */
private val CORE_TRIGGERS = listOf("输入", "听写", "打字")

private fun syllablesOf(s: String): List<String> =
    pinyinOf(s).split(' ').filter { it.isNotEmpty() }

/** 整句音节数与触发词相同、差异在声韵口径内（v0.57.11 二次收紧）。
 *  v0.57.10 首次收紧后用户即刻实锤回归：「删除」(shan chu) 对「输入」(shu ru) 每个音节都
 *  只差一半（shan/shu 声母同韵母差=0.5，chu/ru 韵母同声母差=0.5），累计 1.0 恰好踩在
 *  放行线（>1.0 才拒）上——说「删除」反进听写。二次收紧：累计只容 **0.5（一个半差音节）**——
 *  同音（书入/大字=0）与单半差（煜入/属于=0.5，软辅音听岔保护）保留，「删除」(1.0) 拒 */
private fun nearSyllables(text: String, triggerSyllables: List<String>): Boolean {
    val a = syllablesOf(text)
    if (a.size != triggerSyllables.size) return false
    var diff = 0.0
    for (i in a.indices) {
        val cost = CommandMatcher.syllableCost(a[i], triggerSyllables[i])
        if (cost >= 1.0) return false   // 声韵全差：不是触发词的听岔形态
        diff += cost
        if (diff > 0.5) return false   // 只容一个半差音节（删除=0.5+0.5 拒）
    }
    return true
}

/** 是否为听写触发句：在「继续」检查之后、一般命令匹配之前调用 */
fun isDictationTrigger(text: String): Boolean {
    // 剥首尾标点：SenseVoice 长句带标点输出，别让「输入，」功亏一篑
    val t = text.trim()
        .trim('，', '。', '！', '？', '…', ',', '.', '!', '?')
        .trim()
    if (t.isEmpty()) return false
    if (t in DICTATION_TRIGGERS) return true
    if (syllablesOf(t).size != 2) return false   // 容错只针对核心短词的 2 音节整句
    return CORE_TRIGGERS.any { nearSyllables(t, syllablesOf(it)) }
}

/**
 * 听写武装综合判定（2026-09-29 收尾项3，生产唯一入口）：触发词/容差命中 **且** 整句不是
 * 在册命令（v0.57.12 用户拍板「删除是正式命令，绝不能因近音被听写抢走」——matchExact 整词
 * 精确命中者永远按命令走）。纯函数，与 VoiceService 委托同一实现，JVM 可测。
 */
fun shouldArmDictation(text: String, matcher: CommandMatcher): Boolean =
    isDictationTrigger(text) && matcher.matchExact(text) == null

/** 听写内容句中可直接生效的文字编辑命令词（v0.56.25）：按编辑执行，不作为文字落笔。
 *  治连环坑：说「删除」被听成「输入」进了听写，再说「删除」又被打成本字。 */
val TEXT_EDIT_WORDS = setOf(
    "删除", "删掉", "删一个字", "退格", "回删", "删字", "往回删",
    "清空输入", "清空输入框", "清空",
    "光标左移", "光标向左", "左移光标",
    "光标右移", "光标向右", "右移光标",
)
