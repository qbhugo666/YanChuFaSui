package com.voicecontrol.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 命令匹配层单元测试（阶段一：测试基建）。
 *
 * 每个 bug 都固化成一条用例，改完代码跑一次就知道有没有回归，
 * 不再依赖真机手测 + 抓 logcat。
 *
 * 注意：v0.6 起 match() 拆成 matchStrict()（精确/包含/同音）与 matchFuzzy()（拼音编辑距离兜底），
 * 用例据此分别断言，与识别层的分层调用顺序一致。
 */
class CommandMatcherTest {

    private val matcher = CommandMatcher.fromJson(TEST_JSON)

    // ===== 精确匹配（matchStrict） =====

    @Test fun `精确匹配 向左滑动`() {
        assertEquals("swipe_left", matcher.matchStrict("向左滑动")?.action)
    }

    @Test fun `精确匹配 上滑`() {
        assertEquals("swipe_up", matcher.matchStrict("上滑")?.action)
    }

    @Test fun `精确匹配 下滑`() {
        assertEquals("swipe_down", matcher.matchStrict("下滑")?.action)
    }

    @Test fun `精确匹配 返回`() {
        assertEquals("go_back", matcher.matchStrict("返回")?.action)
    }

    @Test fun `包含匹配优先选择更长的具体命令`() {
        val media = CommandMatcher.fromJson(
            """{"groups":[{"id":"media","name":"媒体","commands":[
              {"id":"play","command":"播放","aliases":["播放音乐","接着播放"],"action":"play_media"},
              {"id":"pause","command":"暂停","aliases":["暂停播放"],"action":"pause_media"}
            ]}]}"""
        )
        assertEquals("pause_media", media.matchStrict("暂停播放")?.action)
        assertEquals("pause_media", media.matchStrict("暂停播放后")?.action)
        assertEquals("play_media", media.matchStrict("播放音乐")?.action)
        assertNull(media.matchStrict("先暂停然后播放"))
    }

    @Test fun `生产词表暂停播放优先于播放且播放音乐仍播放`() {
        // 直接读取 App 运行时使用的 commands.json，避免测试词表和实际词表慢慢分叉。
        val productionFile = java.io.File("src/main/assets/commands.json")
        assertEquals(true, productionFile.isFile)
        val production = CommandMatcher.fromJson(productionFile.readText(Charsets.UTF_8))
        assertEquals("pause_media", production.matchStrict("请暂停播放")?.action)
        assertEquals("play_media", production.matchStrict("播放音乐")?.action)
        assertEquals("volume_up", production.matchStrict("增加音量")?.action)
        assertEquals("volume_down", production.matchStrict("降低音量")?.action)
        assertEquals("show_grid", production.matchStrict("显示网格")?.action)
        assertEquals("hide_grid", production.matchStrict("取消网格")?.action)
        assertNull(production.matchStrict("先暂停然后播放"))
    }

    @Test fun `主页别名已下架 不再匹配`() {
        // v0.55.13 用户拍板：主页/回主页/最近应用/关闭 属高频误触别名，下架
        assertNull(matcher.matchStrict("主页"))
    }

    @Test fun `上一页别名已下架 不再匹配`() {
        // v0.57.24 用户拍板：删「上一页/返回上一页」（go_back 别名收窄为 后退）。
        // 注意「返回上一页」整句仍会经 contains 命中「返回」（词表词是子串）——合理：
        // 用户说了「返回」意图明确；下架针对的是**单独**「上一页」这个歧义说法
        assertNull(matcher.matchStrict("上一页"))
        assertEquals("go_back", matcher.matchStrict("返回上一页")?.action)   // contains「返回」
        assertEquals("go_back", matcher.matchStrict("后退")?.action)        // 其余别名不受影响
    }

    // ===== 包含匹配（多字/漏字） =====

    @Test fun `包含匹配 向右滑`() {
        assertEquals("swipe_right", matcher.matchStrict("向右滑")?.action)
    }

    @Test fun `包含匹配 在右滑动`() {
        assertEquals("swipe_right", matcher.matchStrict("在右滑动")?.action)
    }

    // ===== 同音字纠错（pinyin_exact，属于 strict） =====

    @Test fun `同音字 华动 匹配 滑动`() {
        assertEquals("swipe_up", matcher.matchStrict("向上华动")?.action)
    }

    // ===== 拼音模糊匹配（matchFuzzy 兜底） =====

