package com.voicecontrol.app

/** 声音救回不主动找输入框或获取焦点，只使用当前可见、已聚焦且光标位置明确的编辑节点。 */
internal object CursorExecutionGuard {
    data class Facts(val editable: Boolean, val visible: Boolean, val focused: Boolean,
                     val enabled: Boolean, val length: Int, val start: Int, val end: Int)

    fun usable(f: Facts): Boolean = f.editable && f.visible && f.focused && f.enabled &&
        f.length > 0 && f.start in 0..f.length && f.end == f.start

    fun next(f: Facts, direction: Int): Int? {
        if (!usable(f) || direction !in listOf(-1,1)) return null
        return (f.start + direction).coerceIn(0,f.length).takeIf { it != f.start }
    }

    // 分类头没有提供次数/位置，不能把带参数的残句擅自执行成平移一个字符；“一下”是语气。
    private val parameter = Regex("(?:[0-9０-９零〇一二两三四五六七八九十百千万]+\\s*(?:格|次|个|字|步|字符|秒))|(?:第[0-9０-９零〇一二两三四五六七八九十百千万]+)")
    fun parameterized(text: String): Boolean = parameter.containsMatchIn(text)
}
