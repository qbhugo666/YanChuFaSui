package com.voicecontrol.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * M1 全命令回归语料（2026-09-28）：**读取生产 commands.json**（不手抄第二份词表——
 * 旧 CommandMatcherTest 的 TEST_JSON 手抄会漂移，是已知债），用与产品同源的
 * CommandRouting / CommandMatcher / AudioDecisionRouting 纯函数做「识别文字+上下文 →
 * 拟执行动作」的离线对照。无任何手机副作用：不执行动作、不加载模型、不发 IPC。
 *
 * 用例字段：caseId / 来源 / 识别文字 / 上下文 / 增强状态 / 可注入音频判决 / 预期。
 * 预期答案来自明确的行为规范（历史 bug 修复的验收描述），不是拿待测算法生成自身答案。
 *
 * 覆盖边界（如实声明）：本 runner 只覆盖「候选→拟执行动作」的确定性前半链。
 * 执行器相关路径（听写触发/落笔、「继续」延期、「退出」红线、语气词、点击屏幕直通、
 * 说法录入、长按待命、tapText 失败后的拼音模糊兜底）仍在 VoiceService——它们依赖
 * 真实无障碍服务/输入框状态，按任务书「小步提取」暂不伪造，后续里程碑逐条迁入。
 */
class ProductionRoutingCorpusTest {

    private fun productionJson(): String {
        val candidates = listOf(
            File("src/main/assets/commands.json"),
            File("app/src/main/assets/commands.json"),
        )
        return candidates.firstOrNull { it.isFile }?.readText(Charsets.UTF_8)
            ?: error("生产词表 commands.json 未找到（user.dir=${File(".").absolutePath}）")
    }

    private val matcher by lazy { CommandMatcher.fromJson(productionJson()) }

    private val ctxDefault = CommandRouting.UtteranceContext(
        gridShowing = false, labelsVisible = false, lastActionPresent = false,
    )

    private val ctxGrid = CommandRouting.UtteranceContext(
        gridShowing = true, labelsVisible = false, lastActionPresent = false,
    )

    private val ctxLabels = CommandRouting.UtteranceContext(
        gridShowing = false, labelsVisible = true, lastActionPresent = false,
    )

    private val ctxWithLastAction = CommandRouting.UtteranceContext(
        gridShowing = false, labelsVisible = false, lastActionPresent = true,
    )

    // ---------- 1. 生产词表全量：每个命令词/别名 exact 命中自己的动作 ----------

    @Test fun `生产词表全部命令词与别名各自命中正确动作`() {
        val root = org.json.JSONObject(productionJson())
        var checked = 0
        val groups = root.getJSONArray("groups")
        for (gi in 0 until groups.length()) {
            val g = groups.getJSONObject(gi)
            for (ci in 0 until g.getJSONArray("commands").length()) {
                val c = g.getJSONArray("commands").getJSONObject(ci)
                val action = c.getString("action")
                val words = mutableListOf(c.getString("command"))
                c.optJSONArray("aliases")?.let { al -> for (ai in 0 until al.length()) words.add(al.getString(ai)) }
                for (w in words) {
                    val plan = CommandRouting.planCommand(w, ctxDefault, matcher)
                    when {
                        plan is CommandRouting.Decision.DispatchCommand -> {
                            assertEquals("词表词 [$w] 动作不符", action, plan.action)
                            assertEquals("词表词 [$w] 匹配方式应为 exact", "exact", plan.method)
                        }
                        // 「按住不动」这类长按前缀别名：产品真实链路=先试长按文字（lpText 分支在前），
                        // 执行器找不到目标文字后回退 strict 匹配进待命模式（既有回退链，见
                        // handleRecognized 长按文字 else 分支）。此处建模该回退：strict 必须能兜回原动作
                        plan is CommandRouting.Decision.LongPressText && w.startsWith("长按") || plan is CommandRouting.Decision.LongPressText && w.startsWith("按住") -> {
                            val strict = matcher.matchStrict(w)
                            assertTrue("长按前缀别名 [$w] 的回退 strict 未兜回命令", strict != null && strict.action == action)
                        }
                        else -> throw AssertionError("词表词 [$w] 未按命令路由，实际=$plan")
                    }
                    checked++
                }
            }
        }
        assertTrue("生产词表条目数异常少：$checked", checked >= 100)
    }

