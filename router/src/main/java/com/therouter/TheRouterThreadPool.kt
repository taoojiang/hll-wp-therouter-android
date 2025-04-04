@file:JvmName("TheRouterThreadPool")

package com.therouter

import android.os.Handler
import android.os.Looper
import java.lang.StringBuilder
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

private val CPU_COUNT = Runtime.getRuntime().availableProcessors()

private val CORE_POOL_SIZE = max(3, min(CPU_COUNT - 1, 6))
private val BIGGER_CORE_POOL_SIZE = CPU_COUNT * 4
private val MAXIMUM_CORE_POOL_SIZE = CPU_COUNT * 8
private const val MAXIMUM_POOL_SIZE = Int.MAX_VALUE
var KEEP_ALIVE_SECONDS = 30L
var MAX_QUEUE_SIZE = 10

private const val THREAD_NAME = "TheRouterLibThread"

var executor: ExecutorService = BufferExecutor()
private val main = Handler(Looper.getMainLooper())

fun setThreadPoolExecutor(e: ExecutorService?) = e?.let {
    executor = it
}

/**
 * Executes the given command at some time in the future.  The command
 * may execute in a new thread, in a pooled thread, or in the calling
 * thread, at the discretion of the `Executor` implementation.
 *
 * @param command the runnable task
 */
fun execute(command: Runnable) {
    try {
        executor.execute(command)
    } catch (e: Exception) {
        //RejectedExecutionException if this task cannot be accepted for execution
        debug("TheRouterThreadPool", "rejected execute runnable") {
            e.printStackTrace()
        }
    }
}

fun executeInMainThread(command: Runnable): Boolean =
    if (Thread.currentThread() == Looper.getMainLooper().thread) {
        command.run()
        true
    } else {
        main.post(command)
    }

private var threadPoolExecutor = ThreadPoolExecutor(
    CORE_POOL_SIZE,
    MAXIMUM_POOL_SIZE, KEEP_ALIVE_SECONDS,
    TimeUnit.SECONDS, LinkedBlockingDeque(MAX_QUEUE_SIZE),
    newThreadFactory(THREAD_NAME)
).apply {
    allowCoreThreadTimeOut(true)
}

fun newThreadFactory(threadName: String): ThreadFactory {
    return object : ThreadFactory {
        private val mCount = AtomicInteger(1)
        override fun newThread(r: Runnable): Thread {
            return Thread(r, threadName + " #" + mCount.getAndIncrement())
        }
    }
}

/**
 * 二级缓存线程池内部执行逻辑
 *
 * <pre>
 * 每个任务首先被加入二级缓存队列中
 * if (一级队列没有满 && 当前正在执行的线程不超过核心线程数) {
 * 取二级队列中的任务
 * if (二级队列中的任务数超过最大队列数的100倍) {
 * 将线程池的核心线程数设置为 ${MAXIMUM_CORE_POOL_SIZE}
 * } else if (二级队列中的任务数超过最大队列数的10倍) {
 * 将线程池的核心线程数设置为 ${BIGGER_CORE_POOL_SIZE}
 * } else {
 * 将线程池的核心线程数设置为 ${CORE_POOL_SIZE}
 * }
 *
 * if (核心线程全部处于工作中) {
 * if (一级队列没有满){
 * 将任务加入一级队列
 * } else {
 * 新开临时线程执行任务
 * }
 * } else {
 * 将任务交给核心线程执行
 * }
 * }
</pre> *
 */
private class BufferExecutor : ExecutorService, Executor {
    val taskQueue = ArrayDeque<Task>()
    var activeTask: Task? = null

    // 加入一级队列时被记录，任务执行完成时被移除
    val flightTaskMap = ConcurrentHashMap<Int, FlightTaskInfo>()

    @Synchronized
    override fun execute(r: Runnable) {
        taskQueue.offer(Task(
            r, if (TheRouter.isDebug) {
                checkTask()
                getTrace(Thread.currentThread().stackTrace)
            } else {
                ""
            }
        ) {
            if (TheRouter.isDebug) {
                flightTaskMap.remove(r.hashCode())
            }
            scheduleNext()
        })
        //activeTask 不为空，表示一级队列已经满了，此刻任务应该被停留到二级队列等待调度
        if (activeTask == null) {
            scheduleNext()
        }
    }