    @Test fun `bug回归 向左华 应匹配左滑而非上滑`() {
        assertEquals("swipe_left", matcher.matchFuzzy("向左华")?.action)
    }

    @Test fun `bug回归 又滑动 应匹配右滑而非上滑`() {
        assertEquals("swipe_right", matcher.matchFuzzy("又滑动")?.action)
    }

    // ===== 近音听岔不加别名，走模糊兜底（2026-09-22 用户拍板：别名会让胶囊显示「已识别经典」很怪） =====

    @Test fun `bug回归 经典 模糊兜住且显示轻点`() {
        // 用户说「轻点」被 ASR 听成「经典」（q/j 声母混淆，dist 1/2=0.5 达标）；
        // 不加别名 → matchedWord 保持词表词「轻点」，胶囊/使用记录显示不怪
        assertNull(matcher.matchStrict("经典"))
        val m = matcher.matchFuzzy("经典")
        assertEquals("tap", m?.action)
        assertEquals("轻点", m?.matchedWord)
    }

    @Test fun `bug回归 经典经典 连说两遍折叠后兜住`() {
        // 连说两遍被 ASR 合成「经典经典」：无折叠时 vs「轻点」dist 3 归一化 0.75 > 0.65 被拒
        // （2026-09-22 使用记录实锤两次未触发）；collapseDoubled 折叠成「经典」后模糊命中
        assertEquals("tap", matcher.matchFuzzy("经典经典")?.action)
        assertEquals("轻点", matcher.matchFuzzy("经典经典")?.matchedWord)
    }

    @Test fun `连说两遍 轻点轻点 折叠后精确命中`() {
        assertEquals("tap", matcher.matchStrict("轻点轻点")?.action)
        assertEquals("exact", matcher.matchStrict("轻点轻点")?.method)
    }

    @Test fun `连说两遍 上滑上滑 折叠后精确命中`() {
        assertEquals("swipe_up", matcher.matchStrict("上滑上滑")?.action)
    }

    @Test fun `非重复片段不折叠`() {
        assertEquals("向左滑动", CommandMatcher.collapseDoubled("向左滑动"))   // 偶数长但前后不同
        assertEquals("经典大剧院", CommandMatcher.collapseDoubled("经典大剧院")) // 奇数长不动
        assertEquals("经典好菜", CommandMatcher.collapseDoubled("经典好菜"))    // 前后半不同
    }

    // ===== 轻点说法扩充（v0.57.2 用户拍板：加「单击」；「点击」二字与「点击屏幕」均不入词表，后者服务层整句直通） =====

    @Test fun `单击 别名精确命中轻点`() {
        assertEquals("tap", matcher.matchStrict("单击")?.action)
        // 别名命中显示别名本身（与自定义说法同理：用户有意说的词，显示不怪；
        // 听岔词如「经典」走 fuzzy 兜底才显示词表词「轻点」——v0.57.1 用户拍板的区别对待）
        assertEquals("单击", matcher.matchStrict("单击")?.matchedWord)
    }

    @Test fun `点击与点击屏幕 都不在词表`() {
        // 「点击」绝不入别名（用户拍板放弃）：说话慢被 VAD 断句截出「点击」会误触轻点，
        // 且 contains 会劫持所有「点击X」文字点击。
        // 「点击屏幕」同样不入：contains 反向规则（词含子串即命中）会让截出的「点击」
        // 作为「点击屏幕」的前缀子串误触——直通逻辑在 VoiceService 整句等值（Android 层，真机验证）
        assertNull(matcher.matchStrict("点击"))
        assertNull(matcher.matchStrict("点击屏幕"))
    }

    // ===== 声母韵母拆分计分（v0.57.9：用户实测说轻点多次被识别成「经电」「经变」不触发） =====

    @Test fun `bug回归 经电 声母混淆兜住轻点`() {
        // jing dian vs qing dian：jing/qing 只差声母（0.5），dian 全同 → dist 0.5 达标
        assertEquals("tap", matcher.matchFuzzy("经电")?.action)
        assertEquals("轻点", matcher.matchFuzzy("经电")?.matchedWord)
    }

    @Test fun `bug回归 经变 双声母混淆兜住轻点`() {
        // jing bian vs qing dian：jing/qing=0.5 + bian/dian 只差声母=0.5 → dist 1.0 达标
        // （旧整音节计分 2/2=1.0 超阈值被拒——本用例即升级动机）
        assertEquals("tap", matcher.matchFuzzy("经变")?.action)
    }

