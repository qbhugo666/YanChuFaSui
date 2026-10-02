package com.voicecontrol.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentProviderClient
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlin.concurrent.thread
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * 前台服务：会话期间占用麦克风，做【离线】语音识别。
 *
 * 安全设计（本项目最高优先级约束，不可妥协）：
 *  1. 识别出「退出」 → 立刻释放麦克风并停止；
 *  2. [WATCHDOG_MILLIS] 看门狗强制释放——时间制独挑大梁：到点前 20 秒预警，
 *     预警期说「继续」续期（仅预警期有效），不续期到点释放（2026-09-14 用户拍板
 *     删除静音自动释放——60s 安静就断太激进，交给看门狗时间制即可）；
 *  3. 锁屏立即释放，保证紧急通道随叫随到。
 */
private object NativeModelInitLock { val lock = Any() }

class VoiceService : Service() {

    companion object {
        private const val TAG = "VoiceControl"
        private const val AUDIO_DECISION_INIT_TIMEOUT_MS = 10_000L
        private const val AUDIO_DECISION_RETRY_BACKOFF_MS = 60_000L
        private val AUDIO_DECISION_CONNECT_LOCK = Any()
        @Volatile private var audioDecisionBlockedUntil = 0L
        const val CHANNEL_ID = "voice_session"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.voicecontrol.app.action.STOP"
        // 主页状态卡「已完成」停留时长：给足视觉确认，再自动回聆听态
        // 测试注入（开发期专用）：不经过麦克风，直接重放「识别→匹配→执行」全链路
        const val ACTION_SIMULATE = "com.voicecontrol.app.action.SIMULATE"
        const val EXTRA_TEXT = "com.voicecontrol.app.extra.TEXT"

        // 飞行记录仪（v0.51.0）：会话出生在偏好里留档、正常结束销档——
        // 下次启动若档还在 = 上次会话没落地（进程被杀/崩溃，当时无法记录），事后追认
        private const val KEY_SESSION_ACTIVE_SINCE = "session_active_since"

        // 自定义说法录入（v0.39.0）：绑定页请求捕获下一句识别原文——复用正常会话链路
        // （前台服务/看门狗/静音释放全在），听到第一句自动还麦。绑定页轮询 lastCaptured 取结果
        @Volatile
        var captureArmed: Boolean = false
        @Volatile
        var lastCaptured: String? = null

        // 听写武装标志（v0.40.0）：companion 存储——单发注入会 stopSelf 重启服务实例，
        // 实例字段会在两条注入之间丢状态（2026-09-12 实测踩坑）；截止时间=elapsedRealtime 毫秒
        @Volatile
        var dictationArmed: Boolean = false
        @Volatile
        var dictationDeadline: Long = 0L

        // 回声测试台（开发期专用）：用 USAGE_MEDIA 外放 assets/echo_cmd.wav
        // ——与抖音/视频 App 完全同一条音频通道，1:1 复刻「外放声音被麦克风拾取」场景。
        // 用途：修复前复现 bug（基线）、修复后验证回声是否真被消除。
        // v0.56.26：Release 构建由 DEBUG 门禁自动禁用，无需删码。
        //
        // 安全约束（2026-09-08 深夜噪音事故后强制）：绝不循环播放，播完自动停；
        // 并设硬性超时上限，即使 STOP 命令丢失也必定自动停，不可能无限念。
        const val ACTION_ECHO_TEST = "com.voicecontrol.app.action.ECHO_TEST"
        const val ACTION_ECHO_STOP = "com.voicecontrol.app.action.ECHO_STOP"
        private const val ECHO_TEST_FILE = "echo_cmd.wav"
        // 外放硬性超时上限（毫秒）：到点强制停，绝不依赖外部 STOP 命令
        private const val ECHO_TEST_MAX_MS = 12_000L

        // 离线 ASR 测试台（开发期专用，Release 由 DEBUG 门禁自动禁用）：把 assets 里的 wav 直接喂给识别器，
        // 不经过麦克风、不发出任何声音——用于静音诊断「点击十八→点击八」这类丢字问题。
        // 自建临时 recognizer/vad 实例（不复用会话中的），避开与识别线程的 native 并发。
        // 可传 EXTRA_SCORE 对比不同热词权重的准确率，一个 build 内完成 A/B。
        const val ACTION_ASR_TEST = "com.voicecontrol.app.action.ASR_TEST"
        const val EXTRA_WAV = "com.voicecontrol.app.extra.WAV"
        const val EXTRA_SCORE = "com.voicecontrol.app.extra.SCORE"

        // 输入框文本操作探针（开发期诊断，Release 由 DEBUG 门禁自动禁用）
        const val ACTION_TEXT_PROBE = "com.voicecontrol.app.action.TEXT_PROBE"
        const val ACTION_DECISION_PROBE = "com.voicecontrol.app.action.DECISION_PROBE"
        // M6 固定 PCM 一致性探针（2026-09-30 D 复核①）：只推理不派发动作——读 PC 基准
        // probe_fixed.json 中的 wav，跑与生产完全相同的 decideDetailed 链（真实 encoder
        // pooled→双头），结果写 files/m6_android_probe.json 供 PC compare。
        const val ACTION_M6_PCM_PROBE = "com.voicecontrol.app.action.M6_PCM_PROBE"

        // 崩溃触发后门（开发期诊断，Release 由 DEBUG 门禁自动禁用）
        const val ACTION_CRASH_TEST = "com.voicecontrol.app.action.CRASH_TEST"

        // 热词权重（contextual biasing）。保持 8.0：2026-09-08 曾用离线 ASR 测试台做 8.0 vs 2.0 A/B，
        // 两者对「点击十八/十三/二十五」识别结果完全一致——「权重过高导致丢字」假设被证伪，
        // 无证据不改；8.0 是实测能改善「抖婴→抖音」这类听岔的值。
        private const val HOTWORDS_SCORE = 8.0f

        /** 实时语音振幅（0~1）：识别循环每 100ms 按当前缓冲 RMS 更新，
         *  供顶部胶囊声波指示实时起伏（同一进程直接读共享字段） */
        @Volatile
        var liveAmplitude: Float = 0f
        const val SAMPLE_RATE = 16000

        // 看门狗：占用麦克风的基础时长（毫秒），到点强制释放
        // 2026-09-11 用户拍板：2 分钟 → 3 分钟（用着更从容，不必频繁喊「继续」）
        // 2026-09-14 用户拍板：3 分钟 → 5 分钟（每段更长，喊「继续」更少；硬顶 15 分钟不变）
        private const val WATCHDOG_MILLIS = 300_000L

        // 看门狗延期：每次「继续」延长的时长（毫秒）
        private const val EXTEND_MILLIS = 300_000L

        // 看门狗最多延期次数（基础 5 分钟 + 4 次 × 5 分钟 = 最多 25 分钟）
        // 2026-09-15 用户拍板：×2 → ×4（"四五二十"），总量 5+20=25 分钟
        private const val MAX_EXTENSIONS = 4

        // 胶囊文案统一由上面三个常量推导（2026-09-14 教训：常量改成 3 分钟、写死的「2 分钟」文案没跟上）
        private val EXTEND_MINUTES = EXTEND_MILLIS / 60_000L
        private val SESSION_MAX_MINUTES = (WATCHDOG_MILLIS + MAX_EXTENSIONS * EXTEND_MILLIS) / 60_000L

        // 休眠预警：看门狗到点前多久提示「即将休眠」（毫秒）
        private const val WARN_BEFORE_MILLIS = 20_000L

        // 静音自动释放已于 2026-09-14 用户拍板删除：会话时长完全由看门狗时间制管理

        // 退出命令词
        private const val CMD_EXIT = "退出"

        // 延期命令词（看门狗预警时说「继续」延长占用时长）
        private const val CMD_CONTINUE = "继续"

        // 纯语气词：ASR 常把口癖/残音识别成这些（如「返回」被听成「喂」）。
        // 商用体验原则（2026-09-08 用户定）：没识别对就保持安静继续聆听，不把误识别展示给用户。
        // 「点击」（2026-09-22 用户拍板加入）：说话慢被 VAD 断句截出的「点击」二字会被拼音模糊
        // 兜到「单击」(dian/dan 近音) 误触轻点——整句恰好「点击」时静默忽略；
        // 带宾语的「点击X」整句不同，不受影响，照常走文字点击
        private val NOISE_WORDS = setOf(
            "喂", "喂喂", "嗯", "嗯嗯", "呃", "啊", "啊啊", "哦", "噢",
            "哎", "唉", "呀", "哈", "哈哈", "嘿", "诶", "欸", "点击"
        )

        // 命令冷却：同一动作执行后多久内不重复执行（防误触发连击）。
        // v0.55.4 从 1.5s 压到 0.8s：2026-09-16 用户拍板（连发同命令翻页更快）；
        // 1.5s 是抖音外放事故定的铁闸，压短后外放误触发可能多执行几次——熔断（8s 6 次）仍在兜底。
        // 回退条件：外放场景误触发变多改回 1500L
        private const val COOLDOWN_MS = 800L

        // 带附加提示的动作（执行器会写 actionNote，识别层优先展示提示而非通用文案）
        private val NOTE_ACTIONS = setOf(
            "volume_up", "volume_down", "volume_mute",
            "nudge_up", "nudge_down", "nudge_left", "nudge_right",
            "zoom_in", "zoom_out",
            "text_cursor_left", "text_cursor_right", "text_delete", "text_clear",
            "take_screenshot",
        )

        // 熔断：时间窗与阈值——窗口内执行次数过多即判定误循环，释放麦克风
        private const val CIRCUIT_WINDOW_MS = 8000L
        private const val CIRCUIT_MAX_EXEC = 6

        // 重复执行：最大次数（超过拒绝，防止失控/崩溃）
        private const val MAX_REPEAT = 10

        // 重复执行：两次动作之间的间隔（滑动拖动 550ms + 缓冲，避免打断上一段拖动手势）
        private const val REPEAT_INTERVAL_MS = 620L

        // 长按待命模式：进入后多久没收到数字/「中间」就自动退出
        private const val LONG_PRESS_MODE_TIMEOUT_MS = 5000L

        // 数字词热词（十一～九十九）：2026-09-08 真人实测发现「点击十八→点击二十/点击八」丢字。
        // 实测规律：单独念的完整数字词（如「十六」）识别极稳，动词+数字粘连时丢「十」——
        // 把全部两位数整词加入热词表，让解码时倾向输出完整数字词，不做两步式（保留一步直给的快）。
        private val NUMBER_HOTWORDS = buildList {
            val digits = listOf("零", "一", "二", "三", "四", "五", "六", "七", "八", "九")
            add("十")
            for (n in 11..99) {
                if (n % 10 == 0) continue // 整十数口语不常用，减词表体积
                add(digits[n / 10] + "十" + digits[n % 10])
            }
        }

        // 重复家族热词（v0.53.1）：此前整个家族缺席——解码器无偏置时偏爱常见搭配，
        // 用户实测「重复五次/六次」被听成「重复一次」、「重复一次」本身也时灵时不灵。
        // 「一次」~「十次」「两次」与「重复 ×」一并覆盖，「再来一次」为口语变体
        private val REPEAT_HOTWORDS = buildList {
            val ci = listOf("一次", "两次", "三次", "四次", "五次", "六次", "七次", "八次", "九次", "十次")
            ci.forEach { add(it); add("重复$it") }
            add("再来一次")
        }

        // App 名热词（纯汉字）：让 ASR 优先识别成正确 App 名（否则「抖音」易被听成「面嗯」等）
        private val APP_HOTWORDS = listOf(
            "抖音", "微信", "支付宝", "淘宝", "京东", "微博", "小红书", "知乎",
            "哔哩哔哩", "百度", "美团", "快手", "钉钉", "飞书", "拼多多", "闲鱼",
            "今日头条", "夸克", "高德地图", "酷狗音乐", "爱奇艺", "优酷",
            "腾讯视频", "滴滴出行", "饿了么", "携程旅行", "豆瓣", "贴吧",
            "喜马拉雅", "网易云音乐", "西瓜视频", "番茄小说"
        )
    }

    private var audioRecord: AudioRecord? = null
    private var recognizer: OfflineRecognizer? = null
    private var vad: Vad? = null

    // 音频前处理效果器（回声消除/降噪/自动增益）：挂在 AudioRecord 的会话上，释放麦克风时一并释放
    private var aecEffect: AcousticEchoCanceler? = null
    private var nsEffect: NoiseSuppressor? = null
    private var agcEffect: AutomaticGainControl? = null

    @Volatile
    private var recording = false

    private var recordThread: Thread? = null
    private val handler = Handler(Looper.getMainLooper())

    // 命令冷却：记录每个动作最后一次执行时间（elapsedRealtime 毫秒）
    private val lastExecTime = mutableMapOf<String, Long>()

    // 熔断：记录最近若干次执行时间，窗口内次数过多则熔断
    private val execHistory = ArrayDeque<Long>()

    // 横条文字恢复的定时任务：预警期间恢复预警文案（否则预警会被反馈文字顶掉，用户看不到就断），平时恢复「正在聆听」
    private val barResetRunnable = Runnable {
        SessionState.phase = SessionState.Phase.LISTENING   // 主页状态卡回到聆听态
        VoiceControlService.updateBar(
            if (sleepWarned) warnBarText() else "🎤 正在聆听…"
        )
    }

    /** 休眠预警横条文案：带剩余「继续」次数——预算看得见（2026-09-15 用户误以为 25 分钟自动给满） */
    private fun warnBarText() =
        "😴 即将休眠，说「继续」延长（剩余 ${MAX_EXTENSIONS - extensionCount} 次）"

    // 命令匹配层（v0.4：识别结果 → 词表纠错）。v0.39.0 起带用户自定义说法（语音绑定）：
    // 按绑定文件时间戳缓存重建——绑定保存后下一次识别即生效，无需重启会话/进程
    private var cachedMatcher: CommandMatcher? = null
    private var matcherStamp = Long.MIN_VALUE

