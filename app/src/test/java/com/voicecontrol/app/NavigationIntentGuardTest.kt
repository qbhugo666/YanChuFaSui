package com.voicecontrol.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class NavigationIntentGuardTest {
    private fun file(path: String) = OptionalExperimentFiles.requireFile(path)
    private val commands by lazy { file("src/main/assets/commands.json").readText(Charsets.UTF_8) }
    private val matcher by lazy { CommandMatcher.fromJson(commands) }
    private val context = CommandRouting.UtteranceContext(false, false, true)

    private fun navigationWords(): List<String> {
        val groups = JSONObject(commands).getJSONArray("groups")
        return (0 until groups.length()).flatMap { g ->
            val list = groups.getJSONObject(g).getJSONArray("commands")
            (0 until list.length()).flatMap { i ->
                val command = list.getJSONObject(i)
                if (command.getString("action") !in NavigationIntentGuard.ACTIONS) emptyList()
                else {
                    val aliases = command.getJSONArray("aliases")
                    listOf(command.getString("command")) + (0 until aliases.length()).map { aliases.getString(it) }
                }
            }
        }
    }

    private fun candidate(text: String, m: CommandMatcher = matcher): CommandMatcher.Match? {
        return when (val plan = CommandRouting.planCommand(text, context, m)) {
            is CommandRouting.Decision.DispatchCommand -> plan.match
            is CommandRouting.Decision.TapText, is CommandRouting.Decision.NoMatch ->
                m.matchFuzzyDetailed(text).let { if (it.ambiguous) null else it.match }
            else -> null
        }
    }

    private fun guarded(text: String, m: CommandMatcher = matcher, enabled: Boolean = true,
                        normal: Boolean = true, execute: () -> Boolean): NavigationIntentGuard.Outcome {
        val match = requireNotNull(candidate(text, m)) { "No existing command: $text" }
        return NavigationIntentGuard.dispatch(text, match, enabled, normal, m.isCustomMatch(match), execute)
    }

    @Test fun allProductionNavigationWordsAndPoliteRequestsStillDispatchOnce() {
        for (word in navigationWords()) for (text in listOf(word, "请$word")) {
            var count = 0
            val result = guarded(text) { count++; true }
            assertNull(text, result.rejection)
            assertTrue(text, result.dispatched)
            assertEquals(text, 1, count)
        }
        for (text in listOf("帮我返回", "别的页面，请返回", "不要紧，返回")) {
            var count = 0
            assertTrue(text, guarded(text) { count++; true }.dispatched)
            assertEquals(text, 1, count)
        }
    }

    @Test fun negativeAndQuestionPhrasesNeverCallTheExecutor() {
        for (word in navigationWords()) {
            for (text in listOf("不要$word", "不能$word", "请勿$word", "别$word", "我不能$word")) {
                assertEquals(text, NavigationIntentGuard.Rejection.NEGATION,
                    guarded(text) { fail("Unexpected action: $text"); true }.rejection)
            }
            for (text in listOf("${word}吗", "怎么$word", "能否$word", "${word}？", "${word}是什么意思")) {
                assertEquals(text, NavigationIntentGuard.Rejection.QUESTION,
                    guarded(text) { fail("Unexpected action: $text"); true }.rejection)
            }
        }
        assertEquals(NavigationIntentGuard.Rejection.QUESTION,
            guarded("桌面在哪里") { fail("Home navigation"); true }.rejection)
    }

    @Test fun offProtectedModesAndIntentionalCustomBindingsKeepExistingBehavior() {
        for (text in listOf("不能返回", "桌面在哪里")) {
            for ((enabled, normal) in listOf(false to true, true to false, false to false)) {
                var count = 0
                val result = guarded(text, enabled = enabled, normal = normal) { count++; true }
                assertNull(result.rejection)
                assertTrue(result.dispatched)
                assertEquals(1, count)
            }
            val m = CommandMatcher.fromJson(commands, customBindings = listOf(text to "go_back"))
            val match = requireNotNull(candidate(text, m))
            assertTrue(m.isCustomMatch(match))
            var count = 0
            assertTrue(guarded(text, m) { count++; true }.dispatched)
            assertEquals(1, count)
        }
    }

    @Test fun nonNavigationExitAndNumericActionsAreOutsideTheGuard() {
        for (action in listOf("exit_session", "lock_screen", "volume_up", "volume_down", "show_labels",
            "grid_back", "tap_number_18", "text_cursor_left", "tap_text")) {
            val match = CommandMatcher.Match(action, action, "test", "不能返回", "exact")
            var count = 0
            val result = NavigationIntentGuard.dispatch("不能返回", match, true, true, false) { count++; true }
            assertNull(action, result.rejection)
            assertTrue(action, result.dispatched)
            assertEquals(action, 1, count)
        }
    }

    @Test fun allowedDispatchFailureDoesNotRetryAndFuzzyCannotBypassRejection() {
        var count = 0
        val result = guarded("返回") { count++; false }
        assertNull(result.rejection)
        assertFalse(result.dispatched)
        assertEquals(1, count)
        for (method in listOf("exact", "contains", "pinyin_exact", "pinyin_fuzzy")) {
            val match = CommandMatcher.Match("go_back", "go_back", "basic_navigation", "返回", method)
            assertEquals(NavigationIntentGuard.Rejection.NEGATION,
                NavigationIntentGuard.dispatch("不能返回", match, true, true, false) { fail(method); true }.rejection)
        }
    }

    @Test fun cachedReplayChecksActualProductionCandidatesAndReportsConditionsSeparately() {
        val source = file("_test/m6/number_acoustic_v3/out/navigation/full_fresh.json")
        val rows = JSONArray(source.readText(Charsets.UTF_8))
        val out = JSONArray()
        var corrected = 0
        var harmed = 0
        var offChanged = 0
        var beforeControlNav = 0
        var afterControlNav = 0
        var navBefore = 0
        var navAfter = 0
        val conditions = mutableSetOf<String>()
        val raw = mutableSetOf<String>()
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val text = row.getString("asr_text")
            val old = row.getString("baseline")
            var new = old
            var off = old
            if (old in NavigationIntentGuard.ACTIONS) {
                val matched = requireNotNull(candidate(text)) { "Cache drift: $text -> $old" }
                assertEquals("Cached navigation is still the production candidate: $text", old, matched.action)
                val actual = NavigationIntentGuard.dispatch(text, matched, true, true,
                    matcher.isCustomMatch(matched)) { true }
                new = if (actual.rejection != null) "no_action" else old
                val disabled = NavigationIntentGuard.dispatch(text, matched, false, true,
                    matcher.isCustomMatch(matched)) { true }
                off = if (disabled.rejection != null) "no_action" else old
            }
            val label = row.getString("label")
            if (off != old) offChanged++
            if (label in NavigationIntentGuard.ACTIONS && old == label && new != label) harmed++
            if (label == "other" && old in NavigationIntentGuard.ACTIONS && new == "no_action") {
                corrected++
                conditions.add(row.getString("case_id"))
                raw.add(row.getString("case_id").substringBeforeLast("-"))
            }
            if (row.getString("text_status") == "NOT_FOUND") {
                if (label == "other" && old in NavigationIntentGuard.ACTIONS) beforeControlNav++
                if (label == "other" && new in NavigationIntentGuard.ACTIONS) afterControlNav++
                if (label in NavigationIntentGuard.ACTIONS && old == label) navBefore++
                if (label in NavigationIntentGuard.ACTIONS && new == label) navAfter++
            }
            out.put(JSONObject(row.toString()).put("guarded", new).put("guard_changed", new != old))
        }
        assertEquals(0, harmed)
        assertEquals(0, offChanged)
        assertEquals(navBefore, navAfter)
        assertEquals(36, beforeControlNav)
        assertEquals(7, afterControlNav)
        assertEquals(29, conditions.size)
        assertEquals(6, raw.size)
        val dir = File(if (File("_test").isDirectory) "_test/m6/navigation_intent_guard" else "../_test/m6/navigation_intent_guard")
        dir.mkdirs()
        dir.resolve("replay.json").writeText(out.toString(1), Charsets.UTF_8)
        dir.resolve("summary.json").writeText(JSONObject().put("contexts", rows.length())
            .put("corrected_contexts", corrected).put("corrected_conditions", conditions.size)
            .put("corrected_raw", raw.size).put("original_correct_harmed", harmed).put("off_changed", offChanged)
            .put("not_found_control_nav_before", beforeControlNav).put("not_found_control_nav_after", afterControlNav)
            .put("navigation_correct_before", navBefore).put("navigation_correct_after", navAfter)
            .put("source_is_known_synthetic_development", true).toString(1), Charsets.UTF_8)
    }
}