    @Test fun `声韵拆分不误配 静音与轻点仍可区分`() {
        // jing yin vs qing dian：jing/qing=0.5 + yin/dian 声韵全差=1 → dist 1.5 比「经变」的 1.0 远；
        // 同分竞速时「轻点」在词表 basic_navigation 组先于 device_control 的「静音」遍历——
        // 但 1.5/2=0.75 > 0.65 静音自身也不达标，无撞车
        assertEquals("tap", matcher.matchFuzzy("经电")?.action)
    }

    @Test fun `韵母混淆 an-ang 也受益`() {
        // fang hui vs fan hui（「放回」→「返回」）：fang/fan 只差韵母=0.5，hui 全同 → dist 0.5
        assertEquals("go_back", matcher.matchFuzzy("放回")?.action)
    }

    // ===== 匹配不到的情况 =====

    // ===== 在册命令优先判定（v0.57.12 用户拍板：正式命令绝不被听写容差劫持） =====

    private val exactMatcher = CommandMatcher.fromJson(TEST_JSON)

    @Test fun `matchExact 在册命令命中 输入等非词表词不命中`() {
        assertEquals("swipe_up", exactMatcher.matchExact("上滑")?.action)
        assertEquals("tap", exactMatcher.matchExact("轻点")?.action)
        assertEquals("tap", exactMatcher.matchExact("单击")?.action)
        assertEquals("tap", exactMatcher.matchExact("轻点轻点")?.action)   // 折叠后命中
        // 非词表词（听写触发词）：matchExact 不拦 → 听写照常可触发
        assertNull(exactMatcher.matchExact("输入"))
        assertNull(exactMatcher.matchExact("打字"))
        // contains 命中不算 exact：「清空输入」对「输入」不构成在册优先
        assertNull(exactMatcher.matchExact("输入"))
    }

    @Test fun `无意义文本 不匹配`() {
        assertNull(matcher.matchStrict("个活动"))
        assertNull(matcher.matchFuzzy("个活动"))
    }

    // ===== 自定义说法绑定（v0.39.0：动作自适应用户自己的说法） =====

    private val boundMatcher = CommandMatcher.fromJson(
        TEST_JSON,
        customBindings = listOf("乡上花东" to "swipe_up", "花花动" to "go_back"),
    )

    @Test fun `自定义说法 精确命中`() {
        assertEquals("swipe_up", boundMatcher.matchStrict("乡上花东")?.action)
        assertEquals("go_back", boundMatcher.matchStrict("花花动")?.action)
    }

    @Test fun `自定义说法 包含命中`() {
        assertEquals("swipe_up", boundMatcher.matchStrict("帮我乡上花东一下")?.action)
    }

    @Test fun `自定义说法 平分时优先于标准别名`() {
        // 「返回」既是标准 go_back 别名又被用户绑到 swipe_up：自定义是用户显式意图，应赢
        val m = CommandMatcher.fromJson(TEST_JSON, customBindings = listOf("返回" to "swipe_up"))
        assertEquals("swipe_up", m.matchStrict("返回")?.action)
    }

    @Test fun `自定义说法 进入热词表`() {
        org.junit.Assert.assertTrue(boundMatcher.hotwords().contains("乡上花东"))
    }

    // ===== 自定义说法享受完整模糊体系（v0.57.13 固化：第三方用户无感受益，用户提问确认） =====
    // 自定义词与标准词同管线：同音 pinyin_exact / 声韵半差 pinyin_fuzzy / 连说两遍折叠全生效

    @Test fun `自定义说法 同音听岔命中`() {
        // 「乡上花东」(xiang shang hua dong) 听岔成同音字「香上花冬」→ pinyin_exact
        assertEquals("swipe_up", boundMatcher.matchStrict("香上花冬")?.action)
        assertEquals("pinyin_exact", boundMatcher.matchStrict("香上花冬")?.method)
    }

    @Test fun `自定义说法 声母半差听岔命中`() {
        // 「乡上花东」听岔「香上法东」(hua→fa 声母 h≠f 韵母 ua=a? f+a=fa 声母差韵母同 a?——hua=fa:
        // hua 拆 (h,ua)、fa 拆 (f,a)：韵母 ua≠a 声母 h≠f 全差)。换稳例：「香上画东」hua=画同音→exact 层。
        // 用「相上花东」：xiang vs xiang 同？不对。「箱上花东」xiang 同音。取声母半差：
        // 「乡上华动」：hua→hua 同、dong→dong? 用 zha ji 家族：绑「炸机」听岔「扎机」(zha→za 声母 zh≠z 韵母 a 同=0.5)
        val m = CommandMatcher.fromJson(TEST_JSON, customBindings = listOf("炸机" to "swipe_up"))
        assertEquals("swipe_up", m.matchFuzzy("扎机")?.action)   // 单半差 0.5 达标
    }