    @Test fun `词表顺序打乱后同一批词路由结果不变`() {
        // M2 验收：无显式优先权的同分冲突不因 JSON 顺序改变动作。
        // 把组与命令全部反转重建，同一批词必须得到同一动作。
        val root = org.json.JSONObject(productionJson())
        val reversed = org.json.JSONObject()
        val groups = root.getJSONArray("groups")
        val rGroups = org.json.JSONArray()
        for (gi in groups.length() - 1 downTo 0) {
            val g = groups.getJSONObject(gi)
            val cmds = g.getJSONArray("commands")
            val rCmds = org.json.JSONArray()
            for (ci in cmds.length() - 1 downTo 0) rCmds.put(cmds.get(ci))
            rGroups.put(org.json.JSONObject().put("id", g.getString("id")).put("commands", rCmds))
        }
        reversed.put("groups", rGroups)
        val shuffledMatcher = CommandMatcher.fromJson(reversed.toString())

        val root2 = org.json.JSONObject(productionJson())
        val groups2 = root2.getJSONArray("groups")
        for (gi in 0 until groups2.length()) {
            val g = groups2.getJSONObject(gi)
            for (ci in 0 until g.getJSONArray("commands").length()) {
                val c = g.getJSONArray("commands").getJSONObject(ci)
                val word = c.getString("command")
                val a = CommandRouting.planCommand(word, ctxDefault, matcher)
                val b = CommandRouting.planCommand(word, ctxDefault, shuffledMatcher)
                assertEquals("顺序打乱后 [$word] 路由改变", a, b)
            }
        }
    }

    @Test fun `生产词表无同音不同动作冲突（歧义扩展零回归前提）`() {
        // M2：matchStrictDetailed 把同分歧义从 contains(90) 推广到 pinyin_exact(80)。
        // 当前生产表若存在「拼音完全相同但动作不同」的词对，该扩展会改变既有行为——
        // 此不变量测试保证推广对现行词表零影响；未来加词踩线会在这里被拦下。
        val byPinyin = mutableMapOf<String, String>()
        val root = org.json.JSONObject(productionJson())
        val groups = root.getJSONArray("groups")
        for (gi in 0 until groups.length()) {
            val g = groups.getJSONObject(gi)
            for (ci in 0 until g.getJSONArray("commands").length()) {
                val c = g.getJSONArray("commands").getJSONObject(ci)
                val words = mutableListOf(c.getString("command"))
                c.optJSONArray("aliases")?.let { al -> for (ai in 0 until al.length()) words.add(al.getString(ai)) }
                for (w in words) {
                    val py = pinyinOf(w)
                    val existing = byPinyin[py]
                    if (existing != null && existing != c.getString("action")) {
                        throw AssertionError("拼音 [$py] 同时属于不同动作：$existing vs ${c.getString("action")}（词=$w）")
                    }
                    byPinyin[py] = c.getString("action")
                }
            }
        }
    }

    // ---------- 2. 数字/编号历史回归（每个 case 对应一次真实事故修复） ----------

    @Test fun `数字路由回归组`() {
        fun plan(text: String, ctx: CommandRouting.UtteranceContext = ctxDefault) =
            CommandRouting.planCommand(text, ctx, matcher)

        // v0.57.14：杂音容忍不得吃掉「十」——「点击十八」=18 不是 8
        assertEquals(CommandRouting.Decision.TapNumber(18), plan("点击十八"))
        // v0.57.5：起音重复折叠——「二十二十六」=26 不是 22
        assertEquals(CommandRouting.Decision.TapNumber(26), plan("点击二十二十六"))
        assertEquals(CommandRouting.Decision.TapNumber(29), plan("点击二十二十九"))
        // v0.57.13：「击」听岔（点辑四）仍进编号通道
        assertEquals(CommandRouting.Decision.TapNumber(4), plan("点辑四"))
        // v0.9：多条合并取尾——「点击二十五点击十八」执行 18
        assertEquals(CommandRouting.Decision.TapNumber(18), plan("点击二十五点击十八"))
        // 编号显示时裸数字直通；不显示时不进编号（掉文字点击，产品端由 tapText 失败兜底）
        assertEquals(CommandRouting.Decision.TapNumber(58), plan("五十八", ctxLabels))
        // v0.23.1：编号显示时「四是」=40（宽松数字音节集）
        assertEquals(CommandRouting.Decision.TapNumber(40), plan("四是", ctxLabels))
        assertTrue(plan("四是") is CommandRouting.Decision.TapText)
        // 防误触：数字后跟「下」一律不是编号（点一下/点两下）
        assertTrue(plan("点一下") !is CommandRouting.Decision.TapNumber)
        assertTrue(plan("点两下") !is CommandRouting.Decision.TapNumber)
    }

