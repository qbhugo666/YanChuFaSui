package com.voicecontrol.app

import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.FutureTask

/** 一个页面最多一个导出任务。取消同时撤销尚未交付的结果，迟到附件由后台删除。 */
internal class FeedbackExportTask<T>(
    private val worker: ExecutorService,
    private val delivery: Executor,
    private val discard: (T) -> Unit,
) {
    private class Job<T>(val callback: (Result<T>) -> Unit) {
        lateinit var future: FutureTask<Unit>
        var result: Result<T>? = null
    }

    private val lock = Any()
    private var current: Job<T>? = null
    private var closed = false

    fun start(work: () -> T, callback: (Result<T>) -> Unit): Boolean {
        val job = synchronized(lock) {
            if (closed || current != null) return false
            Job(callback).also { current = it }
        }
        job.future = FutureTask<Unit> {
            val result = runCatching(work)
            val publish = synchronized(lock) {
                if (current !== job) false else {
                    job.result = result
                    true
                }
            }
            if (publish) delivery.execute { deliver(job) }
            else result.getOrNull()?.let(discard)
            Unit
        }
        // start/cancel/close 由页面主线程串行调用；工作结果只在 lock 内交接。
        worker.execute(job.future)
        return true
    }

    private fun deliver(job: Job<T>) {
        val result = synchronized(lock) {
            if (current !== job) return
            current = null
            job.result.also { job.result = null }
        }
        result?.let(job.callback)
    }

    fun cancel() {
        val job = synchronized(lock) { current.also { current = null } } ?: return
        job.future.cancel(true)
        val result = synchronized(lock) { job.result.also { job.result = null } }
        result?.getOrNull()?.let { value -> worker.execute { discard(value) } }
    }

    fun close() {
        synchronized(lock) { closed = true }
        cancel()
        // 已排队的附件清理仍需完成；运行中的收集任务已经收到中断。
        worker.shutdown()
    }
}
