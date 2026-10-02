package com.voicecontrol.app

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.File

class AudioDecisionRoutingTest {
    private fun productionJson(): String = listOf(File("src/main/assets/commands.json"),
        File("app/src/main/assets/commands.json")).first { it.isFile }.readText(Charsets.UTF_8)

    private fun productionMatch(text: String) = CommandMatcher.fromJson(productionJson()).matchStrict(text)!!

    private fun outcome(decision: String?, probability: Float = 0.93748826f) =
        AudioDecisionOutcome(decision, probability, 523L, if (decision == null) "low_confidence" else null)

    @Test fun `关闭增强识别时保留原命令`() {
        assertEquals("volume_up", AudioDecisionRouting.correctedAction("volume_up", false, "dec"))
    }

    @Test fun `二审失败时保留原命令`() {
        assertEquals("volume_up", AudioDecisionRouting.correctedAction("volume_up", true, null))
    }

    @Test fun `二审可纠正相反音量方向`() {
        assertEquals("volume_down", AudioDecisionRouting.correctedAction("volume_up", true, "dec"))
        assertEquals("volume_up", AudioDecisionRouting.correctedAction("volume_down", true, "inc"))
    }

    @Test fun `二审分类头不能改动音量以外的命令`() {
        assertEquals("hide_grid", AudioDecisionRouting.correctedAction("hide_grid", true, "inc"))
    }

    @Test fun `真实增加音量误改回归_执行器只收到增加一次`() {
        val calls = mutableListOf<String>()
        val result = AudioDecisionRouting.dispatchVolume("增加音量", productionMatch("增加音量"),
            true, outcome("dec"), false) { calls.add(it); true }
        assertEquals(listOf("volume_up"), calls)
        assertTrue(result.dispatched)
        assertTrue(result.resolution.overrideBlocked)
        assertEquals("建议降低音量，未采用", result.resolution.label)
        assertTrue(result.resolution.description.contains("二审建议=volume_down"))
        assertTrue(result.resolution.description.contains("实际=volume_up"))
        val consistent = AudioDecisionRouting.resolveVolume("增加音量", productionMatch("增加音量"),
            true, outcome("inc", 0.9026509f), false)
        assertEquals("volume_up", consistent.action)
        assertEquals("已复核一致", consistent.label)
    }

    @Test fun `生产音量主词及别名完整命中不被声音反转_含完整词前后缀`() {
        val json = productionJson()
        val matcher = CommandMatcher.fromJson(json)
        val groups = JSONObject(json).getJSONArray("groups")
        var checked = 0
        for (i in 0 until groups.length()) {
            val commands = groups.getJSONObject(i).getJSONArray("commands")
            for (j in 0 until commands.length()) {
                val command = commands.getJSONObject(j)
                val action = command.getString("action")
                if (action !in setOf("volume_up", "volume_down")) continue
                val aliases = command.getJSONArray("aliases")
                val words = listOf(command.getString("command")) +
                    (0 until aliases.length()).map { aliases.getString(it) }
                for (word in words) for (text in listOf(word, "请${word}吧")) {
                    val match = matcher.matchStrict(text)!!
                    val resolution = AudioDecisionRouting.resolveVolume(text, match, true,
                        outcome(if (action == "volume_up") "dec" else "inc", 0.999f), false)
                    assertEquals(text, action, resolution.action)
                    assertTrue(text, resolution.overrideBlocked)
                    checked++
                }
            }
        }
        assertEquals(32, checked)
    }

    @Test fun `残缺文字的音量候选仍能声音改判_不将反向contains当完整命令`() {
        val up = productionMatch("增加音量")
        for ((text, match) in listOf("音量" to up.copy(method = "contains"),
            "机音量" to up.copy(method = "pinyin_fuzzy"))) {
            val calls = mutableListOf<String>()
            val result = AudioDecisionRouting.dispatchVolume(text, match, true, outcome("dec"), false) {
                calls.add(it); true
            }
            assertEquals(listOf("volume_down"), calls)
            assertFalse(result.resolution.overrideBlocked)
            assertEquals("已修改为降低音量", result.resolution.label)
        }
    }