    // ---------- 3. 网格路由（点击格 / 缩放 / 长按格 互不串道） ----------

    @Test fun `网格路由回归组`() {
        fun plan(text: String, ctx: CommandRouting.UtteranceContext) =
            CommandRouting.planCommand(text, ctx, matcher)

        // 点击格 vs 缩放 vs 长按格（v0.25.5 / v0.57.19）
        assertEquals(CommandRouting.Decision.GridTapCell(5), plan("点击第5格", ctxGrid))
        assertEquals(CommandRouting.Decision.GridTapCell(7), plan("点第7格", ctxGrid))
        assertEquals(CommandRouting.Decision.GridZoom(7), plan("缩放到第7格", ctxGrid))
        assertEquals(CommandRouting.Decision.GridLongPress(3), plan("长按第3格", ctxGrid))
        assertEquals(CommandRouting.Decision.GridLongPress(3), plan("按住第3格", ctxGrid))
        // 网格显示时纯数字=缩放意图（loose）
        assertEquals(CommandRouting.Decision.GridZoom(8), plan("八", ctxGrid))
        // 「退回」是撤销命令不是数字（GRID_BACK_KEYWORDS 双保险）
        val back = plan("退回", ctxGrid)
        assertTrue(back is CommandRouting.Decision.DispatchCommand)
        assertEquals("grid_back", (back as CommandRouting.Decision.DispatchCommand).action)
        // 网格显示时正式命令不被宽松数字抢走（GridNumberRouting 守卫，真机事故「声音大一点」）
        val volUp = plan("声音大一点", ctxGrid)
        assertTrue("声音大一点被网格数字劫持：$volUp", volUp is CommandRouting.Decision.DispatchCommand)
        assertEquals("volume_up", (volUp as CommandRouting.Decision.DispatchCommand).action)
        // 既有行为快照：网格未显示时「第5格」走缩放分支（执行器端拒绝），不是点击格
        assertEquals(CommandRouting.Decision.GridZoom(5), plan("第5格", ctxDefault))
    }

    // ---------- 4. 重复 / 替换 ----------

    @Test fun `重复与替换路由回归组`() {
        fun plan(text: String, ctx: CommandRouting.UtteranceContext = ctxDefault) =
            CommandRouting.planCommand(text, ctx, matcher)

        assertEquals(CommandRouting.Decision.Repeat(3), plan("重复三次"))
        assertEquals(CommandRouting.Decision.Repeat(5), plan("农夫五次"))     // v0.57.3 听岔三字形
        assertEquals(CommandRouting.Decision.Repeat(1), plan("再来一次"))
        // v0.57.8 宽松兜底：吞字形态「负三次」=3；前提=确有可重复动作且无编号/网格浮层
        assertEquals(CommandRouting.Decision.Repeat(3, recovered = true), plan("负三次", ctxWithLastAction))
        assertTrue(plan("负三次") !is CommandRouting.Decision.Repeat)         // 无可重复动作不算
        assertTrue(plan("负三次", ctxGrid) !is CommandRouting.Decision.Repeat) // 网格显示时短句是数字意图
        // 替换命令
        assertEquals(CommandRouting.Decision.Replace("不错", "很好"), plan("把不错替换成很好"))
        assertEquals(CommandRouting.Decision.Replace("错", "对"), plan("把错改成对"))
    }

    // ---------- 5. 长按 / 文字点击 ----------

    @Test fun `长按与文字点击路由回归组`() {
        fun plan(text: String) = CommandRouting.planCommand(text, ctxDefault, matcher)

        assertEquals(CommandRouting.Decision.LongPressNumber(12), plan("长按十二"))
        assertEquals(CommandRouting.Decision.LongPressText("抖音"), plan("长按抖音"))
        // 无参「长按」在产品端进待命模式（executor 路径）；路由层先给长按文字机会但不匹配
        assertTrue(plan("长按") !is CommandRouting.Decision.LongPressText)
        // 文字点击目标提取
        assertEquals(CommandRouting.Decision.TapText("抖音"), plan("打开抖音"))
        assertEquals(CommandRouting.Decision.TapText("抖音"), plan("点击抖音"))
    }

    // ---------- 6. 同分歧义（M2 新语义：不许兜底重猜） ----------

