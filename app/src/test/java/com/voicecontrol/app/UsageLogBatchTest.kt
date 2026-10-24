package com.voicecontrol.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class UsageLogBatchTest {
    private fun freshFile() = File(kotlin.io.path.createTempDirectory("usage-batch").toFile(), "usage.json")
    private fun audit(uid: String) = JSONObject().put("schema", 1).put("uid", uid).put("note", "诊断")
    private fun disk(f: File) = JSONArray(f.readText())

    @Test fun originalIsDurableBeforeActionAndResultAndAuditShareOneWrite() {
        val f = freshFile()
        var writes = 0
        UsageLog.initForTest(f) { target, json -> writes++; target.writeText(json) }
        UsageLog.batchUpdates {
            UsageLog.appendHeard("向上滑动", "u1")
            assertEquals(1, writes)
            assertEquals("向上滑动", disk(f).getJSONObject(0).getString("h"))
            UsageLog.append("→ 向上滑动 ✅")
            assertTrue(UsageLog.attachSpeechAudit("u1", audit("u1")))
            assertEquals("→ 向上滑动 ✅", UsageLog.all().single().text)
            assertEquals(1, writes)
        }
        assertEquals(2, writes)
        UsageLog.initForTest(f)
        assertEquals("→ 向上滑动 ✅", UsageLog.all().single().text)
        assertEquals(audit("u1").toString(), UsageLog.all().single().speechAudit)
    }

    @Test fun updatesOutsideBatchRemainImmediatelyDurable() {
        val f = freshFile()
        var writes = 0
        UsageLog.initForTest(f) { target, json -> writes++; target.writeText(json) }
        UsageLog.appendHeard("返回", "u1")
        UsageLog.append("→ 返回 ✅")
        UsageLog.attachSpeechAudit("u1", audit("u1"))
        assertEquals(3, writes)
        assertEquals("→ 返回 ✅", disk(f).getJSONObject(0).getString("x"))
    }

    @Test fun exceptionFlushesChangesAndDoesNotLeaveNextCommandBatched() {
        val f = freshFile()
        UsageLog.initForTest(f)
        val expected = IllegalStateException("action failed")
        val actual = runCatching {
            UsageLog.batchUpdates {
                UsageLog.appendHeard("返回", "u1")
                UsageLog.append("→ 返回：未执行")
                try { throw expected } finally { UsageLog.attachSpeechAudit("u1", audit("u1")) }
            }
        }.exceptionOrNull()
        assertSame(expected, actual)
        assertEquals("→ 返回：未执行", disk(f).getJSONObject(0).getString("x"))
        UsageLog.append("→ 后续事件")
        assertEquals(2, disk(f).length())
        UsageLog.initForTest(f)
        assertEquals(audit("u1").toString(), UsageLog.all().first().speechAudit)
    }

    @Test fun nestedBatchFlushesAtOuterReturnAndPreservesReturnValue() {
        val f = freshFile()
        var writes = 0
        UsageLog.initForTest(f) { target, json -> writes++; target.writeText(json) }
        val result = UsageLog.batchUpdates {
            UsageLog.appendHeard("返回", "u1")
            UsageLog.batchUpdates { UsageLog.append("→ 返回 ✅") }
            assertEquals(1, writes)
            UsageLog.attachSpeechAudit("u1", audit("u1"))
            42
        }
        assertEquals(42, result)
        assertEquals(2, writes)
    }

    @Test fun clearInsideBatchIsImmediateAndLateAuditCannotRestoreOldEntries() {
        val f = freshFile()
        UsageLog.initForTest(f)
        UsageLog.batchUpdates {
            UsageLog.appendHeard("返回", "u1")
            UsageLog.append("→ 返回 ✅")
            UsageLog.clear()
            assertEquals(0, disk(f).length())
            assertFalse(UsageLog.attachSpeechAudit("u1", audit("u1")))
        }
        UsageLog.initForTest(f)
        assertTrue(UsageLog.all().isEmpty())
    }

    @Test fun anotherThreadCanClearAndWriteWhileBatchIsOpenWithoutDeadlockOrStaleOverwrite() {
        val f = freshFile()
        UsageLog.initForTest(f)
        val failure = AtomicReference<Throwable?>()
        val done = CountDownLatch(1)
        UsageLog.batchUpdates {
            UsageLog.appendHeard("旧句", "old")
            UsageLog.append("旧结果")
            thread(isDaemon = true) {
                try {
                    UsageLog.clear()
                    UsageLog.appendHeard("新句", "new")
                    UsageLog.append("新结果")
                } catch (t: Throwable) { failure.set(t) } finally { done.countDown() }
            }
            assertTrue("batch must not hold the log monitor across actions", done.await(5, TimeUnit.SECONDS))
            failure.get()?.let { throw it }
            assertFalse(UsageLog.attachSpeechAudit("old", audit("old")))
        }
        UsageLog.initForTest(f)
        assertEquals("new", UsageLog.all().single().uid)
        assertEquals("新结果", UsageLog.all().single().text)
    }

    @Test fun lateRepeatOutcomeStaysWithOriginalUidAndIsWrittenImmediately() {
        val f = freshFile()
        UsageLog.initForTest(f)
        for (uid in listOf("u1", "u2")) {
            UsageLog.batchUpdates {
                UsageLog.appendHeard("重复两次", uid)
                UsageLog.append("→ 已开始 $uid")
                UsageLog.attachSpeechAudit(uid, audit(uid))
            }
        }
        UsageLog.batchUpdates {
            UsageLog.updateOutcome("u1", "→ 已完成 u1")
            assertEquals("→ 已完成 u1", disk(f).getJSONObject(0).getString("x"))
            assertEquals("→ 已开始 u2", disk(f).getJSONObject(1).getString("x"))
        }
        UsageLog.initForTest(f)
        assertEquals(listOf("u1", "u2"), UsageLog.all().map { it.uid })
        assertEquals("→ 已完成 u1", UsageLog.all()[0].text)
    }

    @Test fun sessionEndCheckpointPersistsBeforeBatchReturns() {
        val f = freshFile()
        UsageLog.initForTest(f)
        UsageLog.batchUpdates {
            UsageLog.appendHeard("退出", "exit")
            UsageLog.append("→ 会话结束：识别到退出")
            UsageLog.flushPending()
            assertEquals("→ 会话结束：识别到退出", disk(f).getJSONObject(0).getString("x"))
            UsageLog.attachSpeechAudit("exit", audit("exit"))
        }
        UsageLog.initForTest(f)
        assertEquals("→ 会话结束：识别到退出", UsageLog.all().single().text)
        assertEquals(audit("exit").toString(), UsageLog.all().single().speechAudit)
    }

    @Test fun failedWriteDoesNotThrowIntoCommandAndCanRetryCurrentState() {
        val f = freshFile()
        var failWrites = false
        UsageLog.initForTest(f) { target, json ->
            if (failWrites) throw IOException("simulated unavailable storage")
            target.writeText(json)
        }
        UsageLog.batchUpdates {
            UsageLog.appendHeard("返回", "u1")
            failWrites = true
            UsageLog.append("→ 返回 ✅")
            UsageLog.attachSpeechAudit("u1", audit("u1"))
        }
        assertEquals("返回", disk(f).getJSONObject(0).getString("h"))
        assertEquals("→ 返回 ✅", UsageLog.all().single().text)
        failWrites = false
        UsageLog.flushPending()
        UsageLog.initForTest(f)
        assertEquals("→ 返回 ✅", UsageLog.all().single().text)
        assertEquals(audit("u1").toString(), UsageLog.all().single().speechAudit)
    }

    @Test fun retryAfterClearUsesEmptyStateNotTheFailedSnapshot() {
        val f = freshFile()
        var failWrites = false
        UsageLog.initForTest(f) { target, json ->
            if (failWrites) throw IOException("simulated unavailable storage")
            target.writeText(json)
        }
        UsageLog.batchUpdates {
            UsageLog.appendHeard("返回", "u1")
            failWrites = true
            UsageLog.append("→ 返回 ✅")
            UsageLog.clear()
        }
        failWrites = false
        UsageLog.flushPending()
        assertEquals(0, disk(f).length())
        assertFalse(UsageLog.attachSpeechAudit("u1", audit("u1")))
    }

    @Test fun retentionAndTwoHundredEntryLimitAreUnchangedAfterReload() {
        val f = freshFile()
        val expired = System.currentTimeMillis() - 49 * 60 * 60 * 1000L
        f.writeText(JSONArray().put(JSONObject().put("t", expired).put("h", "过期").put("u", "old")).toString())
        UsageLog.initForTest(f)
        assertTrue(UsageLog.all().isEmpty())
        repeat(205) { i ->
            UsageLog.batchUpdates {
                UsageLog.appendHeard("返回", "u$i")
                UsageLog.append("结果$i")
                UsageLog.attachSpeechAudit("u$i", audit("u$i"))
            }
        }
        val expected = UsageLog.all()
        assertEquals(200, expected.size)
        assertEquals("u5", expected.first().uid)
        UsageLog.initForTest(f)
        assertEquals(expected, UsageLog.all())
    }

    @Test fun mixedTimelineMatchesUnbatchedBehaviorFieldForFieldWithFewerWrites() {
        fun run(batched: Boolean): Pair<List<UsageLog.Entry>, Int> {
            val f = freshFile()
            var writes = 0
            UsageLog.initForTest(f) { target, json -> writes++; target.writeText(json) }
            repeat(30) { i ->
                val action = {
                    UsageLog.appendHeard(if (i % 2 == 0) "返回" else "重复两次", "u$i")
                    if (i % 5 != 0) UsageLog.append("结果$i") // 包括未触发操作的句子
                    if (i > 0 && i % 3 == 0) UsageLog.updateOutcome("u${i - 1}", "迟到结果${i - 1}")
                    UsageLog.attachSpeechAudit("u$i", audit("u$i"))
                    Unit
                }
                if (batched) UsageLog.batchUpdates(action) else action()
            }
            UsageLog.append("→ 会话结束：看门狗强制释放")
            UsageLog.initForTest(f)
            return UsageLog.all().map { it.copy(time = 0L) } to writes
        }
        val original = run(false)
        val optimized = run(true)
        assertEquals(original.first, optimized.first)
        assertTrue("must reduce writes without dropping fields", optimized.second < original.second)
    }
}
