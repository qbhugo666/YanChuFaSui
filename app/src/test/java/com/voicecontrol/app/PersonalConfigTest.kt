package com.voicecontrol.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份导入布尔偏好安全读取回归（2026-09-29 收尾项1）：
 * 只有 JSON 值确实是布尔才导入；字段缺失/类型错误保持现值（返回 null），
 * 坏备份不得把用户开着的功能无声关闭（旧写法 optBoolean(key,false) 会把 "true"/1/null 全当 false）。
 */
class PersonalConfigTest {

    private fun prefsOf(json: String): org.json.JSONObject = org.json.JSONObject(json)

    @Test fun `真布尔正常读取`() {
        assertEquals(true, PersonalConfig.booleanOrNull(prefsOf("""{"k":true}"""), "k"))
        assertEquals(false, PersonalConfig.booleanOrNull(prefsOf("""{"k":false}"""), "k"))
    }

    @Test fun `字段缺失返回null保持现值`() {
        assertNull(PersonalConfig.booleanOrNull(prefsOf("""{"other":1}"""), "k"))
        assertNull(PersonalConfig.booleanOrNull(prefsOf("""{}"""), "k"))
    }

    @Test fun `类型错误一律返回null不当作false`() {
        assertNull(PersonalConfig.booleanOrNull(prefsOf("""{"k":"true"}"""), "k"))   // 字符串
        assertNull(PersonalConfig.booleanOrNull(prefsOf("""{"k":"yes"}"""), "k"))
        assertNull(PersonalConfig.booleanOrNull(prefsOf("""{"k":1}"""), "k"))        // 数字
        assertNull(PersonalConfig.booleanOrNull(prefsOf("""{"k":0}"""), "k"))
        assertNull(PersonalConfig.booleanOrNull(prefsOf("""{"k":null}"""), "k"))     // JSON null
        assertNull(PersonalConfig.booleanOrNull(prefsOf("""{"k":[]}"""), "k"))      // 数组
    }

    @Test fun `旧备份增强字段仍可读取_但不再属于运行配置`() {
        // 历史 JSON 的类型读取保持兼容；声音二审已退出导入/导出运行配置。
        val exported = org.json.JSONObject()
            .put("vibrate_feedback", true)
            .put("enhanced_decision_enabled", false)
        assertEquals(true, PersonalConfig.booleanOrNull(exported, "vibrate_feedback"))
        assertEquals(false, PersonalConfig.booleanOrNull(exported, "enhanced_decision_enabled"))
        // 旧备份（v0.58 之前导出）没有 enhanced 键
        val legacy = org.json.JSONObject().put("vibrate_feedback", true)
        assertNull(PersonalConfig.booleanOrNull(legacy, "enhanced_decision_enabled"))
        assertTrue(PersonalConfig.booleanOrNull(legacy, "vibrate_feedback") == true)
        assertFalse(legacy.has("enhanced_decision_enabled"))
    }
}
