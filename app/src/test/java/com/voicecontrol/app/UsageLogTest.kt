package com.voicecontrol.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 使用记录关联回归（2026-09-29 收尾项5）：同一句话连说两次时二审处置写回各自条目（uid 关联）、
 * uid 对不上时按文本回退（旧数据/SIMULATE）、旧 JSON 格式兼容读取。
 * 经 initForTest 注入临时文件，不依赖 Android Context；单例状态由注入即清空保证隔离。
 */
class UsageLogTest {

    @Test fun auditAndHumanMarksSurviveReloadAndNeverBindOrChangeOutcomes() {
        val f = freshFile("audit.json")
        UsageLog.initForTest(f)
        UsageLog.appendHeard("点击八", "g1-u1")
        UsageLog.append("→ 点击编号8 ✅")
        UsageLog.appendHeard("点击八", "g1-u2")
        val audit = org.json.JSONObject().put("schema", 1).put("uid", "g1-u1")
        assertTrue(UsageLog.attachSpeechAudit("g1-u1", audit))
        assertTrue(UsageLog.markIntent("g1-u1", "tap_number:18", SpeechAudit.ObservedEffect.WRONG))
        assertFalse(UsageLog.attachSpeechAudit("g1-u2", audit))
        assertFalse(UsageLog.markIntent("gone", "go_back", SpeechAudit.ObservedEffect.NONE))
        assertFalse(UsageLog.markIntent("g1-u2", "tap_number:76", SpeechAudit.ObservedEffect.NONE))
        assertFalse(UsageLog.markIntent("", "go_back", SpeechAudit.ObservedEffect.NONE))
        UsageLog.initForTest(f)
        val entries = UsageLog.all()
        assertEquals(2, entries.size)
        assertEquals("→ 点击编号8 ✅", entries[0].text)
        assertEquals("tap_number:18", entries[0].confirmedIntent)
        assertEquals("WRONG", entries[0].observedEffect)
        assertEquals(audit.toString(), entries[0].speechAudit)
        assertEquals("", entries[1].confirmedIntent)
        assertEquals("", entries[1].speechAudit)
        assertTrue(UsageLog.clearIntent("g1-u1"))
        assertEquals("", UsageLog.all()[0].confirmedIntent)
        assertEquals(audit.toString(), UsageLog.all()[0].speechAudit)
    }

    @Test fun prunedOrClearedSentenceCannotTransferFeedbackToRepeatedText() {
        val f = freshFile("pruned.json")
        f.writeText("""[{"t":1,"u":"old-u1","h":"返回","f":"go_back"}]""")
        UsageLog.initForTest(f)
        UsageLog.appendHeard("返回", "new-u1")
        assertFalse(UsageLog.markIntent("old-u1", "go_back", SpeechAudit.ObservedEffect.WRONG))
        assertFalse(UsageLog.attachSpeechAudit("old-u1", org.json.JSONObject().put("uid", "old-u1")))
        assertEquals("", UsageLog.all().single().confirmedIntent)
        UsageLog.clear()
        assertFalse(UsageLog.markIntent("new-u1", "go_back", SpeechAudit.ObservedEffect.NONE))
        assertTrue(UsageLog.all().isEmpty())
    }

    @Test fun initialNestedAuditAndStringAuditBothLoadWithoutParsingOnEachPersist() {
        val f = freshFile("nested_audit.json")
        val now = System.currentTimeMillis()
        val nested = org.json.JSONObject().put("schema", 1).put("uid", "g1-u1")
        f.writeText(org.json.JSONArray().put(org.json.JSONObject().put("t", now).put("h", "返回")
            .put("u", "g1-u1").put("s", nested)).toString())
        UsageLog.initForTest(f)
        assertEquals(nested.toString(), UsageLog.all().single().speechAudit)
        assertTrue(UsageLog.markIntent("g1-u1", "go_back", SpeechAudit.ObservedEffect.NONE))
        val stored = org.json.JSONArray(f.readText()).getJSONObject(0)
        assertTrue(stored.get("s") is String)
        assertEquals(nested.toString(), stored.getString("s"))
        UsageLog.initForTest(f)
        assertEquals(nested.toString(), UsageLog.all().single().speechAudit)
        assertEquals("go_back", UsageLog.all().single().confirmedIntent)
    }