    /**
     * 检查是否有频繁添加任务，或有轮询任务的情况
     */
    private fun checkTask() {
        flightTaskMap.values.forEach { v ->
            require(
                System.currentTimeMillis() - v.createTime < KEEP_ALIVE_SECONDS * 1000L,
                "ThreadPool",
                "执行该任务耗时过久，有可能是此任务耗时，或者当前线程池中其他任务都很耗时，请优化逻辑\n" +
                        "当前任务被创建时间为${v.createTime}此时时间为${System.currentTimeMillis()}\n${v.trace}"
            )
        }
    }

    /**
     * 从二级队列调度任务，将任务放入一级队列，或直接交由线程池执行
     */
    @Synchronized
    private fun scheduleNext() {
        fun doNext() {
            //从二级队列中取任务
            if (taskQueue.poll().also { activeTask = it } != null) {
                activeTask?.let {
                    if (TheRouter.isDebug) {
                        flightTaskMap[it.r.hashCode()] = FlightTaskInfo(it.trace)
                    }
                }
                //将任务加入一级队列，或有可能直接被线程池执行(Executor内部逻辑)
                threadPoolExecutor.execute(activeTask)
                activeTask = null
            }
        }

        val isMainThread = Thread.currentThread() == Looper.getMainLooper().thread
        if (isMainThread) {
            doNext()
        } else {
            //线程池中正在执行的任务数
            val activeCount = threadPoolExecutor.activeCount
            //一级队列任务数
            val queueSize = threadPoolExecutor.queue.size

            //动态修改核心线程数，以适应不同场景的任务量
            when {
                taskQueue.size > MAX_QUEUE_SIZE * 100 -> {
                    threadPoolExecutor.corePoolSize = MAXIMUM_CORE_POOL_SIZE
                }

                taskQueue.size > MAX_QUEUE_SIZE * 10 -> {
                    threadPoolExecutor.corePoolSize = BIGGER_CORE_POOL_SIZE
                }

                else -> {
                    threadPoolExecutor.corePoolSize = CORE_POOL_SIZE
                }
            }

            //如果一级队列没有满，且当前正在执行的线程不超过核心线程数
            if (queueSize <= MAX_QUEUE_SIZE && activeCount < threadPoolExecutor.corePoolSize) {
                doNext()
            }
        }
    }

    @Synchronized
    override fun shutdown() {
        threadPoolExecutor.shutdown()
    }

    @Synchronized
    override fun shutdownNow(): List<Runnable> {
        return threadPoolExecutor.shutdownNow()
    }

    @Synchronized
    override fun isShutdown(): Boolean {
        return threadPoolExecutor.isShutdown
    }

    @Synchronized
    override fun isTerminated(): Boolean {
        return threadPoolExecutor.isTerminated
    }

    @Synchronized
    @Throws(InterruptedException::class)
    override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean {
        return threadPoolExecutor.awaitTermination(timeout, unit)
    }

    @Synchronized
    override fun <T> submit(task: Callable<T>): Future<T> {
        return threadPoolExecutor.submit(task)
    }

    @Synchronized
    override fun <T> submit(task: Runnable, result: T): Future<T> {
        return threadPoolExecutor.submit(task, result)
    }

    @Synchronized
    override fun submit(task: Runnable): Future<*> {
        return threadPoolExecutor.submit(task)
    }

    @Synchronized
    @Throws(InterruptedException::class)
    override fun <T> invokeAll(tasks: Collection<Callable<T>?>): List<Future<T>> {
        return threadPoolExecutor.invokeAll(tasks)
    }

    @Synchronized
    @Throws(InterruptedException::class)
    override fun <T> invokeAll(tasks: Collection<Callable<T>?>, timeout: Long, unit: TimeUnit): List<Future<T>> {
        return threadPoolExecutor.invokeAll(tasks, timeout, unit)
    }

    @Synchronized
    @Throws(ExecutionException::class, InterruptedException::class)
    override fun <T> invokeAny(tasks: Collection<Callable<T>?>): T {
        return threadPoolExecutor.invokeAny(tasks)
    }

    @Synchronized
    @Throws(ExecutionException::class, InterruptedException::class, TimeoutException::class)
    override fun <T> invokeAny(tasks: Collection<Callable<T>?>, timeout: Long, unit: TimeUnit): T {
        return threadPoolExecutor.invokeAny(tasks, timeout, unit)
    }
}

private class FlightTaskInfo(val trace: String) {
    var createTime = System.currentTimeMillis()
}

private class Task(
    val r: Runnable,
    val trace: String,
    val block: () -> Unit
) : Runnable {
    override fun run() = try {
        r.run()
    } finally {
        block()
    }
}

private fun getTrace(trace: Array<StackTraceElement>): String {
    val str = StringBuilder()
    trace.forEach {
        str.append(it).append('\n')
    }
    return str.toString()
}