    @Test fun `同分歧义不被文字点击重新猜成动作`() {
        // 合成最小词表：两个 4 字词、指向相反动作，整句同时 contains 两者
        val json = """{"groups":[{"id":"g","name":"测试","commands":[
            {"id":"a","command":"显示网格","aliases":[],"action":"show_grid"},
            {"id":"b","command":"隐藏网格","aliases":[],"action":"hide_grid"}]}]}"""
        val m = CommandMatcher.fromJson(json)
        val outcome = m.matchStrictDetailed("显示网格隐藏网格")
        assertTrue("应判同分歧义", outcome.ambiguous)
        assertEquals(setOf("显示网格", "隐藏网格"), outcome.tiedWords.toSet())
        // 旧口径（matchStrict）继续返回 null，兼容既有调用方
        assertTrue(m.matchStrict("显示网格隐藏网格") == null)
        // runner 层：planCommand 给 Ambiguous，绝不掉进 TapText/其他
        val plan = CommandRouting.planCommand("显示网格隐藏网格", ctxDefault, m)
        assertTrue(plan is CommandRouting.Decision.Ambiguous)
    }

    @Test fun `自定义绑定优先不受歧义判定压制`() {
        // v0.39.0 既有规则：同分时自定义说法优先（用户显式意图）
        val m = CommandMatcher.fromJson(
            """{"groups":[{"id":"g","name":"测试","commands":[
              {"id":"a","command":"翻页","aliases":[],"action":"swipe_up"}]}]}""",
            customBindings = listOf("上一页" to "swipe_up"),
        )
        assertEquals("swipe_up", m.matchStrict("上一页")?.action)
    }

    // ---------- 7. 听写触发边界（在册命令优先，v0.57.12 用户拍板规则） ----------

    @Test fun `正式命令不被听写触发抢走`() {
        // 「删除」是正式命令（text_edit 组）——exact 命中就永远按命令走，不进听写考场
        assertNotNull(matcher.matchExact("删除"))
        assertTrue(matcher.matchExact("删除")!!.action == "text_delete")
        // 听写触发词本身不是词表词（「输入」被「清空输入」contains 命中但不算 exact 在册）
        assertTrue(matcher.matchExact("输入") == null)
        assertTrue(isDictationTrigger("输入"))
        assertTrue(isDictationTrigger("书入"))    // v0.55.12 同音容错
        assertFalse(isDictationTrigger("删除"))
        assertFalse(isDictationTrigger("向上滑动"))
    }

    @Test fun `已知模糊救回样本在正反序生产词表下结果一致且不歧义`() {
        // 收尾项2：matchFuzzy 同分不同动作已改为歧义（null）——历史救回样本必须不受影响，
        // 且词表顺序（正/反）不得改变结果。样本来自 FEATURES/CHANGELOG 实锤记录。
        // 注意「经变」不在此列：它在生产词表与「轻点」「静音」同分（见下一个用例，属预期变化）。
        val root = org.json.JSONObject(productionJson())
        val groups = root.getJSONArray("groups")
        val rGroups = org.json.JSONArray()
        for (gi in groups.length() - 1 downTo 0) {
            val g = groups.getJSONObject(gi)
            val cmds = g.getJSONArray("commands")
            val rCmds = org.json.JSONArray()
            for (ci in cmds.length() - 1 downTo 0) rCmds.put(cmds.get(ci))
            rGroups.put(org.json.JSONObject().put("id", g.getString("id")).put("commands", rCmds))
        }
        val reversedMatcher = CommandMatcher.fromJson(
            org.json.JSONObject().put("groups", rGroups).toString())

        val samples = mapOf(
            "经典" to "tap",       // v0.57.1 单半差（jing/qing 0.5，距轻点最近且唯一）
            "放回" to "go_back",   // 声母混淆家族（fang/fan 0.5，唯一）
        )
        for ((text, action) in samples) {
            val a = matcher.matchFuzzyDetailed(text)
            val b = reversedMatcher.matchFuzzyDetailed(text)
            assertFalse("[$text] 在生产词表出现同分模糊歧义：${a.tiedWords}", a.ambiguous)
            assertEquals("[$text] 正反序模糊结果不一致", a.match?.action, b.match?.action)
            assertEquals("[$text] 动作不符", action, a.match?.action)
        }
    }