    private fun freshFile(name: String): File =
        File(createTempDir(), name)

    private fun createTempDir(): File =
        kotlin.io.path.createTempDirectory("usage_log_test").toFile()

    @Test fun `同一句话连说两次各自成条二审按uid写回各自记录`() {
        UsageLog.initForTest(freshFile("a.json"))
        // 同一识别文本连续两遍（uid 不同——识别线程逐句递增）
        UsageLog.appendHeard("增加音量", "g1-u1")
        UsageLog.appendHeard("增加音量", "g1-u2")

        val all = UsageLog.all()
        assertEquals(2, all.size)
        assertEquals("增加音量", all[0].heard)
        assertEquals("增加音量", all[1].heard)
        assertEquals("g1-u1", all[0].uid)
        assertEquals("g1-u2", all[1].uid)

        // 第二句被二审改判、第一句一致：各自写回，不串条目
        UsageLog.append("→ 降低音量 ✅ 已执行（二审）")   // 关联最后一条（u2）
        UsageLog.attachAudioDecision("g1-u1", "增加音量", "已复核一致", "detail-1")
        UsageLog.attachAudioDecision("g1-u2", "增加音量", "已纠正", "detail-2")

        val after = UsageLog.all()
        assertEquals("已复核一致", after[0].decisionLabel)
        assertEquals("detail-1", after[0].audioDecision)
        assertEquals("已纠正", after[1].decisionLabel)
        assertEquals("detail-2", after[1].audioDecision)
        // 执行结果也只落在 u2 那条（append 关联最后一条 heard 空结果条）
        assertEquals("", after[0].text)
        assertEquals("→ 降低音量 ✅ 已执行（二审）", after[1].text)
    }

    @Test fun `uid对不上时按识别文本回退到最新同文本条目`() {
        UsageLog.initForTest(freshFile("b.json"))
        UsageLog.appendHeard("降低音量", "g2-u1")
        UsageLog.appendHeard("降低音量", "g2-u2")
        // uid 陌生（极端：条目已被 48h 清理/上限挤出）→ 文本回退取最后一条
        UsageLog.attachAudioDecision("gX-u99", "降低音量", "无把握，使用普通识别", "detail-x")
        val after = UsageLog.all()
        assertEquals("", after[0].decisionLabel)   // 第一条不受影响
        assertEquals("无把握，使用普通识别", after[1].decisionLabel)
        assertEquals("detail-x", after[1].audioDecision)
    }

    @Test fun `旧JSON格式兼容读取`() {
        val f = freshFile("c.json")
        val now = System.currentTimeMillis()
        // v0.57~0.58 旧格式：{"t","h","x","a"}（无 u/d）；更旧 {"t","x"}；最旧数组 [t,x]
        f.writeText(
            """[
            {"t":${now},"h":"向上滑动","x":"→ 向上滑动 ✅ 已执行","a":"二审=null; …"},
            {"t":${now},"x":"→ 会话结束：看门狗强制释放"},
            [${now},"→ 旧版记录"]
            ]""",
        )
        UsageLog.initForTest(f)
        val all = UsageLog.all()
        assertEquals(3, all.size)
        assertEquals("向上滑动", all[0].heard)
        assertEquals("→ 向上滑动 ✅ 已执行", all[0].text)
        assertEquals("二审=null; …", all[0].audioDecision)
        assertEquals("", all[0].uid)               // 旧条目无 uid，正常显示
        assertEquals("", all[0].decisionLabel)     // 旧条目无处置标签，列表降级显示详情串
        assertEquals("→ 会话结束：看门狗强制释放", all[1].text)
        assertEquals("→ 旧版记录", all[2].text)
        assertTrue(all[2].heard.isEmpty())
    }

