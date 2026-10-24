package com.voicecontrol.app

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FeedbackExportTaskTest {
    private class QueueExecutor : AbstractExecutorService() {
        val queue = ArrayDeque<Runnable>()
        private var closed = false
        override fun execute(command: Runnable) { check(!closed); queue.addLast(command) }
        fun next() = queue.removeFirst().run()
        override fun shutdown() { closed = true }
        override fun shutdownNow(): MutableList<Runnable> { shutdown(); return mutableListOf() }
        override fun isShutdown() = closed
        override fun isTerminated() = closed && queue.isEmpty()
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = isTerminated
    }

    @Test fun repeatedClickRunsOnceAndOnlyDeliversOnDeliveryExecutor() {
        val worker = QueueExecutor()
        val ui = QueueExecutor()
        val received = mutableListOf<String>()
        val task = FeedbackExportTask<String>(worker, ui) { fail("成功结果不应丢弃") }
        var calls = 0
        assertTrue(task.start({ calls++; "完整报告" }) { received.add(it.getOrThrow()) })
        assertFalse(task.start({ fail("不能重复收集"); "" }) {})
        assertEquals(0, calls)
        worker.next()
        assertEquals(1, calls)
        assertTrue(received.isEmpty())
        assertFalse(task.start({ "另一份" }) {})
        ui.next()
        assertEquals(listOf("完整报告"), received)
        assertTrue(task.start({ "下一份" }) {})
        task.close()
    }

    @Test fun clearOrLeaveInvalidatesPendingDeliveryAndDeletesItsAttachmentOnWorker() {
        val worker = QueueExecutor()
        val ui = QueueExecutor()
        val discarded = mutableListOf<String>()
        val received = mutableListOf<String>()
        val task = FeedbackExportTask<String>(worker, ui, discarded::add)
        task.start({ "清空前的报告" }) { received.add(it.getOrThrow()) }
        worker.next()
        task.cancel()
        assertTrue(discarded.isEmpty())
        task.start({ "清空后的报告" }) { received.add(it.getOrThrow()) }
        ui.next()
        assertTrue(received.isEmpty())
        worker.next()
        assertEquals(listOf("清空前的报告"), discarded)
        worker.next()
        ui.next()
        assertEquals(listOf("清空后的报告"), received)
        task.close()
    }

    @Test fun closedPageRejectsNewWorkAndDrainsPendingCleanup() {
        val worker = QueueExecutor()
        val ui = QueueExecutor()
        val discarded = mutableListOf<String>()
        val task = FeedbackExportTask<String>(worker, ui, discarded::add)
        task.start({ "附件" }) { fail("页面关闭后不能弹分享") }
        worker.next()
        task.close()
        assertTrue(worker.isShutdown)
        assertFalse(task.start({ "新任务" }) {})
        ui.next()
        worker.next()
        assertEquals(listOf("附件"), discarded)
        assertTrue(worker.isTerminated)
    }

    @Test fun collectionFailureIsDeliveredAndAllowsRetry() {
        val worker = QueueExecutor()
        val ui = QueueExecutor()
        val task = FeedbackExportTask<String>(worker, ui) {}
        var error: Throwable? = null
        task.start({ throw IllegalStateException("收集失败") }) { error = it.exceptionOrNull() }
        worker.next()
        ui.next()
        assertEquals("收集失败", error?.message)
        assertTrue(task.start({ "重试" }) {})
        task.close()
    }

    @Test fun cancellationDuringCollectionDiscardsEvenAnUninterruptibleLateResult() {
        val worker = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val discarded = CountDownLatch(1)
        val delivered = CountDownLatch(1)
        val task = FeedbackExportTask<String>(worker, Executor { it.run() }) { discarded.countDown() }
        try {
            task.start({
                entered.countDown()
                while (finish.count > 0) {
                    try { finish.await() } catch (_: InterruptedException) { /* 模拟无法立即中断的文件读取 */ }
                }
                "迟到的附件"
            }) { delivered.countDown() }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            task.cancel()
            finish.countDown()
            assertTrue(discarded.await(2, TimeUnit.SECONDS))
            assertEquals(1L, delivered.count)
        } finally {
            finish.countDown()
            task.close()
            assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS))
        }
    }
}
