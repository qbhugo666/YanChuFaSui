package com.voicecontrol.app

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.security.MessageDigest
import java.util.Properties

internal data class AudioDecisionOutcome(
    val decision: String?,
    val confidence: Float?,
    val latencyMs: Long,
    val fallbackReason: String? = null,
    val encoderMs: Long = 0L,
    val headMs: Long = 0L,
    /** M6 全类头候选：未加载/失败为 null；首批只有 open_recents 可在完整旧链静默后采纳。 */
    val m6Top: List<Pair<String, Float>>? = null,
    /** 数字小头（nn 轮 2026-09-30）：33 类 top-k；用于已显示编号中的派发前改号纠正。 */
    val numTop: List<Pair<String, Float>>? = null,
    /** 分段数字头，仅供完整旧链静默后的编号救回；不替换旧数字头。 */
    val numSegmentTop: List<Pair<String, Float>>? = null,
    val numSegmentHeadUs: Long = 0L,
    /** 4/10/other完整概率；专用首次点击前复核，故障只关闭本头。 */
    val numPairTop: List<Pair<String, Float>>? = null,
    val numPairHeadUs: Long = 0L,
    /** 光标专用头：左/右/other；缺失或故障只关闭本组救回。 */
    val cursorTop: List<Pair<String, Float>>? = null,
    val cursorHeadUs: Long = 0L,
)

/**
 * 音频二审层（v0.58.0-enhanced）：ASR+纠错层对高风险对立命令（增加/降低音量）拿不准时，
 * 用 SenseVoice encoder 特征 + 67KB 分类头直接做 A/B 判决（第五轮实验盲测 97.0%）。
 *
 * - 复用现有 SenseVoice：encoder 用官方同款模型切图（sensevoice_enc.onnx），
 *   输入=560 维 LFR+CMVN 特征（povey fbank80 → LFR(7,6) → CMVN(模型元数据)，
 *   与 PC 端 round3/round5 实验完全同链），不引入第二个大型 ASR
 * - 失败安全：任何异常/低置信度 → 返回 null，调用方回退到原识别结果
 * - 2026-10-03 用户移除声音二审：运行入口停用，旧偏好不能重新启用；保留代码供历史实验复查。
 * - 埋点：触发次数/回退次数/最近延迟，dumpStats() 进诊断日志
 *
 * 判决语义：logits[0]>logits[1] → "inc"(增加音量) else "dec"(降低音量)；
 * softmax 置信差 < 0.75 视为"仍拿不准"返回 null。
 */
object AudioDecision {
    private const val TAG = "AudioDecision"
    private const val CONF_MIN = 0.75f

    private var env: OrtEnvironment? = null
    private var headSession: OrtSession? = null
    private var encSession: OrtSession? = null

    // M6 全类小头（2026-09-30 D 阶段 shadow）：独立 session，懒加载，失败静默隔离
    // （m6Top=null，不影响音量二审）；仅 Debug 构建且 INIT 带 shadow 标志时加载——
    // 增强开关 OFF 时整个进程不初始化，零新增加载（任务书 D 约束）。
    private var m6Session: OrtSession? = null
    private var m6Labels: List<String> = emptyList()
    private var m6Ready = false
    // 数字小头（2026-09-30 nn 轮）：33 类（1~30+out_of_range+non_number_click+other）
    private var numSession: OrtSession? = null
    private var numLabels: List<String> = emptyList()
    private var numReady = false
    private var numSegmentSession: OrtSession? = null
    private var statLastNumSegmentUs = 0L
    private var numPairSession: OrtSession? = null
    private var statLastNumPairUs = 0L
    private var cursorSession: OrtSession? = null
    private var cursorLabels: List<String> = emptyList()
    private var statLastCursorUs = 0L
    private var negMean = FloatArray(0)
    private var invStddev = FloatArray(0)
    private var langZh = 3L
    private var withItn = 14L
    private var ready = false

    // 2026-09-28：每句不能在 200×257×400 次循环里重复算三角函数；
    // 预先存 512 点 DFT 前 257 档的系数，避免二审耗时拖慢聆听线程。
    private val dftCos by lazy {
        Array(257) { k -> DoubleArray(400) { t -> Math.cos(-2.0 * Math.PI * k * t / 512.0) } }
    }
    private val dftSin by lazy {
        Array(257) { k -> DoubleArray(400) { t -> Math.sin(-2.0 * Math.PI * k * t / 512.0) } }
    }
    private val mel80 by lazy { melFilterBank(16000f, 512, 80, 20f, 7800f) }

    // 埋点（会话级）
    var statTriggers = 0; private set
    var statFallbacks = 0; private set
    var statLastEncMs = 0L; private set
    var statLastHeadMs = 0L; private set

