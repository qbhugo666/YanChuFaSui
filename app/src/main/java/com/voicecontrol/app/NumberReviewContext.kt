package com.voicecontrol.app

/** 2026-10-01：数字改号提案只属于发起句与屏幕实际绘制的目标集合。 */
internal object NumberReviewContext {
    data class Snapshot(val windowId: Int, val packageName: String, val targets: List<String>)
    data class Request(val uid: String, val generation: Int, val snapshot: Snapshot?)
    data class Live(val uid: String, val generation: Int, val enabled: Boolean,
                    val active: Boolean, val normalMode: Boolean, val snapshot: Snapshot?)

    fun rejection(request: Request?, live: Live): String? = when {
        request == null -> "missing_request"
        !live.enabled -> "enhanced_off"
        !live.active -> "session_inactive"
        !live.normalMode -> "protected_mode"
        request.uid.isBlank() || request.uid != live.uid -> "stale_utterance"
        request.generation != live.generation -> "stale_session"
        request.snapshot == null || live.snapshot == null -> "snapshot_unavailable"
        request.snapshot.targets.isEmpty() -> "snapshot_empty"
        request.snapshot != live.snapshot -> "snapshot_changed"
        else -> null
    }
}