    private fun currentMatcher(): CommandMatcher {
        val stamp = CustomBindings.stamp(applicationContext)
        cachedMatcher?.let { if (stamp == matcherStamp) return it }
        val json = assets.open("commands.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val bindings = CustomBindings.all(applicationContext).map { it.phrase to it.action }
        val built = CommandMatcher.fromJson(json, bindings)
        Log.i(TAG, "匹配器已构建：标准词表 + 自定义说法 ${bindings.size} 条")
        cachedMatcher = built
        matcherStamp = stamp
        return built
    }

    // 上一次动作（供「重复」命令回放）
    private sealed class LastAction {
        data class Command(val action: String, val focusedCursorOnly: Boolean = false) : LastAction()
        data class TapLabel(val number: Int) : LastAction()
        data class TapPoint(val x: Float, val y: Float) : LastAction()
        /** v0.57.19：网格长按落点（「重复」回放同点位长按） */
        data class LongPressPoint(val x: Float, val y: Float) : LastAction()
    }
    private var lastAction: LastAction? = null
    // 重复任务句柄已升级为 RepeatRun（uid+times+executed，2026-09-30）——声明移至 repeatLastAction 旁

    // 长按待命模式（两步式：先「长按」进入，再报数字/「中间」，提升「动词+数字」识别率）
    private var longPressMode = false
    private val longPressModeRunnable = Runnable {
        exitLongPressMode()
        VoiceControlService.updateBar("🎤 正在聆听…")
    }

    // 听写模式（v0.40.0，小米式一次性短听写，用户拍板）：说「输入/听写」立即进入，
    // 下一句识别原文直接写入输入框，说完停顿（VAD 分句）即结束——不做「退出听写」往返。
    // 武装标志存 companion（服务实例可能在两条注入间被 stopSelf 重启）；实例 runnable 只负责到点恢复横条文案
    private var dictationMode
        get() = dictationArmed && SystemClock.elapsedRealtime() < dictationDeadline
        set(value) {
            dictationArmed = value
            if (value) dictationDeadline = SystemClock.elapsedRealtime() + 12_000L
        }
    private val dictationTimeoutRunnable = Runnable {
        if (!dictationMode) {
            VoiceControlService.updateBar("🎤 正在聆听…")
            SessionState.lastMatch = "→ 听写超时已退出"
        }
    }
    // 最近一次听写落笔时刻（v0.56.25）：「删除」→「输入」近音纠偏的时间窗基准
    private var lastInsertAt = 0L
    // v0.56.27：5 秒纠偏已移除（重复输入场景误伤，用户拍板）——字段保留待后续数据方案复用

    /** 未命中识别原文持久落盘（v0.56.26）：files/asr_misses.json 环形 200 条——
     *  积累真实听岔样本，按用户口音做数据驱动的定向纠错表 */
    private fun persistMiss(text: String) {
        try {
            val f = java.io.File(applicationContext.filesDir, "asr_misses.json")
            val arr = if (f.exists()) org.json.JSONArray(f.readText()) else org.json.JSONArray()
            arr.put(org.json.JSONObject().put("t", System.currentTimeMillis()).put("text", text))
            while (arr.length() > 200) arr.remove(0)
            f.writeText(arr.toString())
        } catch (_: Exception) {
            // 样本落盘失败不影响主流程
        }
    }

    // 「重复」回放（v0.39.1 治本）：不再维护白名单——任何派发成功的动作都记入 lastAction，
    // 「重复一次/N 次」回放的就是上一个动作本身，新增功能天然可重复、无需登记。
    // 历史教训：v0.4 白名单早于 v0.17 设备控制组，音量动作从未登记 →「增加音量后重复一次」失效。
    // 安全边界：exit 走红线不经派发（双保险见 dispatchCommand）；重复上限 10 次；音量受 80% 帽约束

    private val watchdogRunnable = Runnable {
        Log.i(TAG, "看门狗到点，强制释放")
        DiagnosticsHelper.log("看门狗到点，强制释放")
        releaseAndStop("看门狗强制释放")
    }
    // 静音自动释放 runnable 已删（2026-09-14 用户拍板）：安静不再提前断会话，看门狗时间制独挑大梁
    // 锁屏自动释放：黑屏（SCREEN_OFF）立即还麦，保证锁屏状态下随时能唤起小爱
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                Log.i(TAG, "锁屏，自动释放麦克风")
                releaseAndStop("锁屏自动释放")
            }
        }
    }
    // 休眠预警是否已显示：静音释放不得在预警出现前触发，否则用户永远等不到「即将休眠」提示
    @Volatile
    private var sleepWarned = false

    // 休眠预警：看门狗到点前提示「即将休眠，说「继续」延长（剩余 N 次）」
    private val warnRunnable = Runnable {
        sleepWarned = true
        Log.i(TAG, "WARN_SHOWN 休眠预警已显示")
        VoiceControlService.updateBar(warnBarText())
    }
    // 已延期次数（0 ~ MAX_EXTENSIONS）
    private var extensionCount = 0

    // 30 秒滚动窗口内的"近似退出被拦"时间戳（守卫升级阶梯用，见 dispatchMatched）
    private val fuzzyExitRejects = ArrayDeque<Long>()

    // 本次会话开始的 elapsedRealtime（0 = 无真实会话上下文，如 SIMULATE 注入）
    private var sessionStartElapsed = 0L

    // 当前段（基础段/延期段）开始的 elapsedRealtime：算「继续」早说时距预警还有多久
    private var segmentStartElapsed = 0L

    // 停止请求：释放后置 true，后台初始化线程逐阶段检查，防止「停止后初始化完成又抢麦」的竞态
    @Volatile
    private var stopRequested = false

    // 会话代际：每次启动新会话 +1，初始化线程捕获自己的代际，阶段间核对待办——
    // 加载期间被「退出再重开」顶替时，旧线程就地释放已分配资源，防双初始化 / native 内存泄漏
    @Volatile
    private var sessionGeneration = 0

    // 句子身份（2026-09-28 M4）：sessionTag 在会话提交点生成、utteranceSeq 由识别线程单调递增，
    // 组成 utteranceId 贯穿 采集→判定→使用记录——记录关联不再靠「识别文本相同」匹配
    // （旧法在「同一句连说两遍」时会关联错条目），跨句永不串号。
    // @Volatile（2026-09-30 收尾轮）：重复任务的迟到判定在主线程读、识别线程写。
    @Volatile
    private var sessionTag = ""
    @Volatile
    private var utteranceSeq = 0

    // 启动加载在途标记（v0.55.7）：true = 有初始化线程正在加载模型且尚未落地/夭折。
    // 在途期间的重复启动请求一律忽略——此前每次点击都会代际+1 把加载中的线程顶替弃货，
    // 连点比加载快时永远加载不完，主页涟漪转不停、会话起不来（2026-09-17 用户实测复现）。
    // 与代际机制分工：代际管「被顶替后正确退货」，本标记管「根本不许顶替」
    @Volatile
    private var initInFlight = false

    // 初始化互斥锁：串行化模型创建（每个 SenseVoice 约 200MB，并发创建会 OOM）
    // 跨 Service 实例共享：旧识别线程未退出前，新会话不能再创建第二套 200MB 原生模型。
    private val initLock = NativeModelInitLock.lock

    // 仅持有跨进程句音频判决入口；主进程不得加载 ORT 1.20 原生库。
    private data class RemoteDecisionSession(val client: ContentProviderClient, val token: String)
    @Volatile private var audioDecisionSession: RemoteDecisionSession? = null
    @Volatile private var decisionIpcUnavailable = false
    private val decisionInitLock = Any()
    @Volatile private var decisionInitThread: Thread? = null
    private var decisionInitRequestedGeneration = 0
    private var decisionInitAttemptedGeneration = -1
    private val decisionExecutor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(1),
        ThreadFactory { task -> Thread(task, "audio-decision-ipc").apply { isDaemon = true } }
    )

    private fun isDecisionIpcBlocked(): Boolean = decisionIpcUnavailable ||
        SystemClock.elapsedRealtime() < audioDecisionBlockedUntil

    private fun markDecisionIpcUnavailable() {
        decisionIpcUnavailable = true
        audioDecisionBlockedUntil = SystemClock.elapsedRealtime() + AUDIO_DECISION_RETRY_BACKOFF_MS
    }

    private fun initRemoteDecision(): RemoteDecisionSession? = synchronized(AUDIO_DECISION_CONNECT_LOCK) {
        if (!AudioReviewRequest.AVAILABLE) return@synchronized null
        if (isDecisionIpcBlocked()) return@synchronized null
        val client = try {
            contentResolver.acquireUnstableContentProviderClient(AudioDecisionProvider.URI)
        } catch (error: Throwable) {
            Log.e(TAG, "AUDIO_DECISION 无法启动独立进程，本会话使用基础识别", error)
            markDecisionIpcUnavailable()
            return@synchronized null
        }
        if (client == null) {
            markDecisionIpcUnavailable()
            return@synchronized null
        }
        val session = RemoteDecisionSession(client, java.util.UUID.randomUUID().toString())
        try {
            if (callRemoteDecision(session, AudioDecisionProvider.INIT,
                    Bundle().apply { putBoolean(AudioDecisionProvider.M6_SHADOW, m6ShadowEnabled) },
                    AUDIO_DECISION_INIT_TIMEOUT_MS)
                    ?.getBoolean(AudioDecisionProvider.READY) == true) {
                audioDecisionBlockedUntil = 0L
                Log.i(TAG, "AUDIO_DECISION 独立进程初始化成功")
                session
            } else {
                Log.e(TAG, "AUDIO_DECISION 初始化失败或超时，本会话继续使用基础识别")
                markDecisionIpcUnavailable()
                releaseRemoteDecision(session)
                null
            }
        } catch (error: Throwable) {
            Log.e(TAG, "AUDIO_DECISION 独立进程连接失败，本会话继续使用基础识别", error)
            markDecisionIpcUnavailable()
            releaseRemoteDecision(session)
            null
        }
    }

    /** 二审模型在基础识别已经开始后单独初始化；失败不会挡住普通聆听。
     *  2026-09-30 D 阶段：Debug 构建附带 m6 全类头 shadow 加载（只算提案不改变动作）——
     *  Release 恒 false（任务书 D：OFF/Release 零新增加载）。 */
    private val m6ShadowEnabled: Boolean
        get() = AudioReviewRequest.AVAILABLE &&
            (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private fun startRemoteDecisionInit(generation: Int) {
        if (!recording || !AudioDecision.isEnabled(applicationContext) || isDecisionIpcBlocked()) return
        synchronized(decisionInitLock) {
            decisionInitRequestedGeneration = generation
            if (!recording || !AudioDecision.isEnabled(applicationContext) || isDecisionIpcBlocked()) return
            if (audioDecisionSession != null || decisionInitAttemptedGeneration == generation) return
            if (decisionInitThread?.isAlive == true) return

            decisionInitAttemptedGeneration = generation
            val worker = Thread({
                val currentThread = Thread.currentThread()
                val pending = runCatching { initRemoteDecision() }.getOrElse { error ->
                    markDecisionIpcUnavailable()
                    Log.e(TAG, "AUDIO_DECISION 初始化异常，本会话继续使用基础识别", error)
                    null
                }
                var adopted = false
                if (pending != null) synchronized(decisionInitLock) {
                    if (recording && sessionGeneration == generation &&
                        AudioDecision.isEnabled(applicationContext) && !isDecisionIpcBlocked() &&
                        audioDecisionSession == null) {
                        audioDecisionSession = pending
                        adopted = true
                    }
                }
                if (!adopted) releaseRemoteDecision(pending)

                var retryGeneration: Int? = null
                synchronized(decisionInitLock) {
                    if (decisionInitThread === currentThread) decisionInitThread = null
                    if (!isDecisionIpcBlocked() && recording && AudioDecision.isEnabled(applicationContext) &&
                        audioDecisionSession == null && decisionInitRequestedGeneration > generation) {
                        retryGeneration = decisionInitRequestedGeneration
                    }
                }
                retryGeneration?.let(::startRemoteDecisionInit)
            }, "audio-decision-init").apply { isDaemon = true }
            decisionInitThread = worker
            worker.start()
        }
    }

    /** ContentProviderClient 只在单线程 executor 中调用；Android 文档明确它不是线程安全对象。 */
    private fun callRemoteDecision(
        session: RemoteDecisionSession,
        method: String,
        extras: Bundle?,
        timeoutMs: Long,
    ): Bundle? {
        if (isDecisionIpcBlocked()) return null
        val request = Bundle().apply {
            putString(AudioDecisionProvider.SESSION, session.token)
            extras?.let { putAll(it) }
        }
        val task = runCatching {
            decisionExecutor.submit<Bundle?> { session.client.call(method, null, request) }
        }.getOrElse { error ->
            Log.e(TAG, "AUDIO_DECISION 请求未能排队：$method", error)
            if (method != AudioDecisionProvider.SHUTDOWN) markDecisionIpcUnavailable()
            return null
        }
        return try {
            task.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            task.cancel(true)
            if (method != AudioDecisionProvider.SHUTDOWN) markDecisionIpcUnavailable()
            Log.e(TAG, "AUDIO_DECISION 请求超时 ${timeoutMs}ms：$method，本会话不再向该连接发送请求")
            null
        } catch (e: InterruptedException) {
            task.cancel(true)
            Thread.currentThread().interrupt()
            if (method != AudioDecisionProvider.SHUTDOWN) markDecisionIpcUnavailable()
            Log.w(TAG, "AUDIO_DECISION 请求线程被中断：$method")
            null
        } catch (e: Throwable) {
            task.cancel(true)
            if (method != AudioDecisionProvider.SHUTDOWN) markDecisionIpcUnavailable()
            Log.e(TAG, "AUDIO_DECISION IPC 失败：$method", e.cause ?: e)
            null
        }
    }

    private fun decideRemote(session: RemoteDecisionSession?, pcm: FloatArray): AudioDecisionOutcome {
        if (session == null) return AudioDecisionOutcome(null, null, 0L, "client_unavailable")
        val reply = callRemoteDecision(
            session, AudioDecisionProvider.DECIDE,
            Bundle().apply { putFloatArray(AudioDecisionProvider.PCM, pcm) }, 2500L,
        ) ?: run {
            markDecisionIpcUnavailable()
            if (audioDecisionSession === session) audioDecisionSession = null
            releaseRemoteDecision(session)
            DiagnosticsHelper.log("二审 IPC 超时或进程失联，本会话回退原命令且不再重试")
            return AudioDecisionOutcome(null, null, 0L, "ipc_timeout_or_dead")
        }
        return AudioDecisionOutcome(
            decision = reply.getString(AudioDecisionProvider.RESULT),
            confidence = if (reply.containsKey(AudioDecisionProvider.CONFIDENCE))
                reply.getFloat(AudioDecisionProvider.CONFIDENCE) else null,
            latencyMs = reply.getLong(AudioDecisionProvider.LATENCY_MS, 0L),
            fallbackReason = reply.getString(AudioDecisionProvider.FALLBACK_REASON),
            encoderMs = reply.getLong(AudioDecisionProvider.ENCODER_MS, 0L),
            headMs = reply.getLong(AudioDecisionProvider.HEAD_MS, 0L),
            // M6 shadow 提案（2026-09-30 D）：label:prob,... 解析；未加载为 null
            m6Top = reply.getString(AudioDecisionProvider.M6_TOP)?.split(",")?.mapNotNull {
                val i = it.indexOf(':')
                if (i <= 0) null else it.substring(0, i) to it.substring(i + 1).toFloatOrNull()
            }?.filter { it.second != null }?.map { it.first to it.second!! },
            numTop = reply.getString(AudioDecisionProvider.NUM_TOP)?.split(",")?.mapNotNull {
                val i = it.indexOf(':')
                if (i <= 0) null else it.substring(0, i) to it.substring(i + 1).toFloatOrNull()
            }?.filter { it.second != null }?.map { it.first to it.second!! },
            numSegmentTop = reply.getString(AudioDecisionProvider.NUM_SEGMENT_TOP)?.split(",")?.mapNotNull {
                val i = it.indexOf(':')
                if (i <= 0) null else it.substring(i+1).toFloatOrNull()?.let { p -> it.substring(0,i) to p }
            },
            numSegmentHeadUs = reply.getLong(AudioDecisionProvider.NUM_SEGMENT_HEAD_US,0L),
            numPairTop = reply.getString(AudioDecisionProvider.NUM_PAIR_TOP)?.split(",")?.mapNotNull {
                val i = it.indexOf(':')
                if (i <= 0) null else it.substring(i+1).toFloatOrNull()?.let { p -> it.substring(0,i) to p }
            },
            numPairHeadUs = reply.getLong(AudioDecisionProvider.NUM_PAIR_HEAD_US,0L),
            cursorTop = reply.getString(AudioDecisionProvider.CURSOR_TOP)?.split(",")?.mapNotNull {
                val i = it.indexOf(':')
                if (i <= 0) null else it.substring(i+1).toFloatOrNull()?.let { p -> it.substring(0,i) to p }
            },
            cursorHeadUs = reply.getLong(AudioDecisionProvider.CURSOR_HEAD_US,0L),
        )
    }

    /** 先在 IPC 队列尾向 provider 归还 session，再关闭 client，避免与在途 call 并发。 */
    private fun releaseRemoteDecision(session: RemoteDecisionSession?) {
        if (session == null) return
        runCatching {
            decisionExecutor.execute {
                try {
                    val extras = Bundle().apply { putString(AudioDecisionProvider.SESSION, session.token) }
                    val reply = session.client.call(AudioDecisionProvider.SHUTDOWN, null, extras)
                    reply?.getString(AudioDecisionProvider.STATS)?.let {
                        Log.i(TAG, it)
                        DiagnosticsHelper.log(it)
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "AUDIO_DECISION session 释放失败", e)
                } finally {
                    runCatching { session.client.close() }
                }
            }
        }.onFailure { error -> Log.w(TAG, "AUDIO_DECISION client 释放任务未能排队", error) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        UsageLog.init(applicationContext)   // 幂等：加载使用记录
        CrashCatcher.register(applicationContext)   // v0.43.0：崩溃记录器（幂等）
        // 飞行记录仪（v0.51.0）黑匣子终检：上次会话的出生档还在 = 它没落地（进程被杀/崩溃，当时无法记录）。
        // 在此事后追认一条使用记录（任何启动形式必经：真实会话/SIMULATE/探针）；检测即销档防重复补记。
        // 活体检查：本进程会话还在录（如会话中收到停止/重开/注入）时档属于活会话，不是孤儿——
        // 真机验收 A 组实锤的误报（会话 21:36:44 出生、21:36:59 注入退出，终检把活会话当失踪）
        val bb = getSharedPreferences("app", MODE_PRIVATE)
        val orphan = bb.getLong(KEY_SESSION_ACTIVE_SINCE, 0L)
        if (orphan > 0L && !recording) {
            val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
            SessionState.lastMatch = "→ ⚠️ 上次会话异常终止（${fmt.format(java.util.Date(orphan))} 开始，无结束记录——进程被杀或崩溃，详见导出反馈）"
            Log.w(TAG, "BLACKBOX 上次会话无结束记录（开始于 $orphan），已补记异常终止")
            bb.edit().remove(KEY_SESSION_ACTIVE_SINCE).apply()
        }
        // v0.56.26 商用门禁：开发期测试入口（SIMULATE 注入/文本探针/崩溃触发/回声台/ASR 台）
        // 仅 Debug 构建生效，Release 构建一律忽略——公开发行包不含任何远程调试后门
        val debugBuild = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        // 用户移除二审后，旧诊断入口也不得加载模型、推理或占麦。
        if (!AudioReviewRequest.AVAILABLE && intent?.action in setOf(ACTION_DECISION_PROBE, ACTION_M6_PCM_PROBE)) {
            Log.i(TAG, "声音二审已移除，忽略旧诊断入口")
            if (!recording && !initInFlight) {
                // 兼容 startForegroundService 的生命周期，随后立即收掉通知。
                createChannelIfNeeded()
                startForeground(NOTIFICATION_ID, buildNotification("语音控制"))
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelfResult(startId)
            }
            return START_NOT_STICKY
        }
        if (!debugBuild && intent?.action in setOf(
                ACTION_SIMULATE, ACTION_TEXT_PROBE, ACTION_CRASH_TEST,
                ACTION_ASR_TEST, ACTION_ECHO_TEST, ACTION_ECHO_STOP, ACTION_DECISION_PROBE,
                ACTION_M6_PCM_PROBE
            )
        ) {
            Log.w(TAG, "Release 构建忽略调试动作: ${intent?.action}")
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_STOP) {
            releaseAndStop("用户退出")
            return START_NOT_STICKY
        }

        // 输入框文本操作探针（开发期诊断，Release 由 DEBUG 门禁自动禁用）：验证微信等输入框的改文本/光标/粘贴可行性。
        // 不占麦不进会话；前置条件=目标聊天页已打开且输入框可见
        if (intent?.action == ACTION_TEXT_PROBE) {
            createChannelIfNeeded()
            startForeground(NOTIFICATION_ID, buildNotification("🔬 文本探针"))
            Thread {
                runCatching { VoiceControlService.textProbe() }
                runCatching {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }.start()
            return START_NOT_STICKY
        }

        // 音频二审延迟探针（v0.58.0-enhanced 开发埋点）：不占麦，用合成 2s 音频直接测
        // fbank+LFR+CMVN+encoder+头 的真机端到端耗时
        if (intent?.action == ACTION_DECISION_PROBE) {
            if (recording || initInFlight) {
                Log.w(TAG, "DECISION_PROBE 忽略：当前会话正在运行或初始化")
                return START_NOT_STICKY
            }
            createChannelIfNeeded()
            startForeground(NOTIFICATION_ID, buildNotification("⏱️ 二审延迟探针"))
            Thread {
                var session: RemoteDecisionSession? = null
                try {
                    session = initRemoteDecision()
                    val pcm = FloatArray(32000) { Math.sin(it * 0.02).toFloat() * 0.3f }
                    val t0 = SystemClock.elapsedRealtime()
                    val decision = decideRemote(session, pcm)
                    val dt = SystemClock.elapsedRealtime() - t0
                    // 无麦克风构造一次正式 ASR，核对两个进程的原生库都能加载。
                    val asrReady = runCatching {
                        synchronized(initLock) {
                            createRecognizer()?.let { it.release(); true } ?: false
                        }
                    }.getOrElse { error ->
                        Log.e(TAG, "DECISION_PROBE ASR 原生库加载失败", error)
                        false
                    }
                    val report = "DECISION_PROBE 真机二审耗时=${dt}ms decision=${decision.decision} conf=${decision.confidence} fallback=${decision.fallbackReason} asrReady=$asrReady remoteReady=${session != null} at=${System.currentTimeMillis()}"
                    Log.i(TAG, report)
                    runCatching {
                        java.io.File(getFilesDir(), "decision_probe.txt")
                            .appendText(report + "\n", Charsets.UTF_8)
                    }
                } finally {
                    releaseRemoteDecision(session)
                    // 探针执行期间可能收到正式启动；不要因此移除正式会话的通知或停止服务。
                    if (!recording && !initInFlight) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }.start()
            return START_NOT_STICKY
        }

        // M6 固定 PCM 一致性探针（2026-09-30 D 复核①，Debug 限定）：只推理不派发动作。
        // 读 probe_pc.json（PC 端 m6_probe.py gen 推到 /data/local/tmp）中列出的 wav 文件，
        // 逐条 16k 重采样+trim（与 PC 相同预处理）→ 生产 decideDetailed 链（真实 encoder
        // pooled→音量头+m6 头）→ 写 files/m6_android_probe.json，adb pull 后 PC compare。
        if (intent?.action == ACTION_M6_PCM_PROBE) {
            if (recording || initInFlight) {
                Log.w(TAG, "M6_PCM_PROBE 忽略：会话运行中")
                return START_NOT_STICKY
            }
            createChannelIfNeeded()
            startForeground(NOTIFICATION_ID, buildNotification("🔬 M6 一致性探针"))
            Thread {
                var session: RemoteDecisionSession? = null
                try {
                    session = initRemoteDecision()
                    val sess = session
                    if (sess == null) {
                        Log.e(TAG, "M6_PCM_PROBE：远程进程不可用")
                        return@Thread
                    }
                    // 通知 provider 加载 m6（探针专用——与正式会话同链）
                    callRemoteDecision(sess, AudioDecisionProvider.INIT,
                        Bundle().apply {
                            putString(AudioDecisionProvider.SESSION, sess.token)
                            putBoolean(AudioDecisionProvider.M6_SHADOW, true)
                        }, AUDIO_DECISION_INIT_TIMEOUT_MS)
                    val json = java.io.File("/data/local/tmp/probe_pc.json")
                    if (!json.isFile) {
                        Log.e(TAG, "M6_PCM_PROBE：/data/local/tmp/probe_pc.json 不存在（先 adb push）")
                        return@Thread
                    }
                    val cases = org.json.JSONArray(json.readText(Charsets.UTF_8))
                    val out = org.json.JSONArray()
                    for (i in 0 until cases.length()) {
                        val c = cases.getJSONObject(i)
                        val wavPath = c.getString("pcm_path")
                        // PC 侧路径 E:\... → 设备侧 /data/local/tmp/m6probe/<basename>
                        val devPath = "/data/local/tmp/m6probe/" + wavPath.substringAfterLast('\\')
                            .substringAfterLast('/')
                        val f = java.io.File(devPath)
                        if (!f.isFile) {
                            Log.w(TAG, "M6_PCM_PROBE 缺文件: $devPath")
                            continue
                        }
                        val pcm = readWav16k(f) ?: continue
                        val trimmed = trimProbe(pcm)
                        if (trimmed.size < 2400) continue
                        val oc = decideRemote(sess, trimmed)
                        out.put(org.json.JSONObject()
                            .put("file", c.getString("file"))
                            .put("pooled_first8", org.json.JSONArray())   // pooled 不出 IPC——以 vol/m6 输出对照
                            .put("vol_decision", oc.decision ?: "null")
                            .put("vol_conf", oc.confidence ?: -1.0)
                            .put("m6_top", oc.m6Top?.joinToString(",") { "${it.first}:${it.second}" } ?: "")
                            .put("num_top", oc.numTop?.joinToString(",") { "${it.first}:${it.second}" } ?: "")
                            .put("num_segment_top", oc.numSegmentTop?.joinToString(",") { "${it.first}:${it.second}" } ?: "")
                            .put("num_segment_head_us",oc.numSegmentHeadUs)
                            .put("num_pair_top",oc.numPairTop?.joinToString(",") { "${it.first}:${it.second}" } ?: "")
                            .put("num_pair_head_us",oc.numPairHeadUs)
                            .put("cursor_top",oc.cursorTop?.joinToString(",") { "${it.first}:${it.second}" } ?: "")
                            .put("latency_ms",oc.latencyMs).put("encoder_ms",oc.encoderMs)
                            .put("cursor_head_us",oc.cursorHeadUs))
                    }
                    java.io.File(getFilesDir(), "m6_android_probe.json")
                        .writeText(out.toString(1), Charsets.UTF_8)
                    Log.i(TAG, "M6_PCM_PROBE 完成：${out.length()} cases → files/m6_android_probe.json")
                } finally {
                    releaseRemoteDecision(session)
                    if (!recording && !initInFlight) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }.start()
            return START_NOT_STICKY
        }

        // 崩溃触发后门（开发期诊断，Release 由 DEBUG 门禁自动禁用）：主动抛空指针验证 CrashCatcher 记录→导出链路。
        // 必须不在录音线程：崩溃由未捕获异常处理器记录后原样交还系统（行为=真实闪退）
        if (intent?.action == ACTION_CRASH_TEST) {
            createChannelIfNeeded()
            startForeground(NOTIFICATION_ID, buildNotification("💥 崩溃测试"))
            Thread {
                Thread.sleep(500)   // 等 startForeground 落地，避免被系统当成 FGS 超时而非测试崩溃
                throw NullPointerException("CRASH_TEST 主动测试崩溃")
            }.start()
            return START_NOT_STICKY
        }

        // 回声测试台（开发期专用）：外放命令词音频，复现/验证「抖音视频声音被误识别」
        if (intent?.action == ACTION_ECHO_TEST) {
            startEchoTest()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_ECHO_STOP) {
            stopEchoTest()
            return START_NOT_STICKY
        }

        // 离线 ASR 测试台：静音、不占麦，用 wav 文件直接测识别准确率
        if (intent?.action == ACTION_ASR_TEST) {
            val wav = intent.getStringExtra(EXTRA_WAV) ?: "numbers.wav"
            val score = intent.getFloatExtra(EXTRA_SCORE, HOTWORDS_SCORE)
            runOfflineAsrTest(wav, score)
            return START_NOT_STICKY
        }

        // 测试注入（仅开发期；Release 由 DEBUG 门禁自动禁用）：
        // 修复验证闭环的关键——bug 必须先在真机重放通过，才允许宣称「修好」
        if (intent?.action == ACTION_SIMULATE) {
            val text = intent.getStringExtra(EXTRA_TEXT)?.replace(" ", "")
            if (!text.isNullOrBlank()) {
                Log.i(TAG, "SIMULATE 注入: $text")
                val inSession = recording
                if (!inSession) {
                    // startForegroundService 启动的服务必须调 startForeground，否则 Android 14+
                    // 直接崩整个进程——无障碍服务同进程陪葬，系统标「此服务出现故障」、胶囊弹不出
                    // （2026-09-11 深夜事故根因，用户实拍）。测试注入也必须合法前台化再走。
                    createChannelIfNeeded()
                    startForeground(NOTIFICATION_ID, buildNotification("🔔 测试注入"))
                    recording = true // 放行 onRecognized 的会话守卫
                }
                try {
                    onRecognized(text)
                } finally {
                    if (!inSession) {
                        recording = false
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
            return START_NOT_STICKY
        }

        // 已在会话中 → 不重复启动（防止重复占麦、重复开识别线程）
        if (recording) return START_NOT_STICKY

        // v0.55.7：启动加载中重复点击 → 直接忽略，绝不重启模型加载（分工见 initInFlight 注释）
        if (initInFlight) {
            Log.i(TAG, "启动请求忽略：模型加载已在进行中（重复点击防抖）")
            return START_NOT_STICKY
        }

        createChannelIfNeeded()
        startForeground(NOTIFICATION_ID, buildNotification("🔴 会话中 · 正在听"))
        // 模型加载秒级耗时，放后台线程：不阻塞主线程（ANR 风险），加载完成前不占麦。
        // 用「会话代际 + 初始化互斥锁」防并发初始化：
        //   代际——加载期间退出再立刻重开，旧线程发现被顶替就地释放资源，绝不双占麦 / 泄漏；
        //   互斥锁——同一时刻只允许一个线程创建重模型（每个 SenseVoice 约 200MB，并发创建会 OOM）。
        stopRequested = false
        initInFlight = true
        val myGen = ++sessionGeneration
        thread(name = "voice-init") {
            try {
                var committedSession: RecognitionSessionIdentity? = null
                synchronized(initLock) {
                    // 锁内重新核对待办：前面已有线程在创建时，本次可能已作废
                    if (isSessionStale(myGen)) return@thread
                    val rec = createRecognizer()
                    if (rec == null) {
                        Log.e(TAG, "模型初始化失败，不占用麦克风")
                        if (isSessionCurrent(myGen)) stopSelf()
                        return@thread
                    }
                    if (isSessionStale(myGen)) { runCatching { rec.release() }; return@thread }
                    val v = createVad()
                    if (v == null) {
                        Log.e(TAG, "VAD 初始化失败，不占用麦克风")
                        runCatching { rec.release() }
                        if (isSessionCurrent(myGen)) stopSelf()
                        return@thread
                    }
                    if (isSessionStale(myGen)) {
                        runCatching { rec.release() }; runCatching { v.release() }
                        return@thread
                    }
                    if (!startMicrophone()) {
                        Log.e(TAG, "麦克风初始化失败")
                        runCatching { rec.release() }; runCatching { v.release() }
                        if (isSessionCurrent(myGen)) stopSelf()
                        return@thread
                    }
                    if (isSessionStale(myGen)) {
                        // 麦克风已启动却被新会话顶替：还麦 + 清理本地资源
                        releaseMicrophone()
                        runCatching { rec.release() }; runCatching { v.release() }
                        return@thread
                    }
                    // 全部成功且仍是当前会话 → 锁内提交字段（防锁外提交被插队），锁外启动识别线程。
                    // 2026-10-02：标签/句序先发布，再允许聆听；否则线程可能永久拿着空或上一会话标签。
                    recognizer = rec
                    vad = v
                    committedSession = RecognitionSessionIdentity.commit(myGen,
                        java.util.UUID.randomUUID().toString().take(6)) { identity ->
                        sessionTag = identity.tag
                        utteranceSeq = 0
                    }
                    recording = true
                }
                val loopSession = committedSession
                if (loopSession != null) {
                    // 先让基础识别完整落地；二审远程进程在后台启动，初始化超时不会挡住聆听。
                    startRemoteDecisionInit(myGen)
                    recordThread = thread(name = "voice-recognition") { recognitionLoop(loopSession) }
                    acquireScreenLock()   // 会话常亮（v0.57.6 用户拍板）：真正会话开始的唯一提交点
                    // 显示顶部识别状态横条
                    VoiceControlService.showBar()
                    VoiceControlService.updateBar("🎤 正在聆听…")
                    // 启动看门狗（时间制独挑大梁）与休眠预警
                    extensionCount = 0
                    // 飞行记录仪（v0.51.0）：会话出生留档（异常终检在 onStartCommand 顶部，任何启动形式都先过一遍）
                    sessionStartElapsed = SystemClock.elapsedRealtime()
                    getSharedPreferences("app", MODE_PRIVATE)
                        .edit().putLong(KEY_SESSION_ACTIVE_SINCE, System.currentTimeMillis()).apply()
                    sleepWarned = false
                    segmentStartElapsed = SystemClock.elapsedRealtime()   // 本段起点（「继续」早说检测用）
                    handler.postDelayed(watchdogRunnable, WATCHDOG_MILLIS)
                    handler.postDelayed(warnRunnable, WATCHDOG_MILLIS - WARN_BEFORE_MILLIS)
                    // 锁屏自动释放：注册黑屏广播，锁屏即把麦克风还给系统
                    registerScreenOffReceiver()
                    SessionState.phase = SessionState.Phase.LISTENING
                    Log.i(TAG, "SESSION_COMMIT 模型加载完成，会话落地开始聆听（gen=${loopSession.generation} tag=${loopSession.tag}）")
                }
            } finally {
                // 无论落地/夭折/失败，线程结束即清在途标记，放行下一次启动（v0.55.7）
                initInFlight = false
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopRequested = true
        recording = false
        runCatching { unregisterReceiver(screenOffReceiver) }
        handler.removeCallbacks(watchdogRunnable)
        handler.removeCallbacks(warnRunnable)
        handler.removeCallbacks(barResetRunnable)
        // 2026-09-30 边界：onDestroy 兜底也留准确中止结果——正常退出路径已走 releaseAndStop
        // （写回「会话结束」并把任务清空），这里只覆盖系统直接销毁、未走统一出口的罕见情形
        repeatTask?.let { task ->
            repeatRunnable?.let(handler::removeCallbacks)
            repeatTask = null
            repeatRunnable = null
            task.cancel(CommandRouting.RepeatTask.CancelReason.SERVICE_DESTROYED)
        }
        VoiceControlService.hideBar()
        releaseMicrophone()   // 兜底（正常释放路径在 releaseAndStop 里已先执行）
        micReleaseReceipt(100L)
        teardownNativeAsync() // recognizer/vad 等识别线程退出后再释放，防 use-after-free
        super.onDestroy()
    }

    /** 注册锁屏广播（SCREEN_OFF）：锁屏立即释放麦克风，保证黑屏时随时能唤起小爱 */
    /** 读 wav → 16k mono float（M6 探针用；标准 16bit PCM，与 PC load16k 同语义） */
    private fun readWav16k(f: java.io.File): FloatArray? {
        return try {
            val bytes = f.readBytes()
            var idx = 12
            var dataOfs = -1
            var dataLen = 0
            while (idx + 8 <= bytes.size) {
                val id = String(bytes, idx, 4, Charsets.US_ASCII)
                val len = ((bytes[idx + 4].toInt() and 0xff) or ((bytes[idx + 5].toInt() and 0xff) shl 8)
                    or ((bytes[idx + 6].toInt() and 0xff) shl 16) or ((bytes[idx + 7].toInt() and 0xff) shl 24))
                if (id == "data") {
                    dataOfs = idx + 8; dataLen = len; break
                }
                idx += 8 + len + (len and 1)
            }
            if (dataOfs < 0 || dataLen < 2) return null
            val n = dataLen / 2
            val pcm = FloatArray(n)
            for (i in 0 until n) {
                val v = ((bytes[dataOfs + 2 * i].toInt() and 0xff) or (bytes[dataOfs + 2 * i + 1].toInt() shl 8))
                pcm[i] = v / 32768f
            }
            pcm
        } catch (e: Throwable) {
            Log.w(TAG, "readWav16k 失败: ${f.path}", e)
            null
        }
    }

    /** 与 PC trim 同语义（阈值 0.008）——M6 探针用 */
    private fun trimProbe(x: FloatArray): FloatArray {
        var start = 0
        var end = x.size - 1
        while (start < x.size && Math.abs(x[start]) <= 0.008f) start++
        while (end > start && Math.abs(x[end]) <= 0.008f) end--
        return if (start >= end) x else x.copyOfRange(start, end + 1)
    }

    private fun registerScreenOffReceiver() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
            }
        }
    }

    /** 创建离线识别器（SenseVoice，非流式整句识别）；返回本地对象，由调用方决定提交或释放 */
    private fun createRecognizer(hotwordsScore: Float = HOTWORDS_SCORE): OfflineRecognizer? {
        return try {
            val hotwordsFile = writeHotwords()
            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(
                        model = "sensevoice.int8.onnx",
                        language = "zh",
                        useInverseTextNormalization = false,
                    ),
                    tokens = "tokens.txt",
                    numThreads = 4, // 2→4（2026-09-09 提速）：整句解码更快，代价仅会话期略增功耗
                    debug = false,
                    provider = "cpu",
                ),
                decodingMethod = "greedy_search", // 禁区：beam search 实测 native 崩进程（2026-09-12 复现，热词实验后回退）
                // 热词（contextual biasing）：让解码时偏向命令词表，从源头减少「向右→鲜花」这类听岔
                hotwordsFile = hotwordsFile,
                hotwordsScore = hotwordsScore,
            )
            val r = OfflineRecognizer(assets, config)
            Log.i(TAG, "识别器初始化成功（SenseVoice）")
            r
        } catch (e: Exception) {
            Log.e(TAG, "识别器初始化失败", e)
            null
        }
    }

    /** 创建 VAD（silero 深度学习语音活动检测，判断一句话的起止）；返回本地对象 */
    private fun createVad(): Vad? {
        return try {
            val config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = "silero_vad.onnx",
                    // v0.38.0 起按用户灵敏度滑块读取（格 5 = 0.60f 恰为历史常量，默认零变化）：
                    // 越高门槛越低，小声/口齿不清才"开得了闸"；映射见 RecognitionSensitivity
                    threshold = RecognitionSensitivity.vadThreshold(
                        RecognitionSensitivity.level(applicationContext)
                    ),
                    // v0.55.2 起 0.3s（说完到动手延迟的大头）：短换气 (<0.3s) 不被斩断，
                    // 长换气贴线——出现句子被拦腰斩断就回退 0.35/0.4（历史：0.5→0.4 v0.18→0.3 v0.55.2）。
                    // v0.54.0 弱声专档（9~10 格）另放宽到 0.55s：构音障碍者字间停顿长，窗口太短会斩句
                    minSilenceDuration = RecognitionSensitivity.minSilence(
                        RecognitionSensitivity.level(applicationContext)
                    ),
                    minSpeechDuration = RecognitionSensitivity.minSpeech(
                        RecognitionSensitivity.level(applicationContext)
                    ),
                    windowSize = 512,
                    // v0.54.0 弱声专档放宽到 5s：慢语速长句不被腰斩；其余格 3s 不变
                    maxSpeechDuration = RecognitionSensitivity.maxSpeech(
                        RecognitionSensitivity.level(applicationContext)
                    ),
                ),
                sampleRate = SAMPLE_RATE,
                numThreads = 1,
                debug = false,
                provider = "cpu",
            )
            val v = Vad(assets, config)
            Log.i(TAG, "VAD 初始化成功（minSilence=${config.sileroVadModelConfig.minSilenceDuration}s threshold=${config.sileroVadModelConfig.threshold}）")
            v
        } catch (e: Exception) {
            Log.e(TAG, "VAD 初始化失败", e)
            null
        }
    }

    /** 本 init 线程是否已被新会话顶替（代际过期或已请求停止） */
    private fun isSessionStale(myGen: Int): Boolean = stopRequested || sessionGeneration != myGen

    /** 是否仍是当前活跃会话（未被顶替且未停止） */
    private fun isSessionCurrent(myGen: Int): Boolean = !isSessionStale(myGen)

    /** 把命令词表写成热词文件，返回文件绝对路径（词表为空则返回空串、跳过热词） */
    private fun writeHotwords(): String {
        // v0.42.0：并入用户自定义常用词（人名/地名）——来源只增不减，指令词 8.0 分照旧；
        // 撞车词已在准入时被 CustomVocab.validateWord 拦截（commandCollision），不会到这里
        val words = (currentMatcher().hotwords() + APP_HOTWORDS + NUMBER_HOTWORDS +
            REPEAT_HOTWORDS + CustomVocab.all(applicationContext)).distinct()
        if (words.isEmpty()) {
            Log.w(TAG, "命令词表为空，跳过热词")
            return ""
        }
        // 本模型 tokens 是「字符级」的（每个汉字一个 token），
        // 热词必须写成「字与字之间用空格分隔」，否则会被当成一个不存在的整词而失效。
        val lines = words
            .filter { it.all { c -> c.code in 0x4E00..0x9FFF } }   // 只保留纯汉字词
            .map { it.toCharArray().joinToString(" ") }
        if (lines.isEmpty()) {
            Log.w(TAG, "没有可用的中文热词")
            return ""
        }
        val file = File(filesDir, "hotwords.txt")
        file.writeText(lines.joinToString("\n"), Charsets.UTF_8)
        Log.i(TAG, "热词文件已生成：${lines.size} 个 -> ${file.absolutePath}")
        return file.absolutePath
    }

    /**
     * 启动麦克风。
     *
     * 音频源用 VOICE_COMMUNICATION 而非 VOICE_RECOGNITION —— 这是「外放声音被误识别成命令」的根治点：
     * 实测本机 /vendor/etc/audio_effects.xml 只给 voice_communication 挂了 aec/ns/agc 前处理链，
     * VOICE_RECOGNITION 一个都没有，所以抖音外放的人声被原样拾取、精确识别成「向上滑动」（已真机复现）。
     * VOICE_COMMUNICATION 是 Android 官方为通话/语音助手设计的源，系统自动做回声消除。
     * 再显式 attach AEC/NS/AGC 作双保险（部分 ROM 的 preprocess 配置不全）。
     */
    private fun startMicrophone(): Boolean {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            if (minBuf > 0) minBuf * 2 else 8192
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return false
        }
        audioRecord = recorder
        attachAudioEffects(recorder.audioSessionId)   // 先挂效果器，再开始录音
        return try {
            recorder.startRecording()
            Log.i(TAG, "麦克风已启动：VOICE_COMMUNICATION + AEC=${aecEffect?.enabled == true} NS=${nsEffect?.enabled == true} AGC=${agcEffect?.enabled == true}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "startRecording 失败", e)
            detachAudioEffects()
            recorder.release()
            audioRecord = null
            false
        }
    }

    /**
     * 挂载音频前处理效果器：回声消除(AEC) / 噪声抑制(NS) / 自动增益(AGC)。
     *
     * AEC 是「刷抖音时不被视频声音误触发」的关键：它拿系统正在外放的音频作参考信号，
     * 从麦克风输入里减掉——外放声音被消掉，你近讲的人声（直达声）保留。
     * 每个效果器都先 isAvailable 判断（机型能力不同），不可用就跳过，绝不影响录音主链路。
     */
    private fun attachAudioEffects(sessionId: Int) {
        detachAudioEffects()
        runCatching {
            if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler.create(sessionId)?.apply {
                    if (enabled) { aecEffect = this; Log.i(TAG, "AEC 回声消除已启用") }
                    else runCatching { release() }
                }
            } else Log.w(TAG, "AEC 不可用（机型不支持）")
        }
        runCatching {
            if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor.create(sessionId)?.apply {
                    if (enabled) { nsEffect = this; Log.i(TAG, "NS 噪声抑制已启用") }
                    else runCatching { release() }
                }
            } else Log.w(TAG, "NS 不可用（机型不支持）")
        }
        runCatching {
            // v0.54.0 弱声专档（9~10 格）强制开启 AGC：系统层把小声/含糊嗓音拉到正常音量。
            // 机型相关行为：有的厂商不提供/不允许第三方开 AGC——失败时明确记日志（导出反馈可见），
            // 靠弱声档其余参数（低门槛/停顿容忍）兜底；实测不佳就降回 8 格（一行回退）
            val weak = RecognitionSensitivity.weakVoiceMode(
                RecognitionSensitivity.level(applicationContext)
            )
            if (!AutomaticGainControl.isAvailable()) {
                if (weak) Log.w(TAG, "AGC 不可用（机型不提供该效果器），弱声专档靠低门槛+停顿容忍兜底")
                return@runCatching
            }
            AutomaticGainControl.create(sessionId)?.apply {
                if (enabled) {
                    agcEffect = this
                    Log.i(TAG, "AGC 自动增益已启用")
                } else if (weak && runCatching { this.enabled = true }.isSuccess) {
                    agcEffect = this
                    Log.i(TAG, "AGC 自动增益已启用（弱声专档强制开启）")
                } else {
                    if (weak) Log.w(TAG, "AGC 无法开启（弱声专档）：机型不允许，靠低门槛+停顿容忍兜底")
                    runCatching { release() }
                }
            }
        }
    }

    private fun recognitionLoop(loopSession: RecognitionSessionIdentity) {
        val loopGeneration = loopSession.generation
        val v = vad ?: return
        val rec = recognizer ?: return
        val bufferSize = (SAMPLE_RATE * 0.1).toInt() // 每次读 100ms 音频
        val buffer = ShortArray(bufferSize)
        // 一阶高通滤波（IIR）：滤掉 ~100Hz 以下的低频鼓点/轰鸣/空调声，
        // 保留人声共振峰，让 VAD 和识别器少受背景低频干扰。状态跨帧延续。
        val alpha = 0.962f // 截止频率约 100Hz @ 16kHz
        var prevIn = 0f
        var prevOut = 0f
        // 灵敏度软件增益（v0.38.0）：会话开始时读一次，格 5=1.0×（历史零变化）。
        // 增益乘在滤波输入端（滤波器线性，先增益后滤波数学等价），钳制 ±1 防爆音；
        // 胶囊声波 RMS 随之放大——小声说话的用户能直接看到"波浪起来了"的正反馈。
        val gain = RecognitionSensitivity.gain(RecognitionSensitivity.level(applicationContext))
        Log.i(TAG, "识别灵敏度增益：${gain}×")
        // 起音保护（预滚缓冲）已撤（v0.57.7，2026-09-22 用户拍板方案 A）：
        // v0.56.29 的做法是把开闸前 ~0.4s 音频垫回分段开头防起音被吃——但真机使用记录实锤
        // 本机 VAD 吐出的分段已含开头，垫上去 = 开头重叠两遍 → 句句叠词（「向上向上滑」
        // 「二十二十六」，当日会话叠词占比 ~70%），听写原文直接重复落笔。
        // 撤除后：分段回归 VAD 原生行为；起音偶缺由「再说一遍+折叠匹配（collapseDoubled/
        // collapseLeadingRepeat 保留）」兜底；防误退有三道防线（阶梯判别/FUZZY_GUARD/精确红线）。
        // 勿再回加预滚——要治起音先调 VAD 参数，实证后再动。
        var readFailure: String? = null
        var consecutiveEmptyReads = 0
        try {
            while (recording && sessionGeneration == loopGeneration) {
                val recorder = audioRecord
                if (recorder == null) {
                    if (recording) readFailure = "录音对象意外释放"
                    break
                }
                val n = recorder.read(buffer, 0, buffer.size)
                if (n < 0) {
                    readFailure = "AudioRecord.read 返回错误 $n"
                    Log.e(TAG, "录音读取失败：$n")
                    break
                }
                if (n == 0) {
                    consecutiveEmptyReads++
                    if (consecutiveEmptyReads >= 10) {
                        readFailure = "连续读取到空音频"
                        Log.e(TAG, "录音连续 10 次返回空音频，结束本次会话")
                        break
                    }
                    Thread.sleep(20L)
                    continue
                }
                consecutiveEmptyReads = 0
                val samples = FloatArray(n)
                var sumSq = 0.0
                for (i in 0 until n) {
                    val x = buffer[i] / 32768f * gain
                    val y = alpha * (prevOut + x - prevIn)
                    prevIn = x
                    prevOut = y
                    samples[i] = y.coerceIn(-1f, 1f)
                    sumSq += samples[i] * samples[i]
                }
                // 实时振幅（RMS 归一化）→ 顶部胶囊声波（每 100ms 一帧，视觉上即实时）
                VoiceService.liveAmplitude =
                    kotlin.math.sqrt(sumSq / n).toFloat().times(7f).coerceIn(0f, 1f)
                // VAD 检测语音段（一句话）；检测到完整一段就交给 SenseVoice 整句识别。
                // 每句都新建 stream、用完即释放，没有状态累积，不会越跑越慢。
                v.acceptWaveform(samples)
                while (recording && sessionGeneration == loopGeneration && !v.empty()) {
                    val segment = v.front()
                    v.pop()
                    val seg = segment.samples
                    if (seg.isEmpty()) continue
                    val stream = rec.createStream()
                    val t0 = System.nanoTime()
                    val text = try {
                        stream.acceptWaveform(seg, SAMPLE_RATE)
                        rec.decode(stream)
                        rec.getResult(stream).text
                    } finally {
                        stream.release()
                    }
                    val asrMs = (System.nanoTime() - t0) / 1_000_000L
                    if (!recording) break
                    if (text.isNotBlank()) {
                        val clean = text.replace(" ", "")
                        if (sessionGeneration != loopGeneration) break
                        val utteranceId = loopSession.utteranceId(++utteranceSeq)
                        val numberRequest = NumberReviewContext.Request(utteranceId, loopGeneration,
                            VoiceControlService.numberReviewSnapshot())
                        // 二审在识别线程运行，结果与本句一起送主线程，避免卡住 UI 或串到下一句。
                        // 门控（2026-09-30 M3 统一入口）：增强关闭/听写内容/说法录入/长按待命
                        // 不请求——这些模式永远不会走到音量采纳分支，白等最多 2.5s IPC 无意义。
                        // 2026-09-30 162 轮复核修复①：退出语义保护——普通模式下含「退出」的句子
                        // **在请求前**跳过一切二审（m6Gate 此前对所有非空句为 true，导致原本
                        // 不含音/声/量的退出句也要等 IPC，故障时可到超时）；长按待命的「退出只退
                        // 待命」优先级在 onRecognized 内部（longPressMode 分支先于红线），此处
                        // longPressMode 已被两门控排除，不改变该语义。
                        // 2026-09-30 P1（Astra 复核）：M6 全类 shadow 独立门控；162 轮复核②：
                        // volGate=false 时 shadow 专用结果**不得携带 inc/dec**（旧音量采纳资格
                        // 与 shadow 结果显式隔离——本句无旧二审资格，二分类输出只作观察）
                        val enhancedAtRecognition = AudioDecision.isEnabled(applicationContext)
                        val recognitionMode = speechAuditMode()
                        val reviewPlan = AudioReviewRequest.plan(clean,
                            enhancedAtRecognition, m6ShadowEnabled,
                            dictationMode, captureArmed, longPressMode)
                        val volGate = reviewPlan.volume
                        val reviewStart = SystemClock.elapsedRealtime()
                        val decision = reviewPlan.execute { decideRemote(audioDecisionSession, seg) }
                        val reviewWaitMs = SystemClock.elapsedRealtime() - reviewStart
                        // 资格隔离：本句无旧音量二审资格（volGate=false）时，即使远程返回了
                        // inc/dec（shadow 路径顺带算出的二分类输出），也剥离其采纳资格——
                        // dispatchMatched 的音量分支只应消费有资格句的判决
                        if (decision != null) {
                            Log.i(TAG, "AUDIO_DECISION [$utteranceId] 候选原文=[$clean] 判决=${decision.decision} conf=${decision.confidence} latency=${decision.latencyMs}ms fallback=${decision.fallbackReason} volQualified=$volGate")
                            DiagnosticsHelper.log("二审候选[$utteranceId]：[$clean] → ${decision.decision ?: "回退:${decision.fallbackReason}"} conf=${decision.confidence} ${decision.latencyMs}ms volQ=$volGate")
                            DiagnosticsHelper.log("耗时[$utteranceId] asr=${asrMs}ms 二审=${decision.latencyMs}ms")
                        } else if (asrMs > 1500L) {
                            // M4 耗时观测：仅慢句留痕（诊断缓冲有限，正常句不刷屏）
                            DiagnosticsHelper.log("耗时[$utteranceId] asr=${asrMs}ms（慢）")
                        }
                        // M6 shadow 提案（2026-09-30 D）：只算提案不改变动作——绝不参与
                        // dispatch/lastMatch；诊断与使用记录留痕供后续离线对照（任务书 §D3）
                        decision?.m6Top?.let { top ->
                            val t = top.joinToString(" ") { "${it.first}:${"%.3f".format(it.second)}" }
                            DiagnosticsHelper.log("M6候选[$utteranceId]: [$clean] $t（待旧链路由）")
                            Log.i(TAG, "M6_SHADOW [$utteranceId] [$clean] $t")
                        }
                        decision?.cursorTop?.let { top ->
                            DiagnosticsHelper.log("光标候选[$utteranceId]: [$clean] $top head=${decision.cursorHeadUs}us（待旧链与焦点检查）")
                        }
                        val auditCapture = SpeechAudit.Capture(audioDurationMs = seg.size * 1000L / SAMPLE_RATE,
                            asrMs = asrMs, volumeRequested = reviewPlan.volume, m6Requested = reviewPlan.m6,
                            reviewWaitMs = reviewWaitMs, queuedAtMs = SystemClock.elapsedRealtime(),
                            enhancedAtRecognition = enhancedAtRecognition, modeAtRecognition = recognitionMode)
                        handler.post { onRecognized(clean, decision, utteranceId, numberRequest, auditCapture) }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "识别循环异常", e)
            if (recording) readFailure = "识别循环异常 ${e.javaClass.simpleName}"
        } finally {
            VoiceService.liveAmplitude = 0f
            val failure = readFailure
            if (failure != null && recording && sessionGeneration == loopGeneration) {
                DiagnosticsHelper.log("录音链路中断：$failure；停止会话并释放麦克风")
                handler.post {
                    if (sessionGeneration != loopGeneration) return@post
                    if (recording) releaseAndStop(failure)
                }
            }
        }
    }

    private fun onRecognized(text: String, decision: AudioDecisionOutcome? = null, utteranceId: String = "",
                             numberRequest: NumberReviewContext.Request? = null,
                             auditCapture: SpeechAudit.Capture? = null) {
        // 会话已结束（看门狗/静音/退出已释放）→ 忽略识别线程队列里残留的结果，避免退出后还触发动作
        if (!recording) return
        if (numberRequest != null && numberRequest.generation != sessionGeneration) return
        SessionState.lastText = text
        // 自定义说法录入（v0.39.0）：第一句原文到手立即还麦，绑定页轮询取走确认绑定
        if (captureArmed) {
            captureArmed = false
            lastCaptured = text
            Log.i(TAG, "CAPTURE 说法已捕获：[$text]")
            VoiceControlService.updateBar("已听到：$text，回页面确认绑定")
            releaseAndStop("说法录入完成")
            return
        }
        currentAudioDecision = decision
        currentNumberRequest = numberRequest
        currentDecisionDisposition = null
        currentDecisionLabel = null
        // 诊断失败不能改变执行；既不新增模型请求，也不按模型分数制造“正确答案”。
        currentSpeechAudit = if (utteranceId.isBlank()) null else runCatching {
            val liveSnapshot = VoiceControlService.numberReviewSnapshot()
            SpeechAudit.Recorder(utteranceId, SpeechAudit.Context(speechAuditMode(), AudioDecision.isEnabled(this),
                m6ShadowEnabled, sessionGeneration, "$sessionTag-u$utteranceSeq",
                VoiceControlService.isLabelsVisible(), VoiceControlService.isGridShowing(), liveSnapshot?.targets?.size,
                SpeechAudit.snapshotToken(numberRequest?.snapshot), SpeechAudit.snapshotToken(liveSnapshot)),
                auditCapture ?: SpeechAudit.Capture(source = "synthetic_injection"), decision,
                speechAssetFingerprint, SystemClock.elapsedRealtime())
        }.getOrNull()
        try { handleRecognized(text, utteranceId) } finally {
            if (decision != null) {
                val disposition = currentDecisionDisposition ?: "本句未采纳声音改判或救回"
                val label = currentDecisionLabel ?: "未参与"
                // 全局lastMatch可能还是上一句话；账目只取本句，不能拿旧✅充当新句结果。
                val ownResult = if (utteranceId.isNotBlank()) UsageLog.all().lastOrNull { it.uid == utteranceId }?.text
                    else SessionState.lastMatch
                val finalResult = ownResult?.takeIf { it.isNotBlank() } ?: "本句未记录动作结果"
                UsageLog.attachAudioDecision(
                    utteranceId, text, label,
                    "音量判决=${decision.decision ?: "回退:${decision.fallbackReason ?: "未判定"}"}; " +
                        "置信度=${decision.confidence?.let { "%.3f".format(java.util.Locale.US, it) } ?: "无"}; " +
                        "耗时=${decision.latencyMs}ms(encoder=${decision.encoderMs}ms, head=${decision.headMs}ms); " +
                        "M6候选=${decision.m6Top}; 数字候选=${decision.numTop}; 分段数字候选=${decision.numSegmentTop}; 分段头=${decision.numSegmentHeadUs}us; 4/10候选=${decision.numPairTop}; 4/10头=${decision.numPairHeadUs}us; 光标候选=${decision.cursorTop}; 光标头=${decision.cursorHeadUs}us; " +
                        "$disposition; 结果=$finalResult"
                )
            }
            runCatching {
                currentSpeechAudit?.let { UsageLog.attachSpeechAudit(utteranceId, it.json(currentDecisionDisposition)) }
            }.onFailure { Log.w(TAG, "本句诊断记录失败，不影响识别", it) }
            currentSpeechAudit = null
            currentAudioDecision = null
            currentNumberRequest = null
            currentDecisionDisposition = null
            currentDecisionLabel = null
        }
        // 2026-10-03：单句反馈留在胶囊/记录，不再推进主页完成态或安排1.2秒回切。
    }

    private fun handleRecognized(text: String, utteranceId: String = "") {
        Log.i(TAG, "识别结果[$utteranceId]: $text")
        updateNotification("🔴 会话中 · 你说：${text.take(15)}")
        // 使用记录（v0.57.0）：每句识别原文先落一条，本句后续 lastMatch 赋值自动关联同条；
        // 未触发操作的句子（语气词/未命中）显示「未触发操作」——用户可对出「说了什么被听成什么」。
        // v0.58：带 utteranceId 关联（同文本连说两遍不再串条目）
        UsageLog.appendHeard(text, utteranceId)

        // 长按待命模式优先：数字→长按编号 / 中间→长按屏幕 / 退出→取消长按（不结束会话）
        if (longPressMode) {
            handleLongPressMode(text)
            return
        }

        // 安全红线 1：原文直接含「退出」→ 无条件释放，不依赖匹配层
        if (text.contains(CMD_EXIT)) {
            releaseAndStop("识别到「退出」")
            return
        }

        // 听写模式（v0.40.0）：本句为听写内容——写进输入框后自动回到普通命令聆听。
        // 放在语气词过滤之前：用户说的内容原样落笔（含语气词），只让路给「退出」红线。
        // 意图分类已提取到 CommandRouting.planDictationContent（2026-09-29 收尾项3，纯函数）；
        // 此处只保留执行与文案。
        if (dictationMode) {
            handler.removeCallbacks(dictationTimeoutRunnable)
            dictationMode = false
            when (val dp = CommandRouting.planDictationContent(text)) {
                is CommandRouting.DictationContentDecision.CancelDictation -> {
                    VoiceControlService.updateBar("🎤 已取消输入")
                    SessionState.lastMatch = "→ 已取消输入"
                    return
                }
                is CommandRouting.DictationContentDecision.ContinueDictation -> {
                    // v0.56.30：内容句说了「输入」→ 几乎总是想继续听写（而非打字面词）——
                    // 重新武装听写，不打字面
                    dictationMode = true
                    handler.removeCallbacks(dictationTimeoutRunnable)
                    handler.postDelayed(dictationTimeoutRunnable, 12_000L)
                    VoiceControlService.updateBar("✍️ 继续听写（说完停顿即填入）")
                    SessionState.lastMatch = "→ 继续听写"
                    return
                }
                is CommandRouting.DictationContentDecision.EditInDictation -> {
                    // v0.56.25 连环坑：内容句恰好是文字编辑命令（删除/清空/光标移动）→ 按编辑执行，
                    // 不作为文字落笔。此处只报文案；**执行走下方正常命令链**（strict 必中该编辑词）——
                    // 保持既有 fall-through 语义，勿加 return
                    VoiceControlService.updateBar("✂️ 编辑（听写中）：${dp.word}")
                    SessionState.lastMatch = "→ 听写中执行编辑：${dp.word}"
                }
                is CommandRouting.DictationContentDecision.InsertText -> {
                    // 常用词纠错（v0.42.0）：识别原文里与常用词拼音相近的片段改写为常用词（只作用听写内容）
                    val corrected = CustomVocab.correctText(dp.text, CustomVocab.all(applicationContext))
                    if (corrected != dp.text) Log.i(TAG, "听写纠错: [${dp.text}] -> [$corrected]")
                    val ok = VoiceControlService.textInsert(corrected)
                    VoiceControlService.updateBar(if (ok) "✍️ 已输入：${corrected.take(12)}" else "⚠️ 未找到输入框")
                    SessionState.lastMatch = if (ok) "→ 输入「$corrected」✅" else "→ 未找到输入框"
                    if (ok) {
                        noteUserActionDispatched()   // 输入框内容已变，旧重复的点击目标可能失效
                        lastInsertAt = SystemClock.elapsedRealtime()
                        vibrateFeedback()
                    }
                    return
                }
            }
        }

        // 纯语气词：静默忽略，横条保持当前状态继续聆听（商用原则：不把误识别展示给用户）
        if (text in NOISE_WORDS) {
            Log.i(TAG, "忽略语气词: [$text]")
            return
        }

        // 整句「点击屏幕」= 轻点屏幕中心（v0.57.2 用户拍板）。**不入词表别名**：
        // contains 双向规则会让 VAD 断句截出的「点击」二字（「点击屏幕」的前缀子串）误触轻点，
        // 也会劫持「点击X」文字点击；这里只认整句等值，多说一个字都不触发（用户拍板：宁严勿误）
        if (text.trim() == "点击屏幕") {
            Log.i(TAG, "整句点击屏幕直通: -> 轻点")
            dispatchMatched(CommandMatcher.Match("tap", "tap", "basic_navigation", "轻点", "exact"))
            return
        }

        // 「继续」：看门狗延期——**仅预警期有效**（2026-09-14 裘晨阳报 bug：没到预警说继续
        // 也会扣延期名额并重置 5 分钟计时=提前浪费。修复：预警未出现时明确拒绝并告知还差多久）
        if (text.contains(CMD_CONTINUE)) {
            if (!sleepWarned) {
                val waitText = if (segmentStartElapsed != 0L) {
                    val remainSec = ((segmentStartElapsed + WATCHDOG_MILLIS - WARN_BEFORE_MILLIS -
                        SystemClock.elapsedRealtime()) / 1000).coerceAtLeast(0)
                    val m = remainSec / 60
                    if (m > 0) "${m} 分 ${remainSec % 60} 秒" else "${remainSec} 秒"
                } else null
                Log.i(TAG, "CONTINUE_EARLY 「继续」被拒：预警未出现${waitText?.let { "（距预警还有 $it）" } ?: ""}")
                VoiceControlService.updateBar(
                    if (waitText != null) "⏳ 还没到续期时间（$waitText 后提醒）" else "⏳ 还没到续期时间"
                )
                SessionState.lastMatch = "→ 「继续」太早，预警出现后再说${waitText?.let { "（还有 $it）" } ?: ""}"
                handler.removeCallbacks(barResetRunnable)
                handler.postDelayed(barResetRunnable, 1500L)
                return
            }
            handleExtendSession()
            // 横条稍后恢复为「正在聆听」
            handler.removeCallbacks(barResetRunnable)
            handler.postDelayed(barResetRunnable, 1500L)
            return
        }

        // 听写触发（v0.40.0「输入/听写」；v0.55.12 扩容触发词+短句拼音容错，见 DictationTriggers）：
        // 下一句识别原文直接写入输入框（小米式短听写）。
        // v0.57.12 在册命令优先（用户拍板「删除是正式命令，绝不能因近音被听写抢走」）：
        // 整词精确命中词表命令的句子永远按命令走，不进听写考场——容差边界再怎么调都不可能
        // 劫持正式命令（v0.57.10「删除」踩线反例治本）。注意只认 exact：contains 不算
        // （「输入」被「清空输入」contains 命中，但不能因此拦掉真听写）。
        // 综合判定已提取为 shouldArmDictation（2026-09-29 收尾项3，纯函数）。
        // v0.57.10 输入框在场前置（用户拍板「识别到对话框才能说打字」）：无「可见可交互」输入框时
        // **整句静默**——实测「输入」会掉进 contains 反向匹配被「清空输入」劫持、
        // 执行失败报「未找到输入框」；无框页面说触发词没有任何合理意图。
        if (shouldArmDictation(text, currentMatcher())) {
            if (!VoiceControlService.hasVisibleEditable()) {
                Log.i(TAG, "听写触发但屏幕无输入框，整句静默: [$text]")
                return
            }
            dictationMode = true
            handler.removeCallbacks(dictationTimeoutRunnable)
            handler.postDelayed(dictationTimeoutRunnable, 12_000L)
            VoiceControlService.updateBar("✍️ 请说出内容，停顿即填入")
            SessionState.lastMatch = "→ 听写中（说完停顿即填入）"
            return
        }

        // ===== 统一判断入口（2026-09-29 全指令收敛轮）=====
        // 候选+参数+当前模式/页面条件+歧义全部在 CommandRouting.planCommand 一处判定——
        // 生产与离线回归（ProductionRoutingCorpusTest/ParameterRecoveryTest 等用例）调用
        // 同一函数，不存在只供测试的第二套路由。此处只保留执行调度与用户反馈；
        // 改判定次序必须改 planCommand 并跑全量语料回归。
        val planCtx = CommandRouting.UtteranceContext(
            gridShowing = VoiceControlService.isGridShowing(),
            labelsVisible = VoiceControlService.isLabelsVisible(),
            lastActionPresent = lastAction != null,
            visibleLabelCount = if (VoiceControlService.isLabelsVisible())
                VoiceControlService.visibleLabelCount() else null,
            gridCellCount = VoiceControlService.GRID_COLS * VoiceControlService.GRID_ROWS,
        )
        val plan = CommandRouting.planCommand(text, planCtx, currentMatcher())
        currentSpeechAudit?.planned(plan)
        when (plan) {
            is CommandRouting.Decision.Replace -> {
                // 替换（v0.41.0）：精确找词 → 拼音模糊滑窗（治照读屏幕错字被听岔）。找不到原词不静默
                val cur = VoiceControlService.currentEditableText()
                if (cur == null) {
                    VoiceControlService.updateBar("⚠️ 未找到输入框")
                    SessionState.lastMatch = "→ 未找到输入框"
                } else {
                    val range = if (cur.contains(plan.find)) {
                        IntRange(cur.indexOf(plan.find), cur.indexOf(plan.find) + plan.find.length - 1)
                    } else {
                        CommandMatcher.findFuzzyRange(cur, plan.find)
                    }
                    if (range == null) {
                        VoiceControlService.updateBar("⚠️ 输入框中没有「${plan.find}」")
                        SessionState.lastMatch = "→ 没有找到「${plan.find}」"
                    } else {
                        val ok = VoiceControlService.textReplaceRange(range.first, range.last + 1, plan.replacement)
                        if (ok) noteUserActionDispatched()   // 输入框内容已变，旧重复的点击目标可能失效
                        VoiceControlService.updateBar(if (ok) "🔁 已替换为「${plan.replacement}」" else "🎤 识别：$text")
                        SessionState.lastMatch = if (ok) "→ 把「${plan.find}」替换为「${plan.replacement}」✅" else "→ 替换失败"
                        if (ok) vibrateFeedback()
                    }
                }
            }
            is CommandRouting.Decision.Repeat -> {
                if (plan.recovered) {
                    DiagnosticsHelper.log("重复恢复[$utteranceId]: $text -> ${plan.times} 次; 编号=${planCtx.labelsVisible}, 网格=${planCtx.gridShowing}")
                }
                handleRepeat(plan.times, utteranceId)
            }
            is CommandRouting.Decision.GridLongPress -> {
                val ok = VoiceControlService.longPressGridCell(plan.cell)
                if (ok) {
                    noteUserActionDispatched()   // 新命令取消旧重复（防旧点击在新页面继续执行）
                    VoiceControlService.lastGridTapPoint?.let { p ->
                        lastAction = LastAction.LongPressPoint(p.first, p.second)
                    }
                }
                VoiceControlService.updateBar(if (ok) "⚡ 长按第 ${plan.cell} 格" else "🎤 识别：$text")
                SessionState.lastMatch = if (ok) "→ 长按第 ${plan.cell} 格 ✅" else "→ 长按第 ${plan.cell} 格"
            }
            is CommandRouting.Decision.GridTapCell -> {
                if (plan.restored) {
                    DiagnosticsHelper.log("编号恢复[$utteranceId]: $text -> 第 ${plan.cell} 格（网格范围=${planCtx.gridCellCount}）")
                }
                val note = if (plan.restored) "（编号校正）" else ""
                val ok = VoiceControlService.tapGridCell(plan.cell)
                if (ok) {
                    noteUserActionDispatched()
                    // 记住网格点击的落点，「重复一次」可在同一位置再点（用户明确指令，非自动重试）
                    VoiceControlService.lastGridTapPoint?.let { p ->
                        lastAction = LastAction.TapPoint(p.first, p.second)
                    }
                }
                VoiceControlService.updateBar(if (ok) "⚡ 点击第 ${plan.cell} 格$note" else "⚠️ 第 ${plan.cell} 格未执行")
                SessionState.lastMatch = if (ok) "→ 点击第 ${plan.cell} 格 ✅$note" else "→ 点击第 ${plan.cell} 格：未执行$note"
            }
            is CommandRouting.Decision.GridZoom -> {
                val ok = VoiceControlService.zoomGrid(plan.cell)
                if (ok) noteUserActionDispatched()   // 缩放改变网格层级，旧重复的点位已失效
                VoiceControlService.updateBar(if (ok) "⚡ 缩放到第 ${plan.cell} 格" else "⚠️ 已到最小格，无法再缩")
                SessionState.lastMatch = if (ok) "→ 缩放到第 ${plan.cell} 格 ✅" else "→ 已到最小格"
            }
            is CommandRouting.Decision.TapNumber -> {
                if (plan.restored) {
                    DiagnosticsHelper.log("编号恢复[$utteranceId]: $text -> 编号 ${plan.number}（范围=${planCtx.visibleLabelCount}）")
                }
                var note = if (plan.restored) "（编号校正）" else ""
                // 数字复核（nn 轮 2026-09-30 路径①）：文字选到了存在但错误的编号时，在首次
                // tapLabel 之前用数字头纠正（独立验收 7 纠正/0 误伤/0 负例触发/0 新增错误执行）。
                // 2026-10-01：4/10/other专用头仅补旧数字头弃权，≥.995且领先≥.99才改号。
                // 冻结新验收新增1个原音/条件的4→10救回、0新增改错；尚不是用户真人效果证明。
                // 失败/未加载/低分→保持文字编号（回退语义）；失败后不补点（幂等红线）。
                val result = numberTapDispatcher.dispatch(plan.number, text, currentAudioDecision?.numTop, currentNumberRequest,
                    NumberReviewContext.Live("$sessionTag-u$utteranceSeq", sessionGeneration,
                        AudioDecision.isEnabled(this), recording && !stopRequested,
                        !dictationMode && !captureArmed && !longPressMode,
                        VoiceControlService.numberReviewSnapshot()), pairAudio = currentAudioDecision?.numPairTop,
                    confirmation = currentAudioDecision?.numSegmentTop,
                    tap = VoiceControlService::tapLabel)
                if (!result.attempted) {
                    currentSpeechAudit?.rejected(result.rejection ?: "number_not_attempted")
                    return
                }
                currentSpeechAudit?.dispatched(SpeechAudit.Route("tap_number", result.number), result.dispatched, result.rejection)
                val tapTarget = result.number
                result.correction?.let { correction ->
                    note = "（编号复核：${correction.fromNumber}→${correction.toNumber}）"
                    currentDecisionLabel = "已修改为点击编号 ${correction.toNumber}"
                    currentDecisionDisposition = "数字改号：${correction.fromNumber}→${correction.toNumber}"
                    DiagnosticsHelper.log("数字复核[$utteranceId]: $text ${correction.fromNumber}→${correction.toNumber}")
                }
                result.rejection?.let { DiagnosticsHelper.log("数字复核拒绝[$utteranceId]: $it") }
                if (result.correction == null && result.rejection?.startsWith("number_") == true) {
                    currentDecisionLabel = "声音有分歧或无把握，保留编号 $tapTarget"
                    currentDecisionDisposition = "数字改号未采用：${result.rejection}；实际编号=$tapTarget"
                }
                val ok = result.dispatched
                if (ok) {
                    noteUserActionDispatched()
                    lastAction = LastAction.TapLabel(tapTarget)
                }
                VoiceControlService.updateBar(if (ok) "⚡ 点击编号 $tapTarget$note" else "⚠️ 编号 $tapTarget 未执行")
                SessionState.lastMatch = if (ok) "→ 点击编号 $tapTarget ✅ 已执行$note" else "→ 点击编号 $tapTarget：未执行$note"
            }
            is CommandRouting.Decision.LongPressNumber -> {
                // 2026-09-30：doLongPressLabel 已同步化（越界/无窗口同步返回 false），
                // 失败反馈对齐编号点击口径（未执行），不再显示「识别原文」误导；
                // 成功后登记长按点位为「上一个动作」——否则「重复一次」会误放更早的动作
                val ok = VoiceControlService.longPressLabel(plan.number)
                if (ok) {
                    noteUserActionDispatched()
                    VoiceControlService.lastLongPressPoint?.let { p ->
                        lastAction = LastAction.LongPressPoint(p.first, p.second)
                    }
                }
                VoiceControlService.updateBar(if (ok) "⚡ 长按编号 ${plan.number}" else "⚠️ 长按编号 ${plan.number} 未执行")
                SessionState.lastMatch = if (ok) "→ 长按编号 ${plan.number} ✅" else "→ 长按编号 ${plan.number}：未执行"
            }
            is CommandRouting.Decision.LongPressText -> {
                // 目标先经 App 名热词纠错，再长按文字；失败回退精确/模糊命令（executor 路径）
                val target = currentMatcher().resolveClosest(plan.target, APP_HOTWORDS) ?: plan.target
                if (VoiceControlService.longPressText(target)) {
                    noteUserActionDispatched()
                    // 2026-09-30：成功登记长按点位（重复回放同一点位，不再误放更早动作）
                    VoiceControlService.lastLongPressPoint?.let { p ->
                        lastAction = LastAction.LongPressPoint(p.first, p.second)
                    }
                    VoiceControlService.updateBar("⚡ 长按「$target」")
                    SessionState.lastMatch = "→ 长按「$target」 ✅"
                } else {
                    // 长按文字失败 → 回退精确命令（「按住不动」这类无参长按）
                    val strict = currentMatcher().matchStrictDetailed(text)
                    if (strict.ambiguous) {
                        Log.i(TAG, "同分歧义，忽略: [$text] 冲突词=${strict.tiedWords}")
                        DiagnosticsHelper.log("同分歧义已忽略: $text（${strict.tiedWords.joinToString("/")}）")
                        persistMiss(text)
                    } else if (strict.match != null) {
                        Log.i(TAG, "匹配: [$text] -> ${strict.match.matchedWord} (${strict.match.method})")
                        dispatchMatched(strict.match)
                    } else {
                        val fuzzy = matchFuzzySafely(text)
                        if (fuzzy != null) {
                            Log.i(TAG, "匹配: [$text] -> ${fuzzy.matchedWord} (${fuzzy.method})")
                            dispatchMatched(fuzzy)
                        } else {
                            // 长按文字没找到：同样静默忽略（商用原则）
                            Log.i(TAG, "长按未命中，忽略: [$text]")
                            DiagnosticsHelper.log("长按未命中: $text")
                        }
                    }
                }
            }
            is CommandRouting.Decision.DispatchCommand -> {
                Log.i(TAG, "匹配: [$text] -> ${plan.matchedWord} (${plan.method})")
                dispatchMatched(plan.match)
            }
            is CommandRouting.Decision.TapText -> {
                // 目标先经 App 名热词纠错（「抖婴」→「抖音」），再按屏幕文字点击
                val target = currentMatcher().resolveClosest(plan.target, APP_HOTWORDS) ?: plan.target
                val tapStatus = VoiceControlService.tapTextDetailed(target)
                currentSpeechAudit?.textTap(tapStatus)
                if (tapStatus == SilentCommandRecovery.TextTapStatus.DISPATCHED) {
                    noteUserActionDispatched()
                    VoiceControlService.updateBar("⚡ 点击「$target」")
                    SessionState.lastMatch = "→ 点击「$target」 ✅"
                    // 文字点击成功也登记为可重复（v0.55）：媒体播放器里「暂停」就是文字点击
                    VoiceControlService.lastTextTapPoint?.let { p ->
                        lastAction = LastAction.TapPoint(p.first, p.second)
                    }
                } else {
                    // 屏幕上没找到该文字 → 模糊兜底（executor 回退路径，不在 planCommand）
                    val fuzzyResult = matchFuzzyOutcomeSafely(text)
                    // 2026-10-01：完整“数字+号”在文字目标不存在时恢复编号语义。
                    // 标准显示编号的模糊误路由可替换；绑定/其他动作/歧义不抢。
                    if (tryNumberSuffixRecovery(plan, text, utteranceId, tapStatus, fuzzyResult)) return
                    val fuzzy = fuzzyResult.match.takeUnless { fuzzyResult.ambiguous }
                    if (fuzzy != null) {
                        Log.i(TAG, "匹配: [$text] -> ${fuzzy.matchedWord} (${fuzzy.method})")
                        dispatchMatched(fuzzy)
                    } else {
                        if (tryM6Recovery(plan, text, utteranceId, tapStatus, fuzzyResult)) return
                        if (tryNumberRecovery(plan, text, utteranceId, tapStatus, fuzzyResult)) return
                        // 屏幕文字没找到：不操作也不提示，安静继续聆听（只记日志供诊断）。
                        // 商用原则：误识别不展示给用户
                        Log.i(TAG, "未命中，忽略: [$text]")
                        DiagnosticsHelper.log("文字未命中: $text")
                    }
                }
            }
            is CommandRouting.Decision.Ambiguous -> {
                currentSpeechAudit?.rejected("ambiguous")
                if (plan.original != null && plan.alternative != null) {
                    // 编号候选歧义（「点七六」范围内 76 与 6 都合法）：明确反馈并结束本句，
                    // 绝不落入文字点击/模糊匹配重新猜目标（原 resolveTapForExecution 语义）
                    DiagnosticsHelper.log("编号歧义[$utteranceId]: $text -> ${plan.original}/${plan.alternative}; 范围=${plan.range}")
                    VoiceControlService.updateBar("⚠️ 编号不明确，请说「第${plan.alternative}个」或完整编号")
                    SessionState.lastMatch = "→ 编号 ${plan.original}/${plan.alternative} 不明确，未执行"
                    handler.removeCallbacks(barResetRunnable)
                    handler.postDelayed(barResetRunnable, 1500L)
                    return
                }
                // 词表同分歧义：不按词表顺序替用户拍板，也不许文字点击/拼音模糊重猜。静默忽略留痕。
                Log.i(TAG, "同分歧义，忽略: [$text] 冲突词=${plan.tiedWords}")
                DiagnosticsHelper.log("同分歧义已忽略: $text（${plan.tiedWords.joinToString("/")}）")
                persistMiss(text)
            }
            is CommandRouting.Decision.NoMatch -> {
                // 模糊兜底（executor 回退路径）：仍无命中 → 静默继续聆听
                val fuzzyResult = matchFuzzyOutcomeSafely(text)
                val fuzzy = fuzzyResult.match.takeUnless { fuzzyResult.ambiguous }
                if (fuzzy != null) {
                    Log.i(TAG, "匹配: [$text] -> ${fuzzy.matchedWord} (${fuzzy.method})")
                    dispatchMatched(fuzzy)
                } else {
                    if (tryM6Recovery(plan, text, utteranceId,
                            SilentCommandRecovery.TextTapStatus.NOT_ATTEMPTED, fuzzyResult)) return
                    if (tryNumberRecovery(plan, text, utteranceId,
                            SilentCommandRecovery.TextTapStatus.NOT_ATTEMPTED, fuzzyResult)) return
                    // 未命中任何命令：静默继续聆听，不回显 ASR 原文（商用原则）。
                    // 原文进诊断缓冲（v0.55.3），并持久落盘（v0.56.26）积累真实听岔样本
                    Log.i(TAG, "未匹配，忽略: [$text]")
                    DiagnosticsHelper.log("命令未匹配: $text")
                    currentSpeechAudit?.ignored(if (fuzzyResult.ambiguous) "fuzzy_ambiguous" else "complete_chain_no_match")
                    persistMiss(text)
                }
            }
        }

        // 横条稍后恢复为「正在聆听」
        handler.removeCallbacks(barResetRunnable)
        handler.postDelayed(barResetRunnable, 1500L)
    }

    /** 「继续」：看门狗延期（调用方已保证预警期）。带次数上限，防 bug 自动无限延期导致彻底占麦。 */
    private fun handleExtendSession() {
        if (extensionCount >= MAX_EXTENSIONS) {
            VoiceControlService.updateBar("⚠️ 已达最长 $SESSION_MAX_MINUTES 分钟，无法再延长")
            SessionState.lastMatch = "→ 延期已达上限（最多 $MAX_EXTENSIONS 次）"
            return
        }
        extensionCount++
        sleepWarned = false
        segmentStartElapsed = SystemClock.elapsedRealtime()
        Log.i(TAG, "EXTEND 已延期 $extensionCount/$MAX_EXTENSIONS 次")
        handler.removeCallbacks(watchdogRunnable)
        handler.removeCallbacks(warnRunnable)
        handler.postDelayed(watchdogRunnable, EXTEND_MILLIS)
        handler.postDelayed(warnRunnable, EXTEND_MILLIS - WARN_BEFORE_MILLIS)
        val remaining = MAX_EXTENSIONS - extensionCount
        VoiceControlService.updateBar("✅ 已延长 $EXTEND_MINUTES 分钟（还可延长 $remaining 次）")
        SessionState.lastMatch = "→ 已延长，剩余可延期 $remaining 次"
        updateNotification("🔴 会话中 · 已延长（剩余 $remaining 次）")
    }

    // ---------- 参数化提取已全部收敛到 CommandRouting（2026-09-28 迁出，2026-09-29 全指令 ----------
    // 收敛轮起 handleRecognized 主链直接调用 planCommand——此前的同名薄委托已无调用者，
    // 予以删除；正则本体与历史教训注释（点辑四杂音容忍、十八截成8、农夫三字形、四是宽松
    // 数字等）都在 CommandRouting.kt。离线回归语料与产品路由走同一实现，不抄第二套。

    /** 进入长按待命模式：顶部横条提示，等待报数字或「中间」 */
    private fun enterLongPressMode() {
        longPressMode = true
        VoiceControlService.updateBar("🔵 长按模式：说数字编号，或「中间」")
        handler.removeCallbacks(longPressModeRunnable)
        handler.postDelayed(longPressModeRunnable, LONG_PRESS_MODE_TIMEOUT_MS)
    }

    /** 退出长按待命模式（收到数字/「中间」/「退出」或超时） */
    private fun exitLongPressMode() {
        if (!longPressMode) return
        longPressMode = false
        handler.removeCallbacks(longPressModeRunnable)
        // 退出长按模式后，横条稍后恢复「聆听中」（与普通命令一致）
        handler.removeCallbacks(barResetRunnable)
        handler.postDelayed(barResetRunnable, 1500L)
    }

    /** 长按待命模式下处理下一句：数字→长按编号；中间→长按屏幕；退出→取消 */
    /** 长按待命模式下处理下一句：意图分类已提取到 CommandRouting.planLongPressStandby
     *  （2026-09-29 收尾项3，纯函数可 JVM 离线回归）；此处只保留执行。 */
    private fun handleLongPressMode(text: String) {
        when (val plan = CommandRouting.planLongPressStandby(text, VoiceControlService.isGridShowing())) {
            is CommandRouting.StandbyDecision.CancelStandby -> {
                exitLongPressMode()
                VoiceControlService.updateBar("已退出长按模式")
                SessionState.lastMatch = "→ 退出长按模式"
            }
            is CommandRouting.StandbyDecision.EditCommand -> {
                exitLongPressMode()
                VoiceControlService.updateBar("✂️ 取消长按，执行：${plan.word}")
                SessionState.lastMatch = "→ 取消长按，执行编辑：${plan.word}"
                currentMatcher().matchStrict(plan.word)?.let { dispatchMatched(it) }
            }
            is CommandRouting.StandbyDecision.PressCenter -> {
                val ok = VoiceControlService.longPressCenter()
                if (ok) {
                    noteUserActionDispatched()
                    // 2026-09-30：成功登记长按点位（重复回放屏幕中心同一点）
                    VoiceControlService.lastLongPressPoint?.let { p ->
                        lastAction = LastAction.LongPressPoint(p.first, p.second)
                    }
                }
                VoiceControlService.updateBar(if (ok) "⚡ 长按屏幕中间" else "🎤 识别：$text")
                SessionState.lastMatch = if (ok) "→ 长按屏幕中间 ✅" else "→ 长按中间"
                exitLongPressMode()
            }
            is CommandRouting.StandbyDecision.StandbyNumber -> {
                // 网格显示时长按格子（v0.57.20：网格定位说「长按」进待命、报数字归格子），否则长按编号
                val ok = if (plan.gridCell) VoiceControlService.longPressGridCell(plan.number)
                else VoiceControlService.longPressLabel(plan.number)
                if (ok) {
                    noteUserActionDispatched()
                    // 2026-09-30：成功登记点位（格子=lastGridTapPoint / 编号=lastLongPressPoint），
                    // 重复回放同一点位——此前待命内长按成功不登记，重复会误放更早的动作
                    if (plan.gridCell) {
                        VoiceControlService.lastGridTapPoint?.let { p ->
                            lastAction = LastAction.LongPressPoint(p.first, p.second)
                        }
                    } else {
                        VoiceControlService.lastLongPressPoint?.let { p ->
                            lastAction = LastAction.LongPressPoint(p.first, p.second)
                        }
                    }
                }
                val what = if (plan.gridCell) "第 ${plan.number} 格" else "编号 ${plan.number}"
                VoiceControlService.updateBar(if (ok) "⚡ 长按$what" else "🎤 识别：$text")
                SessionState.lastMatch = if (ok) "→ 长按$what ✅" else "→ 长按$what"
                exitLongPressMode()
            }
            is CommandRouting.StandbyDecision.KeepWaiting -> {
                // 其他：保持长按模式，重新计时
                handler.removeCallbacks(longPressModeRunnable)
                handler.postDelayed(longPressModeRunnable, LONG_PRESS_MODE_TIMEOUT_MS)
                VoiceControlService.updateBar("🎤 长按模式：说数字，或「中间」")
            }
        }
    }

    // 仅在本句主线程派发期间有效，绝不复用上一句的音频判决。
    private var currentAudioDecision: AudioDecisionOutcome? = null
    private var currentSpeechAudit: SpeechAudit.Recorder? = null
    private val speechAssetFingerprint: String? by lazy {
        runCatching { assets.open("audio_decision_assets.sha256").use { SpeechAudit.digest(it.readBytes()) } }.getOrNull()
    }
    private fun speechAuditMode(): String = when {
        captureArmed -> "capture"
        longPressMode -> "long_press_standby"
        dictationMode -> "dictation"
        else -> "ordinary"
    }
    private var currentNumberRequest: NumberReviewContext.Request? = null
    private val numberTapDispatcher = NumberTapDispatcher()
    private val numberSuffixRecovery = NumberSuffixRecovery(numberTapDispatcher)
    private val m6Recovery = SilentCommandRecovery()
    private val numberSilentRecovery = NumberSilentRecovery()
    private var currentDecisionDisposition: String? = null
    // M5：用户可读的处置短标签（已复核一致/已纠正/暂时不可用…），与专业详情（disposition）分层
    private var currentDecisionLabel: String? = null

    /** 模糊兜底统一入口（2026-09-29 收尾项2）：同分、不同动作=明确歧义 → 留痕并返回 null，
     *  不再按词表顺序替用户猜动作（旧实现取 JSON 先出现者）；同分自定义绑定优先由
     *  matchFuzzyDetailed 内部保证（v0.39.0 既有规则） */
    private fun matchFuzzySafely(text: String): CommandMatcher.Match? =
        matchFuzzyOutcomeSafely(text).let { if (it.ambiguous) null else it.match }

    private fun matchFuzzyOutcomeSafely(text: String): CommandMatcher.StrictOutcome {
        val outcome = currentMatcher().matchFuzzyDetailed(text)
        if (outcome.ambiguous) {
            Log.i(TAG, "模糊同分歧义，忽略: [$text] 冲突词=${outcome.tiedWords}")
            DiagnosticsHelper.log("模糊同分歧义已忽略: $text（${outcome.tiedWords.joinToString("/")}）")
            persistMiss(text)
            return outcome
        }
        return outcome
    }

    /** 两条旧链静默出口在此会合；不抢已有动作，不替失败手势补发另一动作。 */
    private fun tryM6Recovery(plan: CommandRouting.Decision, text: String, uid: String,
                              tapStatus: SilentCommandRecovery.TextTapStatus,
                              fuzzy: CommandMatcher.StrictOutcome): Boolean {
        val result = m6Recovery.attempt(plan, text, tapStatus, fuzzy,
            SilentCommandRecovery.Context(
                enabled = m6ShadowEnabled && AudioDecision.isEnabled(this),
                active = recording && !stopRequested,
                normalMode = !dictationMode && !captureArmed && !longPressMode,
                uid = uid, latestUid = "$sessionTag-u$utteranceSeq",
                focusedEditable = CursorReviewPolicy.ENABLED && VoiceControlService.hasFocusedEditableForCursor(),
                cursorEnabled = CursorReviewPolicy.ENABLED),
            currentAudioDecision?.m6Top,currentAudioDecision?.cursorTop) { action ->
                dispatchCommand(action,focusedCursorOnly = action in CursorReviewPolicy.ACTIONS)
            } ?: return false
        val note = VoiceControlService.consumeActionNote()
        val actionName = when (result.action) {
            "text_cursor_left" -> "光标左移"
            "text_cursor_right" -> "光标右移"
            else -> "打开最近任务"
        }
        currentDecisionLabel = if (result.dispatched) "声音复核救回" else "声音复核未执行"
        currentDecisionDisposition = "M6救回：旧链无候选 → ${result.action}；派发=${result.dispatched}"
        SessionState.lastMatch = note ?: if (result.dispatched)
            "→ $actionName ✅ 已派发（声音复核）" else "→ ${actionName}未派发（声音复核）"
        VoiceControlService.updateBar(note ?: if (result.dispatched)
            "⚡ $actionName（声音复核）" else "⚠️ ${actionName}未派发")
        DiagnosticsHelper.log("M6救回[$uid]: [$text] ${result.action} dispatched=${result.dispatched}")
        if (result.dispatched) vibrateFeedback()
        handler.removeCallbacks(barResetRunnable)
        handler.postDelayed(barResetRunnable, 1500L)
        return true
    }

    /** 完整“数字+号”语义恢复：文字不存在才参与，不新增编码或IPC请求。 */
    private fun tryNumberSuffixRecovery(plan: CommandRouting.Decision, text: String, uid: String,
                                        tapStatus: SilentCommandRecovery.TextTapStatus,
                                        fuzzy: CommandMatcher.StrictOutcome): Boolean {
        val live = NumberReviewContext.Live("$sessionTag-u$utteranceSeq", sessionGeneration,
            // “数字+号”是既有文字语义恢复，与已移除的声音二审解耦。
            true, recording && !stopRequested,
            !dictationMode && !captureArmed && !longPressMode, VoiceControlService.numberReviewSnapshot())
        val restored = numberSuffixRecovery.attempt(plan, text, tapStatus, fuzzy, currentMatcher(),
            VoiceControlService.isLabelsVisible(), VoiceControlService.isGridShowing(), currentNumberRequest, live,
            currentAudioDecision?.numTop, currentAudioDecision?.numPairTop,
            confirmation = currentAudioDecision?.numSegmentTop, tap = VoiceControlService::tapLabel) ?: return false
        val result = restored.dispatch
        if (!result.attempted) {
            currentSpeechAudit?.rejected(result.rejection ?: "suffix_already_handled")
            return true // 同句已消费，不重复反馈，也不落回模糊派发。
        }
        currentSpeechAudit?.dispatched(SpeechAudit.Route("tap_number", result.number), result.dispatched, result.rejection)
        val number = result.number
        val correction = result.correction
        val note = "（编号语义恢复）" + if (correction != null)
            "（声音复核：${correction.fromNumber}→${correction.toNumber}）" else ""
        if (result.dispatched) {
            noteUserActionDispatched()
            lastAction = LastAction.TapLabel(number)
            vibrateFeedback()
        }
        currentDecisionLabel = if (correction != null) "已纠正" else "未参与"
        currentDecisionDisposition = "编号语义恢复：${restored.textNumber}→$number；" +
            "替换显示编号模糊命中=${restored.replacedShowLabels}；声音改号=${correction != null}；派发=${result.dispatched}"
        SessionState.lastMatch = if (result.dispatched) "→ 点击编号 $number ✅ 已派发$note"
            else "→ 编号 $number 未派发$note"
        VoiceControlService.updateBar(if (result.dispatched) "⚡ 点击编号 $number$note" else "⚠️ 编号 $number 未派发$note")
        DiagnosticsHelper.log("编号语义恢复[$uid]: [$text] ${restored.textNumber}→$number " +
            "replacedShowLabels=${restored.replacedShowLabels} soundCorrection=${correction != null} dispatched=${result.dispatched}")
        handler.removeCallbacks(barResetRunnable)
        handler.postDelayed(barResetRunnable, 1500L)
        return true
    }

    /** 数字静默出口：原数字/文字/模糊/已有声音链全部没动作后才参与。 */
    private fun tryNumberRecovery(plan: CommandRouting.Decision, text: String, uid: String,
                                  tapStatus: SilentCommandRecovery.TextTapStatus,
                                  fuzzy: CommandMatcher.StrictOutcome): Boolean {
        val live = NumberReviewContext.Live("$sessionTag-u$utteranceSeq",sessionGeneration,
            m6ShadowEnabled && AudioDecision.isEnabled(this),recording && !stopRequested,
            !dictationMode && !captureArmed && !longPressMode,VoiceControlService.numberReviewSnapshot())
        val result = numberSilentRecovery.attempt(plan,text,tapStatus,fuzzy,currentNumberRequest,live,
            currentAudioDecision?.numSegmentTop,VoiceControlService::tapLabel) ?: return false
        currentSpeechAudit?.dispatched(SpeechAudit.Route("tap_number", result.number), result.dispatched)
        if (result.dispatched) {
            noteUserActionDispatched()
            lastAction = LastAction.TapLabel(result.number)
        }
        currentDecisionLabel = if (result.dispatched) "声音复核救回" else "声音复核未执行"
        currentDecisionDisposition = "数字救回：完整旧链静默 → ${result.number}；派发=${result.dispatched}"
        SessionState.lastMatch = if (result.dispatched) "→ 点击编号 ${result.number} ✅ 已派发（声音复核）"
            else "→ 编号 ${result.number} 未派发（声音复核）"
        VoiceControlService.updateBar(if (result.dispatched) "⚡ 点击编号 ${result.number}（声音复核）"
            else "⚠️ 编号 ${result.number} 未派发")
        DiagnosticsHelper.log("数字救回[$uid]: [$text] target=${result.number} dispatched=${result.dispatched}")
        if (result.dispatched) vibrateFeedback()
        handler.removeCallbacks(barResetRunnable)
        handler.postDelayed(barResetRunnable,1500L)
        return true
    }

    /** 执行匹配到的命令；含「退出」安全红线。 */
    private fun dispatchMatched(matched: CommandMatcher.Match) {
        currentSpeechAudit?.matched(matched, currentMatcher().isCustomMatch(matched))
        // 2026-09-14 用户日志实锤：闲话「走出」被 pinyin_fuzzy 掰成「退出」→ 会话无辜断开
        // （"用一半自动退出聆听"）。会话终结类命令（退出/锁屏）不接受模糊命中——
        // 同音字仍可退（pinyin_exact），只有"差一个音"这种最易误触的档位对危险命令闭嘴
        if (matched.method == "pinyin_fuzzy" &&
            (matched.action == "exit_session" || matched.action == "lock_screen")
        ) {
            // 2026-09-15 用户日志实锤反面：真「退出」被 ASR 连听成「走出」四次、守卫全拦
            // = 用户被反锁（麦克风占着退不掉，最恶性场景）。升级阶梯：30 秒内近似退出
            // 被拦 3 次判定为真实退出请求、第 3 次放行——闲话不会连说三遍，被困者一定会
            val now = SystemClock.elapsedRealtime()
            fuzzyExitRejects.addLast(now)
            while (fuzzyExitRejects.isNotEmpty() && now - fuzzyExitRejects.first() > 30_000L) {
                fuzzyExitRejects.removeFirst()
            }
            if (matched.action == "exit_session" && fuzzyExitRejects.size >= 3) {
                // v0.56.29 放行前判别：「删除」(shan chu) 与「退出」(tui chu) 尾音同音，
                // 连续快速说删除时起音被 VAD 吃掉、只剩"出"串——拼音不含 tui，
                // 是删除截断而非退出，不放行（真说「退出」走精确匹配红线，永远直通）
                val strike = SessionState.lastText ?: ""
                val strikePinyin = pinyinOf(strike)
                if (!strikePinyin.contains("tui")) {
                    fuzzyExitRejects.clear()
                    DiagnosticsHelper.log("阶梯放行否决：[$strike] 拼音无 tui，判定为删除截断")
                    VoiceControlService.updateBar("🎤 听到的是「删除」的尾音，已忽略（要说退出请说清楚）")
                    SessionState.lastMatch = "→ 连续近似退出被否决（更像删除的尾音）"
                    return
                }
                fuzzyExitRejects.clear()
                Log.w(TAG, "FUZZY_GUARD 升级：30 秒内第 3 次近似退出，按真实退出放行")
                DiagnosticsHelper.log("近似退出连说 3 次，守卫升级放行")
                VoiceControlService.updateBar("🔓 听到连续三次近似退出，已为您退出")
                SessionState.lastMatch = "→ 退出（近似说法连说三次） ✅"
                releaseAndStop("近似退出第 3 次（守卫升级）")
                return
            }
            Log.i(TAG, "FUZZY_GUARD 拒绝模糊命中危险命令：[${SessionState.lastText}] -> ${matched.matchedWord}")
            currentSpeechAudit?.rejected("dangerous_fuzzy_match")
            DiagnosticsHelper.log("模糊命中危险命令已忽略：${SessionState.lastText} ≈ ${matched.matchedWord}")
            if (fuzzyExitRejects.size == 2) {
                // 第二次给出明确指引：被识别困住的用户需要知道出口
                VoiceControlService.updateBar("想退出请说「退出」，或把刚才的说法连说三遍")
            }
            return
        }
        if (matched.action == "exit_session") {
            releaseAndStop("识别到「退出」")
            return
        }
        // 「长按/按住」（无参数）→ 进入长按待命模式（两步式，提升「动词+数字」识别率）
        if (matched.action == "long_press") {
            enterLongPressMode()
            SessionState.lastMatch = "→ 长按模式（说数字或「中间」）"
            return
        }
        // 数字绑定动作（v0.50.0）：执行路径与原生「点击编号 N / 点击第 N 格」完全一致，
        // 含「重复一次」的 lastAction 记录；横条/使用记录口径也对齐原生
        if (matched.action.startsWith("tap_number_")) {
            val n = matched.action.removePrefix("tap_number_").toIntOrNull() ?: return
            val ok = VoiceControlService.tapLabel(n)
            currentSpeechAudit?.dispatched(SpeechAudit.Route("tap_number", n), ok)
            if (ok) {
                noteUserActionDispatched()
                lastAction = LastAction.TapLabel(n)
            }
            VoiceControlService.updateBar(if (ok) "⚡ 点击编号 $n" else "🎤 识别：${SessionState.lastText}")
            SessionState.lastMatch = if (ok) "→ 点击编号 $n ✅ 已执行" else "→ 点击编号 $n"
            if (ok) vibrateFeedback()
            return
        }
        if (matched.action.startsWith("grid_tap_")) {
            val n = matched.action.removePrefix("grid_tap_").toIntOrNull() ?: return
            // 网格没显示时不能点：currentTapPoint 无网格会兜底屏幕中心（原生路径有 gridShowing 前置，此处对齐）
            if (!VoiceControlService.isGridShowing()) {
                VoiceControlService.updateBar("⚠️ 网格未显示，说「显示网格」")
                SessionState.lastMatch = "→ 点击第 $n 格（网格未显示）"
                return
            }
            val ok = VoiceControlService.tapGridCell(n)
            if (ok) {
                noteUserActionDispatched()
                VoiceControlService.lastGridTapPoint?.let { p ->
                    lastAction = LastAction.TapPoint(p.first, p.second)
                }
            }
            VoiceControlService.updateBar(if (ok) "⚡ 点击第 $n 格" else "🎤 识别：${SessionState.lastText}")
            SessionState.lastMatch = if (ok) "→ 点击第 $n 格 ✅" else "→ 点击第 $n 格"
            if (ok) vibrateFeedback()
            return
        }
        // 2026-10-02：真实“增加音量”被dec@0.937反向派发，完整文字及用户绑定不再被二类高分覆盖。
        // 残缺/同音候选仍可按声音改判；共用派发口保证本句只执行一个方向，不自动补发。
        if (matched.action == "volume_up" || matched.action == "volume_down") {
            val enhancedEnabled = AudioDecision.isEnabled(this)
            val dispatch = AudioDecisionRouting.dispatchVolume(SessionState.lastText, matched,
                enhancedEnabled, currentAudioDecision, currentMatcher().isCustomMatch(matched)
            ) { action -> dispatchCommand(action) }
            val resolution = dispatch.resolution
            currentDecisionDisposition = resolution.description
            currentDecisionLabel = resolution.label
            if (resolution.overrideBlocked || resolution.action != matched.action) {
                Log.i(TAG, "AUDIO_DECISION 音量处置：${resolution.description}（原文=${SessionState.lastText}）")
                DiagnosticsHelper.log("音量处置: ${SessionState.lastText} ${resolution.description}")
            }
            val note = VoiceControlService.consumeActionNote()
            val resultText = AudioDecisionRouting.volumeResultText(dispatch, note)
            VoiceControlService.updateBar(AudioDecisionRouting.volumeBarText(dispatch, note))
            SessionState.lastMatch = resultText
            if (dispatch.dispatched) vibrateFeedback()
            return
        }
        // 音量/摇移命令：执行器可能带回附加提示（如「已达安全上限」「桌面不支持摇移」），
        // 优先展示提示，不被通用文案顶掉
        if (matched.action in NOTE_ACTIONS) {
            val ok = dispatchCommand(matched.action)
            val note = VoiceControlService.consumeActionNote()
            if (ok) {
                VoiceControlService.updateBar(note ?: "⚡ 执行：${matched.matchedWord}")
                SessionState.lastMatch = note ?: "→ ${matched.matchedWord} ✅ 已执行"
            } else {
                VoiceControlService.updateBar(note ?: "🎤 识别：${SessionState.lastText}")
                SessionState.lastMatch = note ?: "→ ${matched.matchedWord}"
            }
            return
        }
        // 标准导航的否定/问句守卫放在统一派发口，严格与模糊入口均不能绕过。
        // 拒绝后本句已处理，不再补猜、不派发、不取消正在执行的旧任务。
        val navigationOutcome = NavigationIntentGuard.dispatch(
            // 保留既有文字否定/询问保护，不依赖声音二审开关。
            SessionState.lastText, matched, true,
            !dictationMode && !captureArmed && !longPressMode, currentMatcher().isCustomMatch(matched)
        ) { dispatchCommand(matched.action) }
        if (navigationOutcome.rejection != null) {
            val reason = navigationOutcome.rejection.description
            currentSpeechAudit?.rejected("navigation_${navigationOutcome.rejection.name.lowercase(java.util.Locale.US)}")
            DiagnosticsHelper.log("导航意图保护：[${currentNumberRequest?.uid.orEmpty()}] [${SessionState.lastText}] -> ${matched.action}，$reason")
            VoiceControlService.updateBar("🎤 $reason，未执行导航")
            SessionState.lastMatch = "→ 未执行导航（$reason）"
            handler.removeCallbacks(barResetRunnable)
            handler.postDelayed(barResetRunnable, 1500L)
            return
        }
        val ok = navigationOutcome.dispatched
        VoiceControlService.updateBar(if (ok) "⚡ 执行：${matched.matchedWord}" else "🎤 识别：${SessionState.lastText}")
        SessionState.lastMatch = if (ok) "→ ${matched.matchedWord} ✅ 已执行" else "→ ${matched.matchedWord}"
        if (ok) vibrateFeedback()
    }

    /** 震动反馈（设置页开关，默认关）：命令执行成功短震 30ms——触觉确认对无障碍用户有价值 */
    private fun vibrateFeedback() {
        if (!getSharedPreferences("app", MODE_PRIVATE).getBoolean("vibrate_feedback", false)) return
        val vib = getSystemService(VIBRATOR_SERVICE) as? android.os.Vibrator ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(android.os.VibrationEffect.createOneShot(30, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(30)
            }
        }
    }

    /** 数字同音纠正与解析：v0.55.11 表大扩容并抽到 DigitParser（纯 Kotlin，JVM 单测固化），此处仅委托 */
    private fun normalizeDigitHomophones(s: String): String = DigitParser.normalizeDigitHomophones(s)
    private fun parseChineseNumber(s: String): Int? = DigitParser.parseChineseNumber(s)

    // 重复/替换匹配已迁至 CommandRouting（REPEAT_REGEX 的农夫三字形、loose 兜底窄口径
    // 等历史注释随实现迁移）；本类经上方薄委托调用

    /** 处理「重复 N 次」：校验上限、回放上一次动作。uid=发起句身份（2026-09-30）：
     *  异步结果（完成/停止/替换/中止）按它写回**原句**的使用记录条目，不串到后来的句子。 */
    private fun handleRepeat(times: Int, utteranceId: String = "") {
        when {
            // 2026-09-30 边界修复：「重复0次」当场拒绝——0 次若启动任务会立即耗尽 remaining
            // 且无任何结果事件，记录永久停在「已开始」
            times <= 0 -> {
                VoiceControlService.updateBar("⚠️ 重复次数需至少 1 次")
                SessionState.lastMatch = CommandRouting.RepeatOutcomeText.rejectedZero()
            }
            times > MAX_REPEAT -> {
                VoiceControlService.updateBar("⚠️ 重复最多 $MAX_REPEAT 次")
                SessionState.lastMatch = "→ 重复次数超过上限（最多 $MAX_REPEAT 次）"
            }
            lastAction == null -> {
                VoiceControlService.updateBar("🎤 还没有可重复的动作")
                SessionState.lastMatch = "→ 还没有可重复的动作"
            }
            repeatTask != null -> {
                // 一次只保留一个重复任务；新请求替换尚未执行的次数，不叠加、不并行。
                // 被替换的旧请求按其发起句 uid 如实写回（已派发 X/N），新请求独立记账
                val old = repeatTask
                if (old != null) {
                    repeatRunnable?.let(handler::removeCallbacks)
                    repeatTask = null
                    repeatRunnable = null
                    old.cancel(CommandRouting.RepeatTask.CancelReason.REPLACED_BY_REPEAT)
                }
                repeatLastAction(times, utteranceId)
                VoiceControlService.updateBar("🔁 已替换剩余重复次数：$times 次")
                SessionState.lastMatch = "→ 新请求替换上一轮未执行部分，重复 $times 次"
            }
            else -> {
                repeatLastAction(times, utteranceId)
                VoiceControlService.updateBar("⚡ 重复 $times 次")
                // 2026-09-29 执行反馈分层：回放是异步链，派发瞬间没有「已全部执行」的证据——
                // 不冒充 ✅；完成时按 uid 写回「已全部派发」（2026-09-30 口径），失败写回「已停止」
                SessionState.lastMatch = CommandRouting.RepeatOutcomeText.started(times)
            }
        }
    }

    /** 一次「重复 N 次」的异步回放任务（状态机在 CommandRouting.RepeatTask，生产/测试同一实现）：
     *  repeatRunnable=驱动它的队列句柄（取消时 removeCallbacks 用）。 */
    private var repeatTask: CommandRouting.RepeatTask? = null
    private var repeatRunnable: Runnable? = null

    /** 用户派发了新的可执行动作（2026-09-30 收尾轮用户拍板策略）：取消旧重复任务的剩余
     *  次数并按旧任务 uid 记「被新命令中止（已派发 X/N）」——重点防旧点击在新页面继续执行；
     *  不自动补点/重试。「退出」仍立即结束（releaseAndStop 记「会话结束」）；新重复请求
     *  替换旧请求走 REPLACED_BY_REPEAT 口径。 */
    private fun noteUserActionDispatched() {
        val task = repeatTask ?: return
        repeatRunnable?.let(handler::removeCallbacks)
        repeatTask = null
        repeatRunnable = null
        task.cancel(CommandRouting.RepeatTask.CancelReason.NEW_COMMAND)
    }

    /** 串行回放上一次动作 times 次（每次间隔，避免手势冲突；绕过熔断/冷却因为是明确指令）。
     *  状态机=CommandRouting.RepeatTask（迟到横条抑制/计数/文案/终态在生产与测试同一实现）；
     *  间隔按动作类型取安全串行值（长按 750ms，2026-09-30 冲突修复）。 */
    private fun repeatLastAction(times: Int, utteranceId: String) {
        val la = lastAction ?: return
        val host = object : CommandRouting.RepeatTask.Host {
            override fun dispatchRepeatAction(): Boolean = when (la) {
                is LastAction.Command -> if (la.focusedCursorOnly)
                    VoiceControlService.executeRecoveredCursor(la.action) else VoiceControlService.execute(la.action)
                is LastAction.TapLabel -> VoiceControlService.tapLabel(la.number)
                is LastAction.TapPoint -> {
                    Log.i(TAG, "重复：原坐标再点 (${la.x.toInt()},${la.y.toInt()})")
                    VoiceControlService.tapAtPoint(la.x, la.y)
                }
                is LastAction.LongPressPoint -> {
                    Log.i(TAG, "重复：原坐标再长按 (${la.x.toInt()},${la.y.toInt()})")
                    VoiceControlService.longPressAtPoint(la.x, la.y)
                }
            }
            override fun recordOutcome(uid: String, text: String) { UsageLog.updateOutcome(uid, text) }
            override fun showBar(text: String) { VoiceControlService.updateBar(text) }
            override fun currentUtteranceSeq(): Int = utteranceSeq
            override fun sessionActive(): Boolean = recording
        }
        // 2026-09-30 收尾漏洞②：startSeq 用**发起句**的序号（从 utteranceId 解析，格式
        // "g{gen}-{rand}-u{seq}"）——不重新读全局 utteranceSeq（识别线程可能已因下一句
        // 识别而递增；若误用已增长值，本句的迟到失败会顶掉下一句的胶囊）
        val startSeq = utteranceId.substringAfterLast("-u").toIntOrNull() ?: utteranceSeq
        val task = CommandRouting.RepeatTask(utteranceId, times, startSeq, host)
        val runnable = object : Runnable {
            override fun run() {
                if (repeatTask !== task) return   // 已被新命令/新重复/会话结束取消：迟到回调拒绝执行
                val again = task.step()
                if (!again) {
                    // 2026-09-30 收尾漏洞①：任务终结（完成/失败/会话中止）→ 清除句柄。
                    // 状态机已进终态（后续 cancel 静默返回），此处再清句柄双保险——
                    // 之后的新命令/新重复/退出不会看到旧任务，已定结果不被改写
                    if (repeatTask === task) {
                        repeatTask = null
                        repeatRunnable = null
                    }
                    return
                }
                // 长按手势时长 650ms > 默认间隔 620ms：长按类取安全串行间隔，防止
                // 下一次派发打断未完成的长按（派发成功≠手势完成，750ms 只降风险不是完成证明）
                val interval = CommandRouting.repeatIntervalMs(
                    la is LastAction.LongPressPoint, REPEAT_INTERVAL_MS,
                    VoiceControlService.LONG_PRESS_DURATION_MS)
                handler.postDelayed(this, interval)
            }
        }
        repeatTask = task
        repeatRunnable = runnable
        handler.post(runnable)
    }

    /**
     * 把动作派发到无障碍服务执行，带冷却与熔断保护。
     * @return 是否真正派发执行
     */
    private fun dispatchCommand(action: String, focusedCursorOnly: Boolean = false): Boolean {
        // 无障碍服务未开启 → 无法执行；横条同步告知原因（商用级：失效必须可感知，不能静默）
        if (!VoiceControlService.isReady()) {
            currentSpeechAudit?.dispatched(SpeechAudit.Route(action), false, "accessibility_unavailable")
            Log.w(TAG, "无障碍服务未开启，无法执行：$action")
            SessionState.status = "无障碍服务被关闭，请回到应用重新开启"
            VoiceControlService.updateBar("⚠️ 无障碍已关闭，动作无法执行")
            return false
        }

        val now = SystemClock.elapsedRealtime()

        // 冷却：同一命令冷却期内不重复执行（先判断；被冷却跳过的动作不计入熔断，避免正常快速操作被误判成循环）
        val last = lastExecTime[action] ?: 0L
        if (now - last < COOLDOWN_MS) {
            currentSpeechAudit?.dispatched(SpeechAudit.Route(action), false, "cooldown")
            Log.i(TAG, "命令冷却中，跳过：$action")
            return false
        }

        // 熔断：时间窗内「真正执行」次数过多 → 判定误循环，释放麦克风
        while (execHistory.isNotEmpty() && now - execHistory.first() > CIRCUIT_WINDOW_MS) {
            execHistory.removeFirst()
        }
        execHistory.addLast(now)
        if (execHistory.size >= CIRCUIT_MAX_EXEC) {
            currentSpeechAudit?.dispatched(SpeechAudit.Route(action), false, "circuit_breaker")
            DiagnosticsHelper.log("熔断触发：${CIRCUIT_WINDOW_MS / 1000}s 内 ${execHistory.size} 次")
            releaseAndStop("连续误触发，熔断保护")
            return false
        }
        lastExecTime[action] = now

        val ok = if (focusedCursorOnly) VoiceControlService.executeRecoveredCursor(action)
            else VoiceControlService.execute(action)
        currentSpeechAudit?.dispatched(SpeechAudit.Route(action), ok, if (ok) null else "executor_rejected")
        Log.i(TAG, "执行动作：$action -> $ok")
        if (ok && action != "exit_session") {
            // v0.39.1：全部派发动作可被「重复」回放（白名单已废止，防"新功能忘登记"复发）。
            // exit_session 防御性排除：退出走红线直达，本处本就到不了，双保险
            // v0.57.21 轻点点中网格格心时记成点位动作（日志实锤：不记点位则「重复」重放
            // 轻点命令、网格已清 → 落到屏幕几何中心；记点位后重复=原位再点）
            if (action == "tap" && VoiceControlService.tapHitGridCenter) {
                VoiceControlService.lastGridTapPoint?.let { p ->
                    lastAction = LastAction.TapPoint(p.first, p.second)
                    Log.i(TAG, "轻点命中网格格心，可重复动作记为点位 (${p.first.toInt()},${p.second.toInt()})")
                } ?: run { lastAction = LastAction.Command(action) }
            } else {
                lastAction = LastAction.Command(action,focusedCursorOnly)
                Log.i(TAG, "可重复动作已记录：$action")
            }
            // 2026-09-30 收尾轮用户拍板：用户派发了新的可执行命令 → 取消旧重复任务剩余次数
            //（防旧点击在新页面继续执行；重复回放不经 dispatchCommand，不会自杀）
            noteUserActionDispatched()
        }
        return ok
    }

    /** 会话期间屏幕常亮锁（v0.57.6 用户拍板方案 B）：
     *  语音操作不重置系统无操作息屏计时（注入手势也不算真人操作），看抖音非播放页 1 分钟灭屏，
     *  语音用户被迫反复唤醒。会话真正开始（识别线程提交）时获取，releaseAndStop 统一释放——
     *  说退出/看门狗到期/锁屏自动释放/熔断全走那条路，锁随会话同生共死，后台绝无残留；
     *  亮屏时长被会话硬顶（25 分钟）天然封顶，不违反「不长时间强制亮屏」红线（防电量耗尽无法求救）。
     *  双通道（2026-09-22 真机实证，缺一不可）：
     *  ① IslandBar 胶囊窗口 FLAG_KEEP_SCREEN_ON——本机（HyperOS）实测生效，会话中可见
     *     SCREEN_BRIGHT 锁 WorkSource=本应用，退出即释放（SIMULATE 短会话时序下偶发不挂，
     *     故不能单靠它）；② 本 SCREEN_BRIGHT_WAKE_LOCK——deprecated 但多 ROM 仍认，
     *     runCatching 包裹失败无害，作跨机型兜底。 */
    private var screenLock: android.os.PowerManager.WakeLock? = null
    private fun acquireScreenLock() {
        if (screenLock?.isHeld == true) return
        runCatching {
            val pm = getSystemService(android.os.PowerManager::class.java) ?: return
            screenLock = pm.newWakeLock(
                android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
                "VoiceControl:SessionKeepScreen"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.i(TAG, "SCREEN_KEEP 会话常亮已开启")
        }
    }
    private fun releaseScreenLock() {
        runCatching {
            screenLock?.takeIf { it.isHeld }?.let {
                it.release()
                Log.i(TAG, "SCREEN_KEEP 会话常亮已释放")
            }
        }
    }

    private fun releaseAndStop(reason: String) {
        DiagnosticsHelper.log("会话结束: $reason")
        // 飞行记录仪（v0.51.0）：每次退出严格留痕。底层日志永远记；
        // 使用记录（用户可见+随导出走）只记真实会话——带时长与延期数，「时间没到就断」一眼可辨
        if (sessionStartElapsed != 0L) {
            val durSec = (SystemClock.elapsedRealtime() - sessionStartElapsed) / 1000
            val durText = if (durSec >= 60) "${durSec / 60} 分 ${durSec % 60} 秒" else "$durSec 秒"
            Log.i(TAG, "SESSION_END reason=$reason dur=${durSec}s ext=$extensionCount/$MAX_EXTENSIONS")
            SessionState.lastMatch = "→ 会话结束：$reason（本次 $durText · 延期 $extensionCount/$MAX_EXTENSIONS 次）"
            sessionStartElapsed = 0L
            getSharedPreferences("app", MODE_PRIVATE).edit().remove(KEY_SESSION_ACTIVE_SINCE).apply()
        } else {
            Log.i(TAG, "SESSION_END reason=$reason（无真实会话上下文，不记使用记录）")
        }
        SessionState.phase = SessionState.Phase.IDLE   // 主页状态卡回到未启动态
        // 安全红线：麦克风立即释放，不依赖 stopSelf() → onDestroy 的异步时序。
        // recognizer/vad 的 native 释放交给 onDestroy 里的后台 teardown——
        // 必须等识别线程真正退出（decode 可能数百 ms），否则主线程此刻 release 会 use-after-free 崩溃。
        stopRequested = true
        recording = false
        releaseMicrophone()
        micReleaseReceipt()   // 释放回执：系统级确认无残留（用户恐惧点闭环，见函数注释）
        handler.removeCallbacks(watchdogRunnable)
        handler.removeCallbacks(warnRunnable)
        // 会话结束取消未完的重复回放，并按发起句 uid 如实写回（已派发 X/N）——
        // 退出/看门狗/锁屏/熔断全走本统一出口，写回按 uid 定位不串句
        repeatTask?.let { task ->
            repeatRunnable?.let(handler::removeCallbacks)
            repeatTask = null
            repeatRunnable = null
            task.cancel(CommandRouting.RepeatTask.CancelReason.SESSION_END)
        }
        longPressMode = false
        handler.removeCallbacks(longPressModeRunnable)
        dictationMode = false
        handler.removeCallbacks(dictationTimeoutRunnable)
        releaseScreenLock()   // 会话常亮（v0.57.6）：所有结束路径（退出/看门狗/锁屏/熔断）汇合于此，必释放
        VoiceControlService.hideBar()
        VoiceControlService.hideLabels()
        VoiceControlService.hideGrid()
        SessionState.status = "已释放（$reason）"
        updateNotification("已释放（$reason）")
        stopSelf()
    }

    /** 后台等待识别线程退出后释放 recognizer/vad，防 native use-after-free（Service 销毁后仍短暂存活几秒） */
    private fun teardownNativeAsync() {
        val t = recordThread
        recordThread = null
        thread(name = "voice-teardown") {
            // 同一把跨实例锁覆盖等待和释放：解码未退出时，新 Service 会在模型创建处等待。
            synchronized(initLock) {
                var interrupted = false
                while (t?.isAlive == true) {
                    try { t.join() } catch (_: InterruptedException) { interrupted = true }
                }
                if (interrupted) Thread.currentThread().interrupt()
                releaseVad()
                releaseRecognizer()
            }
            // 初始化线程的 finally 还会归还一个尚未提交的远程 session，等它把释放排队后再关 executor。
            while (initInFlight) {
                try { Thread.sleep(20L) } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
            val decisionInit = decisionInitThread
            if (decisionInit != null && decisionInit !== Thread.currentThread()) {
                decisionInit.interrupt()
                try {
                    decisionInit.join(AUDIO_DECISION_INIT_TIMEOUT_MS + 2_000L)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                if (decisionInit.isAlive) {
                    Log.w(TAG, "AUDIO_DECISION 初始化线程未及时退出；会话 token 仍隔离，基础识别已释放")
                }
            }
            val session = audioDecisionSession
            audioDecisionSession = null
            releaseRemoteDecision(session)
            // 让队列中已排入的 SHUTDOWN 完成；不打断正在进行的 Binder 调用。
            decisionExecutor.shutdown()
        }
    }

    private fun releaseMicrophone() {
        // 2026-09-15 顺序修正（用户对照实验实锤"释放后小爱仍唤不醒直到清后台"）：
        // 先卸效果器、再停麦克风——部分底层实现中"挂着 AEC 效果的输入通道"会保持打开，
        // 原顺序（先 release 录音再卸效果器）可能让输入通道在效果器层面残留
        detachAudioEffects()   // 前处理效果器先卸载
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
    }

    /**
     * 麦克风释放回执（v0.55.1，用户恐惧点闭环）：释放后向系统查询「本应用名下是否仍有活跃录音」
     * （AudioManager.getActiveRecordingConfigurations，系统级视角、非自证）。
     * 结果写入诊断事件（随导出反馈带走）——"是否百分百释放干净"从口头承诺变成带时间戳的凭据；
     * 万一查到残留，立刻强制再清扫一遍并复查。触发点：每次会话结束（看门狗/退出/锁屏/用户停止）。
     */
    private fun micReleaseReceipt(delayMs: Long = 300L) {
        handler.postDelayed({
            val am = runCatching { getSystemService(android.media.AudioManager::class.java) }.getOrNull()
            val active = runCatching { am?.activeRecordingConfigurations?.size ?: -1 }.getOrDefault(-1)
            when {
                active == 0 -> {
                    Log.i(TAG, "MIC_RELEASE_RECEIPT 本应用已无任何活跃录音（系统级确认）")
                    DiagnosticsHelper.log("麦克风释放回执：系统确认本应用已无活跃录音")
                }
                active > 0 -> {
                    Log.w(TAG, "MIC_RELEASE_RECEIPT 仍有 $active 个活跃录音！强制再清扫")
                    DiagnosticsHelper.log("麦克风释放回执异常：仍有 $active 个活跃录音，强制再清扫")
                    releaseMicrophone()
                    handler.postDelayed({
                        val again = runCatching {
                            am?.activeRecordingConfigurations?.size ?: -1
                        }.getOrDefault(-1)
                        DiagnosticsHelper.log("麦克风二次清扫回执：剩余 $again 个活跃录音")
                        if (again != 0) Log.e(TAG, "MIC_RELEASE_RECEIPT 二次清扫后仍剩 $again")
                    }, 300L)
                }
                else -> DiagnosticsHelper.log("麦克风释放回执：系统查询不可用，已执行 stop+release+效果器分离")
            }
        }, delayMs)
    }

    /** 释放音频前处理效果器（AEC/NS/AGC） */
    private fun detachAudioEffects() {
        runCatching { aecEffect?.release() }; aecEffect = null
        runCatching { nsEffect?.release() }; nsEffect = null
        runCatching { agcEffect?.release() }; agcEffect = null
    }

    /** 兜底清扫（v0.55.1 用户对照实验后加固）：把可能存在的全部音频引用一并释放，幂等可重复调 */
    private fun forceAudioCleanup() {
        detachAudioEffects()
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
    }

    // ===== 回声测试台（开发期专用，商用前移除）=====

    private var echoPlayer: MediaPlayer? = null
    // 硬性超时兜底：即使外部 STOP 丢失，到点也强制停，绝不无限念（深夜噪音事故教训）
    private val echoTimeoutRunnable = Runnable {
        Log.w(TAG, "ECHO_TEST 超时兜底触发，强制停止外放")
        stopEchoTest()
    }

    /** 外放 assets 里的命令词音频（USAGE_MEDIA = 与抖音同通道）；播完自动停 + 超时硬兜底 */
    private fun startEchoTest() {
        if (echoPlayer != null) return
        try {
            val afd = assets.openFd(ECHO_TEST_FILE)
            val mp = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                isLooping = false   // 绝不循环：播完即停（深夜噪音事故的根本修复）
                setOnCompletionListener { stopEchoTest() }   // 自然播完也自动停
                prepare()
                start()
            }
            afd.close()
            echoPlayer = mp
            // 双重保险：超时硬兜底，即使完成回调异常也必定停
            handler.removeCallbacks(echoTimeoutRunnable)
            handler.postDelayed(echoTimeoutRunnable, ECHO_TEST_MAX_MS)
            Log.i(TAG, "ECHO_TEST_START 外放开始（单次播放，最多 ${ECHO_TEST_MAX_MS / 1000}s 自动停）")
        } catch (e: Exception) {
            Log.e(TAG, "ECHO_TEST 启动失败", e)
        }
    }

    private fun stopEchoTest() {
        handler.removeCallbacks(echoTimeoutRunnable)
        echoPlayer?.let { runCatching { it.stop() }; runCatching { it.release() } }
        echoPlayer = null
        Log.i(TAG, "ECHO_TEST_STOP 外放已停止")
    }

    // ===== 离线 ASR 测试台（开发期专用，静音，商用前移除）=====

    /**
     * 把 assets 里的 wav 直接喂给识别器，对比热词权重对数字识别的影响。
     * 完全静音、不碰麦克风、不复用会话实例（自建自释，无 native 并发风险）。
     */
    private fun runOfflineAsrTest(wavFile: String, score: Float) {
        thread(name = "asr-test") {
            Log.i(TAG, "ASR_TEST_START file=$wavFile hotwordsScore=$score")
            val rec = createRecognizer(score)
            if (rec == null) { Log.e(TAG, "ASR_TEST_FAIL 识别器创建失败"); return@thread }
            val v = createVad()
            if (v == null) { Log.e(TAG, "ASR_TEST_FAIL VAD创建失败"); runCatching { rec.release() }; return@thread }
            try {
                val samples = readWavFromAssets(wavFile)
                if (samples == null) { Log.e(TAG, "ASR_TEST_FAIL 读不到 $wavFile"); return@thread }
                Log.i(TAG, "ASR_TEST 音频已读取：${samples.size / SAMPLE_RATE}s")
                // v0.38.0 与真实识别链路同源：按当前灵敏度等级施加软件增益，离线台才能当验证器用
                val gain = RecognitionSensitivity.gain(RecognitionSensitivity.level(applicationContext))
                Log.i(TAG, "ASR_TEST 灵敏度增益=${gain}×")
                if (gain != 1f) {
                    for (i in samples.indices) samples[i] = (samples[i] * gain).coerceIn(-1f, 1f)
                }
                // 模拟真实链路：按 100ms 帧喂给 VAD，VAD 切句后整句识别
                val frameSize = SAMPLE_RATE / 10
                var idx = 0
                while (idx < samples.size) {
                    val end = minOf(idx + frameSize, samples.size)
                    v.acceptWaveform(samples.copyOfRange(idx, end))
                    idx = end
                    drainSegments(v, rec)
                }
                v.flush()
                drainSegments(v, rec)
                Log.i(TAG, "ASR_TEST_END 识别完毕")
            } catch (e: Exception) {
                Log.e(TAG, "ASR_TEST_FAIL 异常", e)
            } finally {
                runCatching { v.release() }
                runCatching { rec.release() }
            }
        }
    }

    private fun drainSegments(v: Vad, rec: OfflineRecognizer) {
        while (!v.empty()) {
            val segment = v.front()
            v.pop()
            val seg = segment.samples
            if (seg.isEmpty()) continue
            val stream = rec.createStream()
            stream.acceptWaveform(seg, SAMPLE_RATE)
            rec.decode(stream)
            val text = rec.getResult(stream).text.replace(" ", "")
            stream.release()
            Log.i(TAG, "ASR_TEST_RESULT: [$text]")
        }
    }

    /** 读 assets 里的 16-bit PCM 单声道 wav（跳过 RIFF 头），归一化为 float 采样 */
    private fun readWavFromAssets(name: String): FloatArray? {
        return try {
            val bytes = assets.open(name).use { it.readBytes() }
            // 定位 data chunk（标准 44 字节头后；有的 wav 头带额外 chunk，按标记搜）
            var dataOffset = -1
            for (i in 0 until bytes.size - 4) {
                if (bytes[i] == 'd'.code.toByte() && bytes[i+1] == 'a'.code.toByte() &&
                    bytes[i+2] == 't'.code.toByte() && bytes[i+3] == 'a'.code.toByte()) {
                    dataOffset = i + 8   // 跳过 "data" + 4 字节长度
                    break
                }
            }
            if (dataOffset < 0) return null
            val pcmLen = bytes.size - dataOffset
            val out = FloatArray(pcmLen / 2)
            for (i in out.indices) {
                val lo = bytes[dataOffset + i * 2].toInt() and 0xFF
                val hi = bytes[dataOffset + i * 2 + 1].toInt()
                out[i] = ((hi shl 8) or lo).toShort() / 32768f
            }
            out
        } catch (e: Exception) {
            Log.e(TAG, "读 wav 失败: $name", e)
            null
        }
    }

    private fun releaseRecognizer() {
        try { recognizer?.release() } catch (_: Exception) {}
        recognizer = null
    }

    private fun releaseVad() {
        try { vad?.release() } catch (_: Exception) {}
        vad = null
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "语音会话", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("言出法随 · 会话")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }
}
