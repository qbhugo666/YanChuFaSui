package com.voicecontrol.app

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.os.SystemClock

/**
 * 2026-09-28：二审运行于独立进程，隔离 ORT 1.20 与主进程 sherpa 所需的 ORT 1.27.1。
 * 仅同包可调用；一次音频段最多 4 秒，Binder 传输量低于 1MB。
 */
class AudioDecisionProvider : ContentProvider() {
    companion object {
        val URI: Uri = Uri.parse("content://com.voicecontrol.app.audio_decision")
        const val INIT = "init"
        const val DECIDE = "decide"
        const val SHUTDOWN = "shutdown"
        const val PCM = "pcm"
        const val RESULT = "decision"
        const val READY = "ready"
        const val SESSION = "session"
        const val CONFIDENCE = "confidence"
        const val LATENCY_MS = "latency_ms"
        const val FALLBACK_REASON = "fallback_reason"
        const val ENCODER_MS = "encoder_ms"
        const val HEAD_MS = "head_ms"
        const val STATS = "stats"
        const val M6_SHADOW = "m6_shadow"          // INIT 时请求加载 m6 全类头（D 阶段只算提案）
        const val M6_TOP = "m6_top"                // DECIDE 返回：label:prob,label:prob,label:prob
        const val NUM_TOP = "nn_num_top"           // nn 轮数字小头 top-k（改号纠正用）
        const val NUM_SEGMENT_TOP = "num_segment_top"
        const val NUM_SEGMENT_HEAD_US = "num_segment_head_us"
        const val NUM_PAIR_TOP = "num_pair_top"
        const val NUM_PAIR_HEAD_US = "num_pair_head_us"
        const val CURSOR_TOP = "cursor_top"
        const val CURSOR_HEAD_US = "cursor_head_us"
        private const val OWNER_TIMEOUT_MS = 30 * 60 * 1000L
    }

    private val sessionOwners = mutableSetOf<String>()
    private val ownerLastSeen = mutableMapOf<String, Long>()

    override fun onCreate(): Boolean = true

    @Synchronized override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = context ?: return Bundle()
        pruneStaleOwners()
        val session = extras?.getString(SESSION)?.takeIf { it.length in 8..80 }
        return when (method) {
            INIT -> {
                if (session == null) return Bundle().apply { putBoolean(READY, false) }
                AudioDecision.init(ctx, extras?.getBoolean(M6_SHADOW, false) ?: false)
                val ready = AudioDecision.isReady()
                if (ready) {
                    sessionOwners.add(session)
                    ownerLastSeen[session] = SystemClock.elapsedRealtime()
                }
                Bundle().apply { putBoolean(READY, ready) }
            }
            DECIDE -> {
                if (session == null || session !in sessionOwners) {
                    return Bundle().apply { putString(FALLBACK_REASON, "session_not_initialized") }
                }
                ownerLastSeen[session] = SystemClock.elapsedRealtime()
                val pcm = extras?.getFloatArray(PCM)
                val outcome = if (pcm != null && pcm.size in 400..64_000)
                    AudioDecision.decideDetailed(pcm)
                else AudioDecisionOutcome(null, null, 0L, "invalid_pcm")
                Bundle().apply {
                    putString(RESULT, outcome.decision)
                    outcome.confidence?.let { putFloat(CONFIDENCE, it) }
                    putLong(LATENCY_MS, outcome.latencyMs)
                    outcome.fallbackReason?.let { putString(FALLBACK_REASON, it) }
                    putLong(ENCODER_MS, outcome.encoderMs)
                    putLong(HEAD_MS, outcome.headMs)
                    // M6 shadow 提案（只算不改——消费方只记日志；未加载为 null）
                    outcome.m6Top?.let { top ->
                        putString(M6_TOP, top.joinToString(",") { "${it.first}:${it.second}" })
                    }
                    outcome.numTop?.let { top ->
                        putString(NUM_TOP, top.joinToString(",") { "${it.first}:${it.second}" })
                    }
                    outcome.numSegmentTop?.let { top ->
                        putString(NUM_SEGMENT_TOP,top.joinToString(",") { "${it.first}:${it.second}" })
                    }
                    putLong(NUM_SEGMENT_HEAD_US,outcome.numSegmentHeadUs)
                    outcome.numPairTop?.let { top ->
                        putString(NUM_PAIR_TOP,top.joinToString(",") { "${it.first}:${it.second}" })
                    }
                    putLong(NUM_PAIR_HEAD_US,outcome.numPairHeadUs)
                    outcome.cursorTop?.let { top ->
                        putString(CURSOR_TOP,top.joinToString(",") { "${it.first}:${it.second}" })
                    }
                    putLong(CURSOR_HEAD_US,outcome.cursorHeadUs)
                }
            }
            SHUTDOWN -> {
                if (session != null) {
                    sessionOwners.remove(session)
                    ownerLastSeen.remove(session)
                }
                val ready = sessionOwners.isNotEmpty()
                val stats = AudioDecision.dumpStats()
                if (!ready) AudioDecision.shutdown()
                Bundle().apply {
                    putBoolean(READY, ready)
                    putString(STATS, stats)
                }
            }
            else -> {
                Log.w("AudioDecision", "unknown provider method: $method")
                Bundle()
            }
        }
    }

    private fun pruneStaleOwners() {
        val now = SystemClock.elapsedRealtime()
        val expired = ownerLastSeen.filterValues { now - it > OWNER_TIMEOUT_MS }.keys
        expired.forEach { sessionOwners.remove(it); ownerLastSeen.remove(it) }
        if (expired.isNotEmpty() && sessionOwners.isEmpty()) AudioDecision.shutdown()
    }

    override fun onLowMemory() {
        synchronized(this) {
            sessionOwners.clear()
            ownerLastSeen.clear()
            AudioDecision.shutdown()
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
                        selectionArgs: Array<out String>?): Int = 0
}
