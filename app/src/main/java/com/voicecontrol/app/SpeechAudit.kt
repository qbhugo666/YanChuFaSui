package com.voicecontrol.app

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** 高频二审的证据账目。只保存既有转写、候选分数及路由结果，不留 PCM 或编码器特征。
 * 标注是用户确认的意图，不用于绑定、训练或执行。缺少原音时绝不推断“起音被吃”。 */
internal object SpeechAudit {
    const val SCHEMA = 1
    val NAVIGATION = NavigationIntentGuard.ACTIONS
    val FIXED_INTENTS = NAVIGATION + setOf("volume_up", "volume_down", "open_recents",
        "text_cursor_left", "text_cursor_right", "no_command")

    enum class ObservedEffect(val label: String) {
        CORRECT("执行正确"), WRONG("执行错了"), NONE("没有动作"), UNKNOWN("不确定")
    }

    fun validIntent(value: String): Boolean = value in FIXED_INTENTS ||
        value.removePrefix("tap_number:").toIntOrNull()?.let {
            value == "tap_number:$it" && it in 1..30
        } == true

    fun intentLabel(value: String): String? = when (value) {
        "swipe_up" -> "向上滑动"
        "swipe_down" -> "向下滑动"
        "swipe_left" -> "向左滑动"
        "swipe_right" -> "向右滑动"
        "go_back" -> "返回"
        "go_home" -> "桌面"
        "volume_up" -> "增加音量"
        "volume_down" -> "降低音量"
        "open_recents" -> "最近任务"
        "text_cursor_left" -> "光标左移"
        "text_cursor_right" -> "光标右移"
        "no_command" -> "这句不是命令"
        else -> if (validIntent(value)) "点击编号 ${value.substringAfter(':')}" else null
    }

    data class Capture(
        val source: String = "live_microphone_audio_not_retained",
        val audioDurationMs: Long? = null,
        val asrMs: Long? = null,
        val volumeRequested: Boolean = false,
        val m6Requested: Boolean = false,
        val reviewWaitMs: Long? = null,
        val queuedAtMs: Long? = null,
        val enhancedAtRecognition: Boolean? = null,
        val modeAtRecognition: String? = null,
    )

    data class Context(
        val mode: String, val enhanced: Boolean, val m6Enabled: Boolean,
        val generation: Int, val latestUid: String,
        val labelsVisible: Boolean, val gridShowing: Boolean,
        val labelCount: Int?, val requestSnapshot: String?, val liveSnapshot: String?,
    )

    data class Route(val action: String, val number: Int? = null) {
        fun key(): String = if (number == null) action else "$action:$number"
        fun json() = JSONObject().put("action", action).put("number", number ?: JSONObject.NULL)
        companion object {
            fun from(plan: CommandRouting.Decision): Route = when (plan) {
                is CommandRouting.Decision.DispatchCommand -> Route(plan.action)
                is CommandRouting.Decision.TapNumber -> Route("tap_number", plan.number)
                is CommandRouting.Decision.GridTapCell -> Route("tap_grid", plan.cell)
                is CommandRouting.Decision.GridLongPress -> Route("long_press_grid", plan.cell)
                is CommandRouting.Decision.GridZoom -> Route("zoom_grid", plan.cell)
                is CommandRouting.Decision.LongPressNumber -> Route("long_press_number", plan.number)
                is CommandRouting.Decision.LongPressText -> Route("long_press_text")
                is CommandRouting.Decision.TapText -> Route("tap_text")
                is CommandRouting.Decision.Repeat -> Route("repeat", plan.times)
                is CommandRouting.Decision.Replace -> Route("replace_text")
                is CommandRouting.Decision.Ambiguous -> Route("ambiguous")
                is CommandRouting.Decision.NoMatch -> Route("no_action")
            }
        }
    }

    /** 分数不是声学特征；非法概率以 null 留痕，不能因 NaN 使整条记录丢失。 */
    private fun head(values: List<Pair<String, Float>>?): JSONObject {
        val scores = values?.sortedByDescending { if (it.second.isFinite()) it.second else -1f }
            ?.take(5)?.map { (label, p) ->
                JSONObject().put("label", label.take(80))
                    .put("prob", p.takeIf { it.isFinite() && it in 0f..1f } ?: JSONObject.NULL)
            }.orEmpty()
        return JSONObject().put("available", values != null && values.isNotEmpty())
            .put("top", JSONArray(scores))
            .put("invalid_probability", values?.any { !it.second.isFinite() || it.second !in 0f..1f } == true)
    }

    fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** 只留目标集合指纹与数量，不额外留存屏幕文字、坐标集合或应用名。 */
    fun snapshotToken(snapshot: NumberReviewContext.Snapshot?): String? = snapshot?.let {
        digest("${it.windowId}\n${it.packageName}\n${it.targets.joinToString("\n")}".toByteArray(Charsets.UTF_8))
    }

    /** 本句主线程内收集事实。observe 不调用任何匹配器、模型或执行器。 */
    class Recorder(val uid: String, val context: Context, val capture: Capture,
                   decision: AudioDecisionOutcome?, val assetFingerprint: String?, nowMs: Long) {
        private val events = JSONArray()
        private var plan: Route? = null
        private var matched: JSONObject? = null
        private var final = Route("not_observed")
        private var status = "not_observed"
        private var rejection: String? = null
        private var textTapStatus: String? = null
        private var attempted = 0
        private val heads = JSONObject().put("m6", head(decision?.m6Top))
            .put("number", head(decision?.numTop)).put("number_segment", head(decision?.numSegmentTop))
            .put("number_pair", head(decision?.numPairTop)).put("cursor", head(decision?.cursorTop))
        private val timing = JSONObject().put("audio_ms", capture.audioDurationMs ?: JSONObject.NULL)
            .put("asr_ms", capture.asrMs ?: JSONObject.NULL)
            .put("review_wait_ms", capture.reviewWaitMs ?: JSONObject.NULL)
            .put("main_queue_ms", capture.queuedAtMs?.let { (nowMs - it).coerceAtLeast(0) } ?: JSONObject.NULL)
            .put("encoder_ms", decision?.encoderMs ?: JSONObject.NULL)
            .put("head_ms", decision?.headMs ?: JSONObject.NULL)
        private val volume = JSONObject().put("decision", decision?.decision ?: JSONObject.NULL)
            .put("prob", decision?.confidence?.takeIf { it.isFinite() && it in 0f..1f } ?: JSONObject.NULL)
        private val fallback = decision?.fallbackReason

        private fun event(stage: String, route: Route? = null, reason: String? = null) {
            if (events.length() >= 16) return
            events.put(JSONObject().put("stage", stage).put("route", route?.json() ?: JSONObject.NULL)
                .put("reason", reason ?: JSONObject.NULL))
        }

        fun planned(value: CommandRouting.Decision) {
            plan = Route.from(value)
            // 已进入主判断链、尚未派发。未布点的执行分支必须保留 unknown，不能当静默成功。
            if (value is CommandRouting.Decision.NoMatch || value is CommandRouting.Decision.TapText ||
                value is CommandRouting.Decision.Ambiguous) {
                final = Route("no_action"); status = "no_action"
            }
            event("plan", plan)
        }

        fun matched(value: CommandMatcher.Match, custom: Boolean) {
            matched = JSONObject().put("action", value.action).put("method", value.method)
                .put("custom", custom)
            event("match", Route(value.action))
        }

        fun textTap(value: SilentCommandRecovery.TextTapStatus) {
            textTapStatus = value.name
            event("text_target", reason = value.name)
            if (value == SilentCommandRecovery.TextTapStatus.DISPATCHED)
                dispatched(Route("tap_text"), true)
            else if (value == SilentCommandRecovery.TextTapStatus.FAILED)
                dispatched(Route("tap_text"), false, "text_dispatch_failed")
        }

        fun dispatched(route: Route, accepted: Boolean, reason: String? = null) {
            attempted++
            final = route; status = if (accepted) "accepted" else "failed"
            rejection = reason
            event(status, route, reason)
        }

        fun rejected(reason: String) {
            final = Route("no_action"); status = "rejected"; rejection = reason
            event("rejected", reason = reason)
        }

        fun ignored(reason: String) {
            final = Route("no_action"); status = "no_action"; rejection = reason
            event("ignored", reason = reason)
        }

        fun json(disposition: String?): JSONObject = JSONObject().put("schema", SCHEMA).put("uid", uid)
            .put("source", capture.source).put("audio_retained", false).put("features_retained", false)
            .put("asset_fingerprint", assetFingerprint ?: JSONObject.NULL)
            .put("context", JSONObject().put("mode", context.mode).put("enhanced", context.enhanced)
                .put("m6_enabled", context.m6Enabled).put("generation", context.generation)
                .put("latest_uid", context.latestUid).put("labels_visible", context.labelsVisible)
                .put("grid_showing", context.gridShowing).put("label_count", context.labelCount ?: JSONObject.NULL)
                .put("request_snapshot", context.requestSnapshot ?: JSONObject.NULL)
                .put("live_snapshot", context.liveSnapshot ?: JSONObject.NULL)
                .put("recognition_mode", capture.modeAtRecognition ?: JSONObject.NULL)
                .put("enhanced_at_recognition", capture.enhancedAtRecognition ?: JSONObject.NULL))
            .put("review", JSONObject().put("volume_requested", capture.volumeRequested)
                .put("m6_requested", capture.m6Requested).put("fallback", fallback ?: JSONObject.NULL)
                .put("volume", volume).put("heads", heads).put("disposition", disposition ?: JSONObject.NULL))
            .put("timing", timing).put("plan", plan?.json() ?: JSONObject.NULL)
            .put("match", matched ?: JSONObject.NULL).put("text_tap_status", textTapStatus ?: JSONObject.NULL)
            .put("outcome", JSONObject().put("route", final.json()).put("status", status)
                .put("attempts_observed", attempted).put("rejection", rejection ?: JSONObject.NULL)
                .put("business_effect", "unconfirmed"))
            .put("events", events)
    }