    @Synchronized fun init(ctx: Context, loadM6Shadow: Boolean = false) {
        if (!AudioReviewRequest.AVAILABLE) return
        val loadPair = loadM6Shadow &&
            (ctx.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (ready) {
            if (loadM6Shadow) initM6(ctx)
            if (loadM6Shadow) initNum(ctx)
            if (loadM6Shadow) initNumSegments(ctx)
            if (loadPair) initNumPair(ctx)
            if (loadM6Shadow && CursorReviewPolicy.ENABLED) initCursor(ctx)
            return
        }
        statTriggers = 0
        statFallbacks = 0
        statLastEncMs = 0L
        statLastHeadMs = 0L
        try {
            // 2026-09-28：仅在 :audio_decision 独立进程加载 ORT 1.20。
            // 主进程的 sherpa JNI 要求 1.27.1，两个版本不得进入同一个 linker 命名空间。
            val assetName = if (android.os.Process.is64Bit())
                "libonnxruntime_120_arm64.so" else "libonnxruntime_120_arm32.so"
            val digests = loadAssetDigests(ctx)
            val soFile = copyVerifiedAsset(ctx, assetName, "libonnxruntime_120.so", digests)
            System.load(soFile.absolutePath)
            env = OrtEnvironment.getEnvironment()
            val so = OrtSession.SessionOptions()
            // 关键2（2026-09-27 OOM 修复）：237MB encoder 切图不能 readBytes 进 Java 堆
            // （App 堆上限 256MB）——复制到 filesDir 用路径加载，ORT 走 mmap 不占 Java 堆
            val encFile = copyVerifiedAsset(ctx, "sensevoice_enc.onnx", "sensevoice_enc.onnx", digests)
            // 头模型（64KB）同样落盘走路径加载
            val headFile = copyVerifiedAsset(ctx, "audio_decision_head.onnx", "audio_decision_head.onnx", digests)
            try {
                headSession = env!!.createSession(headFile.absolutePath, so)
            } finally {
                so.close()
            }
            val encOptions = OrtSession.SessionOptions()
            try {
                encSession = env!!.createSession(encFile.absolutePath, encOptions)
            } finally {
                encOptions.close()
            }
            val meta = encSession!!.metadata
            val mmap = meta.customMetadata
            negMean = parseVec(mmap["neg_mean"] ?: "")
            invStddev = parseVec(mmap["inv_stddev"] ?: "")
            langZh = mmap["lang_zh"]?.toLongOrNull() ?: 3L
            withItn = mmap["with_itn"]?.toLongOrNull() ?: 14L
            // LFR(7×80) 后按 560 维做 CMVN；与 round5_main.py 的广播形状一致。
            ready = negMean.size == 560 && invStddev.size == 560
            Log.i(TAG, "init ready=$ready negMean=${negMean.size} langZh=$langZh withItn=$withItn")
        } catch (e: Throwable) {
            Log.e(TAG, "init failed", e)
            ready = false
            runCatching { headSession?.close() }
            runCatching { encSession?.close() }
            headSession = null
            encSession = null
        }
        if (loadM6Shadow) initM6(ctx)
        if (loadM6Shadow) initNum(ctx)
        if (loadM6Shadow) initNumSegments(ctx)
        if (loadPair) initNumPair(ctx)
        if (loadM6Shadow && CursorReviewPolicy.ENABLED) initCursor(ctx)
    }

    /** M6 全类小头懒加载（shadow，2026-09-30 D）：失败静默——m6Top 恒 null，不影响音量二审 */
    @Synchronized private fun initM6(ctx: Context) {
        if (m6Ready) return
        try {
            val digests = loadAssetDigests(ctx)
            val meta = org.json.JSONObject(
                ctx.assets.open("m6_head_meta.json").bufferedReader(Charsets.UTF_8).use { it.readText() })
            m6Labels = meta.getJSONArray("labels").let { a -> (0 until a.length()).map { a.getString(it) } }
            val f = copyVerifiedAsset(ctx, "m6_head_packed.onnx", "m6_head_packed.onnx", digests)
            val e = env ?: return
            val so = OrtSession.SessionOptions()
            try {
                m6Session = e.createSession(f.absolutePath, so)
            } finally {
                so.close()
            }
            m6Ready = m6Session != null && m6Labels.size >= 2
            Log.i(TAG, "m6 shadow init ready=$m6Ready labels=${m6Labels.size}")
        } catch (e: Throwable) {
            Log.w(TAG, "m6 shadow init failed（隔离，不影响音量二审）", e)
            m6Ready = false
            runCatching { m6Session?.close() }
            m6Session = null
        }
    }

    /** M6 头推理（shadow）：返回 top-k (label,prob)；未就绪/异常返回 null（静默隔离） */
    @Synchronized private fun m6Propose(pooled: FloatArray): List<Pair<String, Float>>? {
        val sess = m6Session ?: return null
        if (!m6Ready || m6Labels.isEmpty()) return null
        return try {
            val e = env ?: return null
            OnnxTensor.createTensor(e, FloatBuffer.wrap(pooled), longArrayOf(1, 512)).use { t ->
                sess.run(mapOf("features" to t)).use { outs ->
                    val logits = (outs[0].value as Array<FloatArray>)[0]
                    val exp = DoubleArray(logits.size)
                    var maxV = Double.NEGATIVE_INFINITY
                    for (v in logits) if (v > maxV) maxV = v.toDouble()
                    var sum = 0.0
                    for (i in logits.indices) {
                        exp[i] = Math.exp(logits[i] - maxV); sum += exp[i]
                    }
                    val idx = logits.indices.sortedByDescending { exp[it] }.take(3)
                    idx.map { m6Labels[it] to (exp[it] / sum).toFloat() }
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "m6 shadow infer failed（隔离）", e)
            null
        }
    }

    /** 数字小头懒加载（2026-09-30 nn 轮）：失败静默隔离；校验模型指纹/字节数/标签（194 审计§A3） */
    @Synchronized private fun initNum(ctx: Context) {
        if (numReady) return
        try {
            val digests = loadAssetDigests(ctx)
            val meta = org.json.JSONObject(
                ctx.assets.open("nn_number_head_meta.json").bufferedReader(Charsets.UTF_8).use { it.readText() })
            val labels = meta.getJSONArray("labels").let { a -> (0 until a.length()).map { a.getString(it) } }
            // 194 审计§A3：33 类精确标签顺序校验（拒绝坏图/错标签）
            check(labels == (1..30).map(Int::toString) +
                listOf("out_of_range", "non_number_click", "other")) { "nn_number_head 标签顺序不符" }
            // 模型哈希校验（同光标模式）
            val expectedSha = meta.getString("model_sha256")
            check(digests["nn_number_head.onnx"] == expectedSha) { "nn_number_head.onnx SHA-256 不匹配" }
            val expectedBytes = meta.getLong("model_bytes")
            check(ctx.assets.openFd("nn_number_head.onnx").use { it.length } == expectedBytes) {
                "nn_number_head.onnx 字节数不符（外置权重漏包检测）"
            }
            numLabels = labels
            val f = copyVerifiedAsset(ctx, "nn_number_head.onnx", "nn_number_head.onnx", digests)
            val e = env ?: return
            val so = OrtSession.SessionOptions()
            try {
                numSession = e.createSession(f.absolutePath, so)
                val input = numSession!!.inputInfo["features"]?.info as? ai.onnxruntime.TensorInfo
                val output = numSession!!.outputInfo["logits"]?.info as? ai.onnxruntime.TensorInfo
                check(numSession!!.inputNames == setOf("features") && numSession!!.outputNames == setOf("logits") && input != null &&
                    input.type == ai.onnxruntime.OnnxJavaType.FLOAT && input.shape.size == 2 &&
                    input.shape[1] == 512L && output != null && output.shape.size == 2 &&
                    output.shape[1] == 33L && output.type == ai.onnxruntime.OnnxJavaType.FLOAT) {
                    "nn_number_head 输入输出契约不符"
                }
            } finally {
                so.close()
            }
            numReady = numSession != null
            Log.i(TAG, "num head init ready=$numReady bytes=${f.length()} labels=${labels.size}")
        } catch (e: Throwable) {
            Log.w(TAG, "num head init failed（隔离，不影响其他头）", e)
            numReady = false
            runCatching { numSession?.close() }
            numSession = null
        }
    }

    /** 数字头推理：返回 top-k (label,prob)；未就绪/异常返回 null */
    @Synchronized private fun numPropose(pooled: FloatArray): List<Pair<String, Float>>? {
        val sess = numSession ?: return null
        if (!numReady || numLabels.isEmpty()) return null
        return try {
            val e = env ?: return null
            OnnxTensor.createTensor(e, FloatBuffer.wrap(pooled), longArrayOf(1, 512)).use { t ->
                sess.run(mapOf("features" to t)).use { outs ->
                    val logits = (outs[0].value as Array<FloatArray>)[0]
                    val exp = DoubleArray(logits.size)
                    var maxV = Double.NEGATIVE_INFINITY
                    for (v in logits) if (v > maxV) maxV = v.toDouble()
                    var sum = 0.0
                    for (i in logits.indices) {
                        exp[i] = Math.exp(logits[i] - maxV); sum += exp[i]
                    }
                    val idx = logits.indices.sortedByDescending { exp[it] }
                    idx.map { numLabels[it] to (exp[it] / sum).toFloat() }
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "num head infer failed（隔离）", e)
            null
        }
    }

    @Synchronized fun isReady(): Boolean = ready

    @Synchronized private fun initNumSegments(ctx: Context) {
        if (numSegmentSession != null) return
        try {
            val meta = org.json.JSONObject(ctx.assets.open("nn_number_segments_meta.json")
                .bufferedReader(Charsets.UTF_8).use { it.readText() })
            val labels = meta.getJSONArray("labels").let { a -> (0 until a.length()).map(a::getString) }
            check(labels == (1..30).map(Int::toString) + listOf("out_of_range","non_number_click","other"))
            check(meta.getInt("input_dimension") == NumberSegmentFeatures.DIMENSION &&
                meta.getInt("control_prefix_rows") == NumberSegmentFeatures.CONTROL_ROWS &&
                meta.getInt("bins") == NumberSegmentFeatures.BINS && meta.getDouble("threshold") == .99)
            val digests = loadAssetDigests(ctx)
            check(meta.getString("encoder_sha256") == digests["sensevoice_enc.onnx"])
            check(meta.getString("model_sha256") == digests["nn_number_segments.onnx"])
            check(ctx.assets.openFd("nn_number_segments.onnx").use { it.length } == meta.getLong("model_bytes"))
            val f = copyVerifiedAsset(ctx,"nn_number_segments.onnx","nn_number_segments.onnx",digests)
            val e = env ?: return
            OrtSession.SessionOptions().use { options ->
                numSegmentSession = e.createSession(f.absolutePath,options)
                val session = numSegmentSession!!
                val input = session.inputInfo["features"]?.info as? ai.onnxruntime.TensorInfo
                val output = session.outputInfo["logits"]?.info as? ai.onnxruntime.TensorInfo
                check(session.inputNames == setOf("features") && session.outputNames == setOf("logits") &&
                    input != null && input.type == ai.onnxruntime.OnnxJavaType.FLOAT && input.shape.size == 2 &&
                    input.shape[1] == NumberSegmentFeatures.DIMENSION.toLong() && output != null &&
                    output.type == ai.onnxruntime.OnnxJavaType.FLOAT && output.shape.size == 2 && output.shape[1] == 33L)
            }
            Log.i(TAG,"num segments init ready=true bytes=${f.length()}")
        } catch (error: Throwable) {
            Log.w(TAG,"num segments init failed（只关闭补充数字头）",error)
            runCatching { numSegmentSession?.close() }; numSegmentSession = null
        }
    }

    @Synchronized private fun numSegmentPropose(features: FloatArray?): List<Pair<String,Float>>? {
        statLastNumSegmentUs = 0L
        val session = numSegmentSession ?: return null
        if (features == null) return null
        val e = env ?: return null
        val started = System.nanoTime()
        return try {
            OnnxTensor.createTensor(e,FloatBuffer.wrap(features),longArrayOf(1,NumberSegmentFeatures.DIMENSION.toLong())).use { input ->
                session.run(mapOf("features" to input)).use { out ->
                    val logits = (out[0].value as Array<FloatArray>)[0]
                    check(logits.size == 33 && logits.all { it.isFinite() })
                    val max = logits.maxOrNull()!!.toDouble()
                    val exp = logits.map { Math.exp(it.toDouble()-max) }; val sum = exp.sum()
                    val labels = (1..30).map(Int::toString) + listOf("out_of_range","non_number_click","other")
                    logits.indices.sortedByDescending { exp[it] }.map { labels[it] to (exp[it]/sum).toFloat() }
                }
            }
        } catch (error: Throwable) {
            Log.w(TAG,"num segments infer failed（保持旧流程）",error); null
        } finally { statLastNumSegmentUs = (System.nanoTime()-started)/1000 }
    }

    /** 2026-10-01：4/10/other四段头；独立契约和session，不重跑encoder，不扩大其他数字范围。 */
    @Synchronized private fun initNumPair(ctx: Context) {
        if (numPairSession != null) return
        try {
            val meta = org.json.JSONObject(ctx.assets.open("nn_number_pair4_10_meta.json")
                .bufferedReader(Charsets.UTF_8).use { it.readText() })
            val labels = meta.getJSONArray("labels").let { a -> (0 until a.length()).map(a::getString) }
            check(labels == NumberPairReviewPolicy.LABELS &&
                meta.getInt("input_dimension") == NumberPairFeatures.DIMENSION &&
                meta.getInt("control_prefix_rows") == NumberPairFeatures.CONTROL_ROWS &&
                meta.getInt("bins") == NumberPairFeatures.BINS &&
                meta.getDouble("temperature") == 1.0 &&
                meta.getDouble("threshold").toFloat() == NumberPairReviewPolicy.THRESHOLD &&
                meta.getDouble("min_margin").toFloat() == NumberPairReviewPolicy.MIN_MARGIN)
            val digests = loadAssetDigests(ctx)
            check(meta.getString("encoder_sha256") == digests["sensevoice_enc.onnx"])
            check(meta.getString("model_sha256") == digests["nn_number_pair4_10.onnx"])
            check(ctx.assets.openFd("nn_number_pair4_10.onnx").use { it.length } == meta.getLong("model_bytes"))
            val f = copyVerifiedAsset(ctx,"nn_number_pair4_10.onnx","nn_number_pair4_10.onnx",digests)
            val e = env ?: return
            OrtSession.SessionOptions().use { options ->
                // 525KB小头不另开多线程忙等；现有encoder与各组参数保持原样。
                options.setIntraOpNumThreads(1)
                options.setInterOpNumThreads(1)
                options.addConfigEntry("session.intra_op.allow_spinning","0")
                options.addConfigEntry("session.inter_op.allow_spinning","0")
                numPairSession = e.createSession(f.absolutePath,options)
                val session = numPairSession!!
                val input = session.inputInfo["features"]?.info as? ai.onnxruntime.TensorInfo
                val output = session.outputInfo["logits"]?.info as? ai.onnxruntime.TensorInfo
                check(session.inputNames == setOf("features") && session.outputNames == setOf("logits") &&
                    input != null && input.type == ai.onnxruntime.OnnxJavaType.FLOAT && input.shape.size == 2 &&
                    input.shape[1] == NumberPairFeatures.DIMENSION.toLong() && output != null &&
                    output.type == ai.onnxruntime.OnnxJavaType.FLOAT && output.shape.size == 2 && output.shape[1] == 3L)
            }
            Log.i(TAG,"num pair4/10 init ready=true bytes=${f.length()}")
        } catch (error: Throwable) {
            Log.w(TAG,"num pair4/10 init failed（只关闭4/10头）",error)
            runCatching { numPairSession?.close() }; numPairSession = null
        }
    }

    @Synchronized private fun numPairPropose(features: FloatArray?): List<Pair<String,Float>>? {
        statLastNumPairUs = 0L
        val session = numPairSession ?: return null
        if (features == null) return null
        val e = env ?: return null
        val started = System.nanoTime()
        return try {
            OnnxTensor.createTensor(e,FloatBuffer.wrap(features),longArrayOf(1,NumberPairFeatures.DIMENSION.toLong())).use { input ->
                session.run(mapOf("features" to input)).use { out ->
                    val logits = (out[0].value as Array<FloatArray>)[0]
                    check(logits.size == NumberPairReviewPolicy.LABELS.size && logits.all { it.isFinite() })
                    val max = logits.maxOrNull()!!.toDouble()
                    val exp = logits.map { Math.exp(it.toDouble()-max) }; val sum = exp.sum()
                    logits.indices.sortedByDescending { exp[it] }.map {
                        NumberPairReviewPolicy.LABELS[it] to (exp[it]/sum).toFloat()
                    }
                }
            }
        } catch (error: Throwable) {
            Log.w(TAG,"num pair4/10 infer failed（保持已有数字流程）",error); null
        } finally { statLastNumPairUs = (System.nanoTime()-started)/1000 }
    }

    /** 与音量/全类头共用真实 encoder pooled；初始化失败不影响已有两条能力。 */
    @Synchronized private fun initCursor(ctx: Context) {
        if (cursorSession != null) return
        val started = System.nanoTime()
        try {
            val meta = org.json.JSONObject(ctx.assets.open("cursor_review_meta.json")
                .bufferedReader(Charsets.UTF_8).use { it.readText() })
            val labels = meta.getJSONArray("labels").let { a -> (0 until a.length()).map { a.getString(it) } }
            check(labels == listOf("text_cursor_left", "text_cursor_right", "other"))
            check(meta.getDouble("threshold").toFloat() == CursorReviewPolicy.MIN_CONFIDENCE)
            check(meta.getDouble("margin").toFloat() == CursorReviewPolicy.MIN_MARGIN)
            val digests = loadAssetDigests(ctx)
            check(meta.getString("model_sha256") == digests["cursor_review_head.onnx"])
            val f = copyVerifiedAsset(ctx,"cursor_review_head.onnx","cursor_review_head.onnx",digests)
            val e = env ?: return
            OrtSession.SessionOptions().use { options -> cursorSession = e.createSession(f.absolutePath,options) }
            cursorLabels = labels
            Log.i(TAG,"cursor init ready=true ${(System.nanoTime()-started)/1_000_000}ms bytes=${f.length()}")
        } catch (error: Throwable) {
            Log.w(TAG,"cursor init failed（隔离，不影响现有二审）",error)
            runCatching { cursorSession?.close() }
            cursorSession = null
            cursorLabels = emptyList()
        }
    }

    @Synchronized private fun cursorPropose(pooled: FloatArray): List<Pair<String,Float>>? {
        statLastCursorUs = 0L
        val sess = cursorSession ?: return null
        val e = env ?: return null
        val started = System.nanoTime()
        return try {
            OnnxTensor.createTensor(e,FloatBuffer.wrap(pooled),longArrayOf(1,512)).use { input ->
                sess.run(mapOf("features" to input)).use { out ->
                    val logits = (out[0].value as Array<FloatArray>)[0]
                    check(logits.size == cursorLabels.size && logits.all { it.isFinite() })
                    val max = logits.maxOrNull()!!.toDouble()
                    val exp = logits.map { Math.exp(it.toDouble()-max) }
                    val sum = exp.sum()
                    logits.indices.sortedByDescending { exp[it] }.map { cursorLabels[it] to (exp[it]/sum).toFloat() }
                }
            }
        } catch (error: Throwable) {
            Log.w(TAG,"cursor infer failed（隔离）",error)
            null
        } finally {
            statLastCursorUs = (System.nanoTime()-started)/1_000L
        }
    }

    private fun parseVec(s: String): FloatArray =
        s.trim().trim('[', ']').split(',').mapNotNull { it.trim().toFloatOrNull() }.toFloatArray()

    private fun loadAssetDigests(ctx: Context): Map<String, String> {
        val result = mutableMapOf<String, String>()
        ctx.assets.open("audio_decision_assets.sha256").bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach { line ->
                val split = line.split('=', limit = 2)
                if (split.size == 2 && split[1].matches(Regex("[0-9a-f]{64}"))) result[split[0]] = split[1]
            }
        }
        return result
    }

    private fun copyVerifiedAsset(
        ctx: Context,
        assetName: String,
        targetName: String,
        digests: Map<String, String>,
    ): java.io.File {
        val expectedSha = digests[assetName] ?: error("asset digest missing: $assetName")
        // ONNX assets 配置为 noCompress，可读取准确长度；hash 标记变化时才完整校验/复制大模型。
        val expectedLength = ctx.assets.openFd(assetName).use { it.length }
        val target = java.io.File(ctx.filesDir, targetName)
        val marker = java.io.File(ctx.filesDir, "audio_decision_asset_hashes.properties")
        val copied = Properties().apply {
            if (marker.isFile) marker.inputStream().use { load(it) }
        }
        if (target.length() == expectedLength && copied.getProperty(targetName) == expectedSha) return target

        val temp = java.io.File(ctx.filesDir, "$targetName.part")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            ctx.assets.open(assetName).use { input ->
                temp.outputStream().buffered().use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            }
            check(temp.length() == expectedLength) { "asset copy incomplete: $assetName" }
            val actualSha = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            check(actualSha == expectedSha) { "asset hash mismatch: $assetName" }
            if (target.exists()) check(target.delete()) { "cannot replace asset: $targetName" }
            check(temp.renameTo(target)) { "cannot move asset: $targetName" }
            copied.setProperty(targetName, expectedSha)
            val markerTemp = java.io.File(ctx.filesDir, "audio_decision_asset_hashes.properties.part")
            markerTemp.outputStream().use { copied.store(it, "AudioDecision model asset hashes") }
            if (marker.exists()) marker.delete()
            check(markerTemp.renameTo(marker)) { "cannot update AudioDecision asset hash marker" }
        } finally {
            if (temp.exists()) temp.delete()
        }
        return target
    }

    fun isEnabled(ctx: Context): Boolean =
        AudioReviewRequest.runtimeEnabled(ctx.getSharedPreferences("app", Context.MODE_PRIVATE)
            .getBoolean("enhanced_decision_enabled", false))

    @Synchronized fun shutdown() {
        // 关闭/诊断探针结束时释放第二套模型的 native 内存；与 decide 共锁。
        ready = false
        runCatching { headSession?.close() }
        runCatching { encSession?.close() }
        runCatching { m6Session?.close() }
        runCatching { cursorSession?.close() }
        headSession = null
        encSession = null
        m6Session = null
        m6Ready = false
        m6Labels = emptyList()
        runCatching { numSession?.close() }
        numSession = null
        numReady = false
        numLabels = emptyList()
        runCatching { numSegmentSession?.close() }
        numSegmentSession = null
        statLastNumSegmentUs = 0L
        runCatching { numPairSession?.close() }
        numPairSession = null
        statLastNumPairUs = 0L
        cursorSession = null
        cursorLabels = emptyList()
        statLastCursorUs = 0L
    }

    /**
     * 二审判决。
     * @param pcm16k 16k 单声道 float 音频（-1..1， VAD 切出的当前句）
     * @return "inc"=增加音量 / "dec"=降低音量 / null=不判定（回退原识别）
     */
    @Synchronized fun decide(pcm16k: FloatArray): String? {
        return decideDetailed(pcm16k).decision
    }

    @Synchronized internal fun decideDetailed(pcm16k: FloatArray): AudioDecisionOutcome {
        if (!AudioReviewRequest.AVAILABLE)
            return AudioDecisionOutcome(null, null, 0L, "feature_removed")
        val startedAt = System.nanoTime()
        statLastEncMs = 0L
        statLastHeadMs = 0L
        fun fallback(reason: String, confidence: Float? = null) = AudioDecisionOutcome(
            decision = null,
            confidence = confidence,
            latencyMs = (System.nanoTime() - startedAt) / 1_000_000L,
            fallbackReason = reason,
            encoderMs = statLastEncMs,
            headMs = statLastHeadMs,
        ).also { statFallbacks++ }

        // 2026-09-30 P0 修复（Astra 复核）：m6 头曾误用「CMVN 前 512 维按帧平均」冒充池化
        // ——与训练用的 encoder 第二输出 mean-pool 是不同特征空间（维度相同≠可互换）。
        // 正解：先跑一次真实 encoder，真 pooled 同时喂音量头与 m6 头；编码器不可用/
        // 未就绪时 m6Top=null（不可用就是不可用，不用 CMVN 假数据冒充）。
        if (!ready) return fallback("not_ready")
        return try {
            val fbank = kaldiFbank80(pcm16k) ?: return fallback("too_short_for_features")
            val lfr = applyLfr(fbank)
            val cmvn = applyCmvn(lfr)
            val T = cmvn.size
            val flat = FloatArray(T * 560)
            for (t in 0 until T) System.arraycopy(cmvn[t], 0, flat, t * 560, 560)

            val t0 = System.nanoTime()
            val e = env ?: return fallback("runtime_unavailable")
            val inputTensors = mutableListOf<OnnxTensor>()
            var numSegmentFeatures: FloatArray? = null
            var numPairFeatures: FloatArray? = null
            val pooled = try {
                val xTensor = OnnxTensor.createTensor(e, FloatBuffer.wrap(flat), longArrayOf(1, T.toLong(), 560))
                    .also(inputTensors::add)
                // 2026-09-28：round5_main.py 的三个控制输入是 int32；特征来自第二输出。
                val lenTensor = OnnxTensor.createTensor(e, IntBuffer.wrap(intArrayOf(T)), longArrayOf(1))
                    .also(inputTensors::add)
                val langTensor = OnnxTensor.createTensor(e, IntBuffer.wrap(intArrayOf(langZh.toInt())), longArrayOf(1))
                    .also(inputTensors::add)
                val itnTensor = OnnxTensor.createTensor(e, IntBuffer.wrap(intArrayOf(withItn.toInt())), longArrayOf(1))
                    .also(inputTensors::add)
                encSession!!.run(mapOf("x" to xTensor, "x_length" to lenTensor,
                    "language" to langTensor, "text_norm" to itnTensor)).use { outs ->
                    val enc = (outs[1].value as Array<*>)[0] as Array<FloatArray>
                    statLastEncMs = (System.nanoTime() - t0) / 1_000_000
                    if (enc.isEmpty()) return fallback("empty_encoder_output")
                    numSegmentFeatures = NumberSegmentFeatures.fromEncoder(enc,T)
                    numPairFeatures = if (numPairSession != null) NumberPairFeatures.fromEncoder(enc,T) else null
                    FloatArray(512).also { pool ->
                        for (fr in enc) for (i in pool.indices) pool[i] += fr[i]
                        for (i in pool.indices) pool[i] = pool[i] / enc.size.toFloat()
                    }
                }
            } finally {
                inputTensors.asReversed().forEach { tensor -> runCatching { tensor.close() } }
            }

            val t1 = System.nanoTime()
            val logits = OnnxTensor.createTensor(e, FloatBuffer.wrap(pooled), longArrayOf(1, 512)).use { fTensor ->
                headSession!!.run(mapOf("features" to fTensor)).use { logitsOut ->
                    (logitsOut[0].value as Array<FloatArray>)[0].copyOf()
                }
            }
            statLastHeadMs = (System.nanoTime() - t1) / 1_000_000

            // m6 shadow：**真实 pooled**（同一 encoder 输出）多头推理——与训练同特征空间
            val m6Result = m6Propose(pooled)
            val numResult = numPropose(pooled)
            val numSegmentResult = numSegmentPropose(numSegmentFeatures)
            val numPairResult = numPairPropose(numPairFeatures)
            val cursorResult = cursorPropose(pooled)

            // softmax 置信差
            val diff = (logits[0] - logits[1]).toDouble()
            if (!diff.isFinite()) {
                return fallback("non_finite_logits").copy(m6Top=m6Result,numTop=numResult,numSegmentTop=numSegmentResult,
                    numSegmentHeadUs=statLastNumSegmentUs,numPairTop=numPairResult,numPairHeadUs=statLastNumPairUs,
                    cursorTop=cursorResult,cursorHeadUs=statLastCursorUs)
            }
            val conf = (1.0 / (1.0 + Math.exp(-Math.abs(diff)))).toFloat()
            statTriggers++
            if (conf < CONF_MIN) {
                Log.i(TAG, "low-conf $diff → fallback")
                return fallback("low_confidence",conf).copy(m6Top=m6Result,numTop=numResult,numSegmentTop=numSegmentResult,
                    numSegmentHeadUs=statLastNumSegmentUs,numPairTop=numPairResult,numPairHeadUs=statLastNumPairUs,
                    cursorTop=cursorResult,cursorHeadUs=statLastCursorUs)
            }
            Log.i(TAG, "decide conf=$conf enc=${statLastEncMs}ms head=${statLastHeadMs}ms")
            AudioDecisionOutcome(
                decision = if (diff > 0) "inc" else "dec",
                confidence = conf,
                latencyMs = (System.nanoTime() - startedAt) / 1_000_000L,
                encoderMs = statLastEncMs,
                headMs = statLastHeadMs,
                m6Top = m6Result,
                numTop = numResult,
                numSegmentTop = numSegmentResult,
                numSegmentHeadUs = statLastNumSegmentUs,
                numPairTop = numPairResult,
                numPairHeadUs = statLastNumPairUs,
                cursorTop = cursorResult,
                cursorHeadUs = statLastCursorUs,
            )
        } catch (e: Throwable) {
            Log.e(TAG, "decide failed → fallback", e)
            fallback("runtime_error_${e.javaClass.simpleName}")
        }
    }

    // 2026-09-28 M3：旧的 shouldTrigger(text) 已删除——它从未接入任何调用点，且注释声称的
    // 「词面无法判定才触发」与真实入口（含 音/声/量 即宽召回，见 AudioDecisionRouting.
    // shouldRequestReview）不符。真实触发/门控/采纳策略统一在 AudioDecisionRouting。

    fun dumpStats(): String =
        "AudioDecision 触发=$statTriggers 回退=$statFallbacks enc=${statLastEncMs}ms head=${statLastHeadMs}ms ready=$ready"

    // ---------- 前端特征（PC fbank_v3.py 同公式，纯 Kotlin 无 native 依赖） ----------
    private fun kaldiFbank80(x: FloatArray): Array<FloatArray>? {
        val fl = 400; val fs = 160
        if (x.size < fl) return null
        val pre = FloatArray(x.size)
        pre[0] = x[0]
        for (i in 1 until x.size) pre[i] = x[i] - 0.97f * x[i - 1]
        val nfr = 1 + (pre.size - fl) / fs
        val frames = Array(nfr) { FloatArray(fl) }
        for (i in 0 until nfr) System.arraycopy(pre, i * fs, frames[i], 0, fl)
        val win = FloatArray(fl)
        for (i in 0 until fl) win[i] = Math.pow(0.5 - 0.5 * Math.cos(2 * Math.PI * i / (fl - 1)), 0.85).toFloat()
        for (i in 0 until nfr) for (k in 0 until fl) frames[i][k] *= win[k]
        // 512 点 DFT 功率谱（实部利用，257 点）
        val spec = Array(nfr) { FloatArray(257) }
        for (i in 0 until nfr) {
            for (k in 0 until 257) {
                var re = 0.0; var im = 0.0
                for (t in 0 until fl) {
                    re += frames[i][t] * dftCos[k][t]
                    im += frames[i][t] * dftSin[k][t]
                }
                spec[i][k] = (re * re + im * im).toFloat()
            }
        }
        val feat = Array(nfr) { FloatArray(80) }
        for (i in 0 until nfr) {
            for (b in 0 until 80) {
                var s = 0f
                for (k in 0 until 257) s += spec[i][k] * mel80[b][k]
                feat[i][b] = Math.log(Math.max(s.toDouble(), 2.220446049250313e-16)).toFloat()
            }
        }
        return feat
    }

    private fun melFilterBank(sr: Float, nfft: Int, nMel: Int, fmin: Float, fmax: Float): Array<FloatArray> {
        // librosa.filters.mel(htk=False, norm=None)：Slaney 标度 + 连续频率三角窗。
        // 昨日版本误用了 HTK 标度与取整 bin，与训练特征不一致。
        fun hz2mel(f: Double): Double = if (f < 1000.0) f / (200.0 / 3.0)
            else 15.0 + Math.log(f / 1000.0) / (Math.log(6.4) / 27.0)
        fun mel2hz(m: Double): Double = if (m < 15.0) m * (200.0 / 3.0)
            else 1000.0 * Math.exp((m - 15.0) * Math.log(6.4) / 27.0)
        val melMin = hz2mel(fmin.toDouble()); val melMax = hz2mel(fmax.toDouble())
        val hz = DoubleArray(nMel + 2) { mel2hz(melMin + (melMax - melMin) * it / (nMel + 1)) }
        val fb = Array(nMel) { FloatArray(nfft / 2 + 1) }
        for (b in 0 until nMel) {
            for (k in fb[b].indices) {
                val freq = k * sr / nfft
                val left = (freq - hz[b]) / (hz[b + 1] - hz[b])
                val right = (hz[b + 2] - freq) / (hz[b + 2] - hz[b + 1])
                fb[b][k] = Math.max(0.0, Math.min(left, right)).toFloat()
            }
        }
        return fb
    }

    private fun applyLfr(feat: Array<FloatArray>, window: Int = 7, shift: Int = 6): Array<FloatArray> {
        var T = feat.size
        val D = feat[0].size
        val pad = (-(T % shift) + shift) % shift
        val padded = Array(T + pad) { FloatArray(D) }
        for (i in 0 until T) System.arraycopy(feat[i], 0, padded[i], 0, D)
        for (i in 1..pad) System.arraycopy(feat[T - 1], 0, padded[T - 1 + i], 0, D)
        T += pad
        val head = Array(window / 2) { feat[0] }
        val tailCount = window - 1 - window / 2
        val tail = Array(tailCount) { feat[T - pad - 1] }
        val full = head + padded + tail
        val outs = ArrayList<FloatArray>()
        var i0 = 0
        // 与 Python range(0, T-window+1, shift) 对齐（T 是尾帧补齐后的 fbank 长度）。
        while (i0 < T - window + 1) {
            val row = FloatArray(window * D)
            for (w in 0 until window) System.arraycopy(full[i0 + w], 0, row, w * D, D)
            outs.add(row)
            i0 += shift
        }
        return outs.toTypedArray()
    }

    private fun applyCmvn(feat: Array<FloatArray>): Array<FloatArray> {
        val out = Array(feat.size) { FloatArray(feat[0].size) }
        for (t in feat.indices) for (d in feat[0].indices)
            out[t][d] = (feat[t][d] + negMean[d]) * invStddev[d]
        return out
    }
}