    @Test fun `经变与轻点静音同分判歧义正反序一致（预期行为变化）`() {
        // 2026-09-29 收尾项2 的真实生产词表案例：「经变」(jing bian) 距「轻点」(qing dian)
        // 与「静音」(jing yin) 同为 1.0——旧实现按词表顺序命中先出现的「轻点」（碰巧对），
        // 倒序词表会命中「静音」（错）。现按用户指令改为明确歧义：正反序一致、静默忽略；
        // 用户如在意该形态，可用自定义指令绑定恢复（绑定豁免歧义判定）。
        val root = org.json.JSONObject(productionJson())
        val groups = root.getJSONArray("groups")
        val rGroups = org.json.JSONArray()
        for (gi in groups.length() - 1 downTo 0) {
            val g = groups.getJSONObject(gi)
            val cmds = g.getJSONArray("commands")
            val rCmds = org.json.JSONArray()
            for (ci in cmds.length() - 1 downTo 0) rCmds.put(cmds.get(ci))
            rGroups.put(org.json.JSONObject().put("id", g.getString("id")).put("commands", rCmds))
        }
        val reversedMatcher = CommandMatcher.fromJson(
            org.json.JSONObject().put("groups", rGroups).toString())

        val a = matcher.matchFuzzyDetailed("经变")
        val b = reversedMatcher.matchFuzzyDetailed("经变")
        assertTrue("正序应判歧义（轻点/静音同分），实际=$a", a.ambiguous)
        assertTrue("倒序应判歧义（顺序无关），实际=$b", b.ambiguous)
        assertEquals(setOf("轻点", "静音"), a.tiedWords.toSet())
        assertEquals(setOf("轻点", "静音"), b.tiedWords.toSet())
        // 绑定「经变」→轻点后恢复（自定义绑定豁免歧义，v0.39.0 显式意图规则）
        val withBinding = CommandMatcher.fromJson(productionJson(), customBindings = listOf("经变" to "tap"))
        val bound = withBinding.matchFuzzyDetailed("经变")
        assertFalse(bound.ambiguous)
        assertEquals("tap", bound.match?.action)
    }

    // ---------- 8. 二审门控与处置（M3/M5） ----------

    @Test fun `声音复核门控只在普通命令聆听且含触发字时请求`() {
        fun gate(text: String, enhanced: Boolean = true, dictation: Boolean = false,
                 capture: Boolean = false, longPress: Boolean = false) =
            AudioDecisionRouting.shouldRequestReview(text, enhanced, dictation, capture, longPress)

        // 增强关闭 = 零请求（OFF 基线：不加载不调用第二套模型）
        assertFalse(gate("增加音量", enhanced = false))
        // 普通聆听 + 含触发字（宽召回：ASR 残缺文本「机音量」也要覆盖）
        assertTrue(gate("增加音量"))
        assertTrue(gate("机音量"))
        assertTrue(gate("声音大一点"))
        // 听写内容/说法录入/长按待命：永远不会走到音量采纳分支，跳过（省最多 2.5s IPC）
        assertFalse(gate("把音量调大一点", dictation = true))
        assertFalse(gate("音量音乐", capture = true))
        assertFalse(gate("长按音量键", longPress = true))
        // 不含触发字 / 空句
        assertFalse(gate("返回"))
        assertFalse(gate(""))
    }

    @Test fun `二审处置标签分档`() {
        fun outcome(decision: String?, reason: String? = null) = AudioDecisionOutcome(decision, 0.9f, 800L, reason)
        val v = AudioDecisionRouting
        // 判决有效：改判直接写实际改到哪个动作，未改=已复核一致
        assertEquals("已修改为降低音量", v.dispositionLabel(v.verdictOf(outcome("dec")), "volume_up", "volume_down"))
        assertEquals("已复核一致", v.dispositionLabel(v.verdictOf(outcome("dec")), "volume_down", "volume_down"))
        // 无把握 / 不可用分档（低分≠模型理解其他命令，故障≠用户没说话）
        assertEquals("无把握，使用普通识别", v.dispositionLabel(v.verdictOf(outcome(null, "low_confidence")), "volume_up", "volume_up"))
        assertEquals("暂时不可用，使用普通识别", v.dispositionLabel(v.verdictOf(outcome(null, "ipc_timeout_or_dead")), "volume_up", "volume_up"))
        assertEquals("暂时不可用，使用普通识别", v.dispositionLabel(v.verdictOf(outcome(null, "not_ready")), "volume_up", "volume_up"))
        // 二分类头不能越权改其他动作（回归 AudioDecisionRoutingTest 的口径）
        assertEquals("hide_grid", v.correctedAction("hide_grid", true, "inc"))
        assertEquals("volume_up", v.correctedAction("volume_down", true, "inc"))
    }