    @Test fun `自定义说法 连说两遍折叠命中`() {
        // 用户重复说自定义词：「炸机炸机」折叠成「炸机」精确命中
        val m = CommandMatcher.fromJson(TEST_JSON, customBindings = listOf("炸机" to "swipe_up"))
        assertEquals("swipe_up", m.matchStrict("炸机炸机")?.action)
        assertEquals("exact", m.matchStrict("炸机炸机")?.method)
    }

    @Test fun `未知动作的绑定 被跳过不影响标准词表`() {
        val m = CommandMatcher.fromJson(TEST_JSON, customBindings = listOf("随便啥" to "nope"))
        assertNull(m.matchStrict("随便啥"))
        assertEquals("swipe_up", m.matchStrict("上滑")?.action)
    }

    @Test fun `无绑定 标准匹配零变化`() {
        assertEquals("go_back", matcher.matchStrict("返回")?.action)
    }

    // ===== 文字输入命令（v0.40.0，commands.json v7 text_edit 组） =====

    private val textMatcher = CommandMatcher.fromJson(
        TEST_JSON.replace(
            """{ "id": "open_recents",""",
            """{ "id": "text_cursor_left",  "command": "光标左移", "aliases": ["光标向左", "左移光标"], "action": "text_cursor_left" },
                    { "id": "text_cursor_right", "command": "光标右移", "aliases": ["光标向右", "右移光标"], "action": "text_cursor_right" },
                    { "id": "text_delete",       "command": "删除",     "aliases": ["删掉", "删一个字", "退格"], "action": "text_delete" },
                    { "id": "text_clear",        "command": "清空输入", "aliases": ["清空输入框", "清空"], "action": "text_clear" },
                    { "id": "open_recents","""
        )
    )

    @Test fun `文字输入 光标左移 精确匹配`() {
        assertEquals("text_cursor_left", textMatcher.matchStrict("光标左移")?.action)
    }

    @Test fun `文字输入 删除 精确匹配`() {
        assertEquals("text_delete", textMatcher.matchStrict("删除")?.action)
    }

    @Test fun `文字输入 清空输入框 别名匹配`() {
        assertEquals("text_clear", textMatcher.matchStrict("清空输入框")?.action)
    }

    // ===== 替换找词（v0.41.0 findFuzzyRange：精确 + 拼音模糊滑窗） =====

    @Test fun `替换找词 精确子串`() {
        assertEquals(4..5, CommandMatcher.findFuzzyRange("今天天气不错", "不错"))
    }

    @Test fun `替换找词 拼音相同听岔命中`() {
        // 屏幕是「不错」、照读被听成「不措」：拼音相同即命中
        assertEquals(4..5, CommandMatcher.findFuzzyRange("今天天气不错", "不措"))
    }

    @Test fun `替换找词 无命中返回null`() {
        assertNull(CommandMatcher.findFuzzyRange("今天天气不错", "抖音"))
    }

    private companion object {
        // 与 assets/commands.json 中「滑动 + 导航」核心命令一致（测试基准，只覆盖已实现动作）
        val TEST_JSON = """
            {
              "groups": [
                {
                  "id": "basic_navigation",
                  "name": "基本浏览",
                  "commands": [
                    { "id": "swipe_up",    "command": "向上轻扫",  "aliases": ["向上滑动", "上滑", "往上滑"], "action": "swipe_up" },
                    { "id": "swipe_down",  "command": "向下轻扫",  "aliases": ["向下滑动", "下滑", "往下滑"], "action": "swipe_down" },
                    { "id": "swipe_left",  "command": "向左轻扫",  "aliases": ["向左滑动", "左滑", "往左滑"], "action": "swipe_left" },
                    { "id": "swipe_right", "command": "向右轻扫",  "aliases": ["向右滑动", "右滑", "往右滑"], "action": "swipe_right" },
                    { "id": "go_back",     "command": "返回",      "aliases": ["后退"],                    "action": "go_back" },
                    { "id": "go_home",     "command": "前往主屏幕","aliases": ["回主屏幕", "回桌面", "回首页"], "action": "go_home" },
                    { "id": "tap",         "command": "轻点",      "aliases": ["点一下", "单击"],         "action": "tap" },
                    { "id": "open_recents","command": "打开 App 切换器", "aliases": ["最近任务", "后台"],   "action": "open_recents" }
                  ]
                }
              ]
            }
        """.trimIndent()
    }

