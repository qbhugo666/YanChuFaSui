package com.voicecontrol.app

/**
 * 2026-10-02：识别线程曾先启动、后生成sessionTag，实机句ID出现“-u69”而当前是“g1-…-u69”。
 * 身份先发布给当前会话，再返回不可变的线程上下文；线程不再重新读取可能变化的全局代际/标签。
 * 不改变数字判决门槛或旧句/页面守卫。
 */
internal class RecognitionSessionIdentity private constructor(
    val generation: Int,
    val tag: String,
) {
    fun utteranceId(sequence: Int): String = "$tag-u$sequence"

    companion object {
        fun commit(
            generation: Int,
            nonce: String,
            publish: (RecognitionSessionIdentity) -> Unit,
        ): RecognitionSessionIdentity {
            val identity = RecognitionSessionIdentity(generation, "g$generation-$nonce")
            publish(identity)
            return identity
        }
    }
}