    @Test fun `关闭增强_自定义绑定_二审回退保留原命令`() {
        val partial = productionMatch("增加音量").copy(method = "pinyin_fuzzy")
        assertEquals("volume_up", AudioDecisionRouting.resolveVolume("机音量", partial,
            false, outcome("dec"), false).action)
        val custom = AudioDecisionRouting.resolveVolume("机音量", partial, true, outcome("dec"), true)
        assertEquals("volume_up", custom.action)
        assertTrue(custom.description.contains("自定义绑定优先"))
        assertEquals("volume_up", AudioDecisionRouting.resolveVolume("机音量", partial,
            true, outcome(null), false).action)
        assertEquals("volume_up", AudioDecisionRouting.resolveVolume("机音量", partial,
            true, null, false).action)
    }

    @Test fun `音量派发失败不执行第二方向_旧标签只表示改判`() {
        val calls = mutableListOf<String>()
        val result = AudioDecisionRouting.dispatchVolume("降低音量", productionMatch("降低音量"),
            true, outcome("inc"), false) { calls.add(it); false }
        assertEquals(listOf("volume_down"), calls)
        assertFalse(result.dispatched)
        assertEquals("按声音改判", AudioDecisionRouting.displayLabel("已纠正"))
        assertEquals("已修改为降低音量", AudioDecisionRouting.displayLabel("已纠正",
            "候选=volume_up；二审改为=volume_down；结果=⚠️ 已是最低音量"))
        assertEquals("已修改为增加音量", AudioDecisionRouting.displayLabel("按声音改为增加音量"))
        assertEquals("按声音改判", AudioDecisionRouting.displayLabel("已纠正", "二审=dec；未采纳"))
        assertEquals("已复核一致", AudioDecisionRouting.displayLabel("已复核一致"))
    }

    @Test fun `真实分歧保留增加_执行方向和结果提示一致`() {
        val calls = mutableListOf<String>()
        val result = AudioDecisionRouting.dispatchVolume("增加音量", productionMatch("增加音量"),
            true, outcome("dec"), false) { calls.add(it); true }
        assertEquals(listOf("volume_up"), calls)
        assertEquals("→ 增加音量 ✅ 已执行", AudioDecisionRouting.volumeResultText(result, null))
        assertEquals("⚡ 执行：增加音量", AudioDecisionRouting.volumeBarText(result, null))
        assertEquals("→ 增加音量 · ⚠️ 已达安全音量上限（80%），不再增大",
            AudioDecisionRouting.volumeResultText(result, "⚠️ 已达安全音量上限（80%），不再增大"))
        assertEquals("⚡ 执行：增加音量 · ⚠️ 已达安全音量上限（80%），不再增大",
            AudioDecisionRouting.volumeBarText(result, "⚠️ 已达安全音量上限（80%），不再增大"))
    }

    @Test fun `残缺句按声音降低_最低提示仍包含实际派发方向`() {
        val calls = mutableListOf<String>()
        val partial = productionMatch("增加音量").copy(method = "contains")
        val result = AudioDecisionRouting.dispatchVolume("音量", partial, true, outcome("dec"), false) {
            calls.add(it); true
        }
        assertEquals(listOf("volume_down"), calls)
        assertEquals("已修改为降低音量", result.resolution.label)
        assertEquals("→ 降低音量 · ⚠️ 已是最低音量",
            AudioDecisionRouting.volumeResultText(result, "⚠️ 已是最低音量"))
        assertEquals("⚡ 执行：降低音量", AudioDecisionRouting.volumeBarText(result, null))
        assertEquals("⚡ 执行：降低音量 · ⚠️ 已是最低音量",
            AudioDecisionRouting.volumeBarText(result, "⚠️ 已是最低音量"))
    }

    @Test fun `音量派发被拒绝_不把残留提示显示成成功或另一个方向`() {
        for (enabled in listOf(false, true)) for (text in listOf("增加音量", "降低音量")) {
            val calls = mutableListOf<String>()
            val match = productionMatch(text)
            val result = AudioDecisionRouting.dispatchVolume(text, match, enabled,
                outcome(if (match.action == "volume_up") "dec" else "inc"), false) {
                calls.add(it); false
            }
            assertEquals(listOf(match.action), calls)
            assertEquals("→ $text（未执行）",
                AudioDecisionRouting.volumeResultText(result, "⚠️ 已是最低音量"))
            assertEquals("🎤 $text（未执行）",
                AudioDecisionRouting.volumeBarText(result, "⚠️ 已是最低音量"))
        }
    }
}