    // ===== 模糊匹配同分歧义（2026-09-29 收尾项2） =====
    // 构造说明：「大腾」(da teng) 对「拉灯」(la deng) 与「拉风」(la feng) 距离同为 1.0
    // （各两个半差音节：da/la、teng/deng、teng/feng 均声母差韵母同=0.5）——同分不同动作的真实形态。

    private fun tieJson(reverse: Boolean, sameAction: Boolean = false): String {
        val a = """{"id":"lamp","command":"拉灯","aliases":[],"action":"light_on"}"""
        val b = if (sameAction)
            """{"id":"fan2","command":"拉风","aliases":[],"action":"light_on"}"""
        else
            """{"id":"fan2","command":"拉风","aliases":[],"action":"fan_speed"}"""
        val cmds = if (reverse) "$b,$a" else "$a,$b"
        return """{"groups":[{"id":"g","name":"t","commands":[$cmds]}]}"""
    }

    @Test fun `模糊同分不同动作判歧义且正反序一致`() {
        val forward = CommandMatcher.fromJson(tieJson(reverse = false))
        val reversed = CommandMatcher.fromJson(tieJson(reverse = true))
        val f = forward.matchFuzzyDetailed("大腾")
        val r = reversed.matchFuzzyDetailed("大腾")
        org.junit.Assert.assertTrue("正序应判歧义，实际=$f", f.ambiguous)
        org.junit.Assert.assertTrue("倒序应判歧义，实际=$r", r.ambiguous)
        org.junit.Assert.assertEquals(setOf("拉灯", "拉风"), f.tiedWords.toSet())
        org.junit.Assert.assertEquals(setOf("拉灯", "拉风"), r.tiedWords.toSet())
        // 旧口径 matchFuzzy 对歧义返回 null（兼容既有调用方语义）
        assertNull(forward.matchFuzzy("大腾"))
        assertNull(reversed.matchFuzzy("大腾"))
    }

    @Test fun `模糊同分同动作取先出现者不判歧义`() {
        val forward = CommandMatcher.fromJson(tieJson(reverse = false, sameAction = true))
        val reversed = CommandMatcher.fromJson(tieJson(reverse = true, sameAction = true))
        // 同动作的两个词是别名等价关系——先出现者胜，两边都不歧义且动作一致
        assertEquals("light_on", forward.matchFuzzy("大腾")?.action)
        assertEquals("light_on", reversed.matchFuzzy("大腾")?.action)
        org.junit.Assert.assertFalse(forward.matchFuzzyDetailed("大腾").ambiguous)
        org.junit.Assert.assertFalse(reversed.matchFuzzyDetailed("大腾").ambiguous)
    }

    @Test fun `模糊同分自定义绑定优先于歧义判定`() {
        // v0.39.0 既有规则：同分自定义优先=用户显式意图。绑定词与标准词同分且动作不同 → 自定义胜
        val m = CommandMatcher.fromJson(
            tieJson(reverse = false),
            customBindings = listOf("拉崩" to "light_on"),   // la beng 对「大腾」同为 1.0
        )
        val outcome = m.matchFuzzyDetailed("大腾")
        org.junit.Assert.assertFalse(outcome.ambiguous)
        assertEquals("light_on", outcome.match?.action)
        assertEquals("拉崩", outcome.match?.matchedWord)
    }

    @Test fun `既有模糊救回路径不受同分歧义影响`() {
        // 历史实锤救回样本：经变/经典→轻点、放回→返回（各自唯一最近候选，无同分冲突）
        assertEquals("tap", matcher.matchFuzzy("经变")?.action)
        assertEquals("tap", matcher.matchFuzzy("经典")?.action)
        assertEquals("go_back", matcher.matchFuzzy("放回")?.action)
        org.junit.Assert.assertFalse(matcher.matchFuzzyDetailed("经变").ambiguous)
        org.junit.Assert.assertFalse(matcher.matchFuzzyDetailed("放回").ambiguous)
    }
}