    @Test fun `声音复核注册表与运行路由一致（收尾项4）`() {
        // 注册表是运行路由的真实守卫（correctedAction 范围/门控前置），不是纯文案：
        // 音量两动作在册可改写，其他动作一律不改写——与设置页能力文案同源
        assertTrue(AcousticReviewRegistry.coversAction("volume_up"))
        assertTrue(AcousticReviewRegistry.coversAction("volume_down"))
        assertFalse(AcousticReviewRegistry.coversAction("hide_grid"))
        assertFalse(AcousticReviewRegistry.coversAction("play_media"))
        assertFalse(AcousticReviewRegistry.coversAction("show_grid"))
        // 改写范围=注册范围（correctedAction 守卫实测）
        assertEquals("hide_grid", AudioDecisionRouting.correctedAction("hide_grid", true, "dec"))
        assertEquals("play_media", AudioDecisionRouting.correctedAction("play_media", true, "inc"))
        // 设置页能力文案与可调用模块一致：文案里必须含两个音量动作字样
        assertTrue(AcousticReviewRegistry.modules.isNotEmpty())
        val summary = AcousticReviewRegistry.userScopeSummary()
        assertTrue("能力文案未提及音量动作：$summary", summary.contains("音量增大") && summary.contains("音量减小"))
        // 注册的模块必须真的可被调用（音量模块 id 固定，远程进程按此口径实现 inc/dec）
        assertEquals(setOf("volume_up", "volume_down"), AcousticReviewRegistry.modules.single().actions)
    }

    // ---------- 9. 防误操作负例组（2026-09-29 全指令收敛轮） ----------
    // 每类指令除了正确说法，还要有相似闲话/错误数字/模式冲突不触发动作的负例——
    // 规则服务普通用户，不靠个人错字绑定换测试成绩。

    @Test fun `防误操作负例：替换缺臂、错误数字不裁剪、网格不劫持文字点击`() {
        fun plan(text: String, ctx: CommandRouting.UtteranceContext = ctxDefault) =
            CommandRouting.planCommand(text, ctx, matcher)

        // 替换缺右臂/缺把前缀 → 不是替换命令（落文字点击通道，由执行器按屏幕内容定夺）
        assertTrue(plan("把这段话改成") !is CommandRouting.Decision.Replace)
        assertTrue(plan("替换成很好") !is CommandRouting.Decision.Replace)
        assertTrue(plan("把不错替换成") !is CommandRouting.Decision.Replace)

        // 错误数字不裁剪：「点击99」在 20 个编号内不存在 → 保留 99 交执行器提示
        // 「没有编号99」，绝不把 99 裁成 9（任务书：不得把超范围数字裁成末位）
        assertEquals(
            CommandRouting.Decision.TapNumber(99),
            CommandRouting.planCommand("点击99", ctxLabels, matcher),
        )
        // 网格同理：超范围格号保留原值，由执行器拒绝
        assertEquals(CommandRouting.Decision.GridZoom(123), plan("网格123", ctxGrid))

        // 模式冲突：网格显示时无数字的文字点击不被网格通道劫持（网格只收数字意图）
        assertEquals(CommandRouting.Decision.TapText("抖音"), plan("点击抖音", ctxGrid))
        // 编号显示时同上（编号只收数字形态，文字点击照常）
        assertEquals(CommandRouting.Decision.TapText("抖音"), plan("点击抖音", ctxLabels))
    }

    @Test fun `防误操作负例：长按误触形态与听写内容命令样`() {
        // 「长按一下」不是长按编号（(?!下) 关卡，v0.25.5 设计）——路由层给长按文字"一下"，
        // 执行器找不到该文字后回退 strict 命中无参「长按」进待命（既有回退链）
        assertNull(CommandRouting.extractLongPressNumber("长按一下"))
        assertEquals(
            CommandRouting.Decision.LongPressText("一下"),
            CommandRouting.planCommand("长按一下", ctxDefault, matcher),
        )
        // 听写内容句含命令样照常落笔（内容优先于命令拦截，v0.40.0 设计）
        assertEquals(
            CommandRouting.DictationContentDecision.InsertText("点击抖音"),
            CommandRouting.planDictationContent("点击抖音"),
        )
        assertEquals(
            CommandRouting.DictationContentDecision.InsertText("重复三次"),
            CommandRouting.planDictationContent("重复三次"),
        )
    }
}