    /** 精确比对拟动作/编号。unknown 不当错也不当对；用户观察到的页面效果另列。 */
    fun assessed(entry: UsageLog.Entry): JSONObject {
        val out = JSONObject().put("uid", entry.uid).put("heard", entry.heard)
            .put("intent", entry.confirmedIntent.takeIf(::validIntent) ?: JSONObject.NULL)
            .put("observed_effect", entry.observedEffect.takeIf { v -> ObservedEffect.entries.any { it.name == v } }
                ?: JSONObject.NULL).put("audio_available", false)
        val audit = runCatching { JSONObject(entry.speechAudit) }.getOrNull()
        out.put("trace", audit ?: JSONObject.NULL)
        if (!validIntent(entry.confirmedIntent)) return out.put("assessment", "unlabeled")
        if (audit == null || audit.optString("uid") != entry.uid)
            return out.put("assessment", "missing_trace")
        val outcome = audit.optJSONObject("outcome") ?: return out.put("assessment", "missing_outcome")
        if (outcome.optString("status") == "not_observed")
            return out.put("assessment", "unobserved_path")
        val route = outcome.optJSONObject("route")
        val actual = if (outcome.optString("status") == "accepted" && route != null) {
            route.getString("action") + if (!route.isNull("number")) ":${route.getInt("number")}" else ""
        } else "no_command"
        val decisionCorrect = actual == entry.confirmedIntent
        out.put("dispatch_choice", actual).put("dispatch_choice_correct", decisionCorrect)
        val knownEffectConflict = entry.observedEffect == ObservedEffect.NONE.name && actual != "no_command" ||
            entry.observedEffect == ObservedEffect.WRONG.name && decisionCorrect && actual != "no_command" ||
            entry.observedEffect == ObservedEffect.CORRECT.name && !decisionCorrect
        // 只给有凭据的分类；没有原音时，ASR错误和采集丢音均保持 unknown。
        val reason = when {
            knownEffectConflict -> "effect_or_trace_conflict"
            decisionCorrect -> "dispatch_choice_matches_intent"
            outcome.optString("status") == "failed" -> "dispatch_failed"
            entry.confirmedIntent == "no_command" -> "non_command_dispatched"
            else -> "decision_mismatch_audio_cause_unknown"
        }
        return out.put("assessment", reason)
    }

    fun export(entries: List<UsageLog.Entry>): JSONObject = JSONObject().put("schema", SCHEMA)
        .put("scope", "user_marked_intent_and_dispatch_choice_not_population_accuracy")
        .put("audio_retained", false).put("features_retained", false)
        .put("total_records", entries.size).put("marked_records", entries.count { validIntent(it.confirmedIntent) })
        .put("records", JSONArray(entries.filter { it.speechAudit.isNotBlank() || validIntent(it.confirmedIntent) }
            .map(::assessed)))
}
