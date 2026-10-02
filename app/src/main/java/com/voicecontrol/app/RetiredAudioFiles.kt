package com.voicecontrol.app

import android.content.Context
import android.util.Log
import java.io.File

/** 0.58.2：仅清理已停用二审复制到filesDir的原件/临时副本；不递归、不清用户数据。 */
internal object RetiredAudioFiles {
    private val names = listOf(
        "sensevoice_enc.onnx", "audio_decision_head.onnx", "m6_head_packed.onnx",
        "cursor_review_head.onnx", "nn_number_head.onnx", "nn_number_segments.onnx",
        "nn_number_pair4_10.onnx", "libonnxruntime_120.so", "audio_decision_asset_hashes.properties",
    ).flatMap { listOf(it, "$it.part") }

    data class Result(val bytes: Long, val removed: List<String>, val failed: List<String>)

    fun removeFrom(filesDir: File): Result {
        val root = filesDir.canonicalFile
        var bytes = 0L
        val removed = mutableListOf<String>()
        val failed = mutableListOf<String>()
        for (name in names) {
            val file = File(root, name)
            if (!file.exists()) continue
            // 精确文件白名单，软链接越出filesDir、同名目录都不删除。
            if (!file.isFile || file.canonicalFile.parentFile != root) {
                failed.add(name)
                continue
            }
            val length = file.length()
            if (file.delete()) {
                bytes += length
                removed.add(name)
            } else failed.add(name)
        }
        return Result(bytes, removed, failed)
    }

    fun clean(context: Context) {
        if (AudioReviewRequest.AVAILABLE) return
        runCatching { removeFrom(context.filesDir) }
            .onSuccess { result ->
                if (result.removed.isNotEmpty() || result.failed.isNotEmpty())
                    Log.i("VoiceControl", "已停用二审缓存清理：bytes=${result.bytes} removed=${result.removed} failed=${result.failed}")
            }
            .onFailure { Log.w("VoiceControl", "旧二审缓存清理失败，不影响普通识别", it) }
    }
}