    @Test fun `持久化往返uid与标签不丢失`() {
        val f = freshFile("d.json")
        UsageLog.initForTest(f)
        UsageLog.appendHeard("声音大一点", "g3-u1")
        UsageLog.attachAudioDecision("g3-u1", "声音大一点", "已复核一致", "conf=0.97")
        // 重新加载（模拟进程重启）
        UsageLog.initForTest(f)
        val e = UsageLog.all().single()
        assertEquals("g3-u1", e.uid)
        assertEquals("已复核一致", e.decisionLabel)
        assertEquals("conf=0.97", e.audioDecision)
    }

    // ---------- updateOutcome：异步动作结果按发起句写回（2026-09-30 执行反馈分层轮） ----------

    @Test fun `异步结果按uid写回原条不追加`() {
        UsageLog.initForTest(freshFile("e.json"))
        UsageLog.appendHeard("重复五次", "g4-u1")
        UsageLog.append("→ 重复 5 次 · 已开始")
        UsageLog.updateOutcome("g4-u1", "→ 重复 5 次 · 已完成")
        val all = UsageLog.all()
        assertEquals(1, all.size)                       // 更新原条，不 append 新条
        assertEquals("→ 重复 5 次 · 已完成", all[0].text)
        assertEquals("重复五次", all[0].heard)          // 原文关联保留
    }

    @Test fun `点击A点击B重复一次结果归属B句不串A`() {
        // 用户点名的时序归属场景（记录层证明）：B 句发起的重复，完成事件写回 B，A 条目不动
        UsageLog.initForTest(freshFile("f.json"))
        UsageLog.appendHeard("点击A", "g5-u1")
        UsageLog.append("→ 点击「A」 ✅")
        UsageLog.appendHeard("点击B", "g5-u2")
        UsageLog.append("→ 点击「B」 ✅")
        UsageLog.appendHeard("重复一次", "g5-u3")
        UsageLog.append("→ 重复 1 次 · 已开始")
        UsageLog.updateOutcome("g5-u3", "→ 重复 1 次 · 已完成")
        val all = UsageLog.all()
        assertEquals(3, all.size)
        assertEquals("→ 点击「A」 ✅", all[0].text)      // A 句不受影响
        assertEquals("→ 点击「B」 ✅", all[1].text)      // B 句不受影响
        assertEquals("→ 重复 1 次 · 已完成", all[2].text)
    }

    @Test fun `迟到回调用旧uid只动旧条目不覆盖新动作`() {
        UsageLog.initForTest(freshFile("g.json"))
        UsageLog.appendHeard("重复三次", "g6-u1")
        UsageLog.append("→ 重复 3 次 · 已开始")
        UsageLog.appendHeard("向上滑动", "g6-u2")       // 会话还在，用户已说新句子
        UsageLog.append("→ 向上滑动 ✅ 已执行")
        // 旧句的迟到完成事件（uid=u1）写回——不碰 u2
        UsageLog.updateOutcome("g6-u1", "→ 重复 3 次 · 已完成")
        val all = UsageLog.all()
        assertEquals("→ 重复 3 次 · 已完成", all[0].text)
        assertEquals("→ 向上滑动 ✅ 已执行", all[1].text)
    }

    @Test fun `uid失配或为空时append保留时间线`() {
        UsageLog.initForTest(freshFile("h.json"))
        UsageLog.appendHeard("重复两次", "g7-u1")
        UsageLog.append("→ 重复 2 次 · 已开始")
        // uid 陌生（条目已被 48h 清理/上限挤出）→ append 新条，不丢事件
        UsageLog.updateOutcome("gX-u99", "→ 重复 2 次 · 已完成")
        // 空 uid（SIMULATE 注入等无句身份场景）→ 同样 append
        UsageLog.updateOutcome("", "→ 重复中止（会话结束，已完成 1/2）")
        val all = UsageLog.all()
        assertEquals(3, all.size)
        assertEquals("→ 重复 2 次 · 已开始", all[0].text)
        assertEquals("→ 重复 2 次 · 已完成", all[1].text)
        assertEquals("→ 重复中止（会话结束，已完成 1/2）", all[2].text)
    }
}
