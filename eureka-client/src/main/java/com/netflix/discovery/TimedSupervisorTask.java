package com.netflix.discovery;

import java.util.TimerTask;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import com.netflix.servo.monitor.Counter;
import com.netflix.servo.monitor.LongGauge;
import com.netflix.servo.monitor.MonitorConfig;
import com.netflix.servo.monitor.Monitors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * eureka 对 TimerTask 的封装，支持以下特性：
 * <ol>
 *     <li>调度线程池与执行线程池分离</li>
 *     <li>任务执行失败时自动阶梯式增加调度延迟时间</li>
 *     <li><监控任务执行成功、超时、拒绝、异常等次数/li>
 * </ol>
 *
 * <p>
 * A supervisor task that schedules subtasks while enforce a timeout.
 * Wrapped subtasks must be thread safe.
 *
 * @author David Qiang Liu
 */
public class TimedSupervisorTask extends TimerTask {
    private static final Logger logger = LoggerFactory.getLogger(TimedSupervisorTask.class);

    // 成功次数
    private final Counter successCounter;
    // 超时次数
    private final Counter timeoutCounter;
    // 任务拒绝次数
    private final Counter rejectedCounter;
    // 任务执行异常次数
    private final Counter throwableCounter;
    // executor 线程池的活跃线程数
    private final LongGauge threadPoolLevelGauge;

    // 任务名称
    private final String name;
    // 任务调度线程池
    private final ScheduledExecutorService scheduler;
    // 实际执行任务的线程池
    private final ThreadPoolExecutor executor;
    // 执行等待超时时间
    private final long timeoutMillis;
    // 需要执行的任务
    private final Runnable task;

    // 延迟时间，决定任务的调度间隔
    private final AtomicLong delay;
    // 最大调度延迟时间(默认为延迟时间的10倍)
    private final long maxDelay;

    /**
     * 构造函数
     */
    public TimedSupervisorTask(String name, ScheduledExecutorService scheduler, ThreadPoolExecutor executor,
                               int timeout, TimeUnit timeUnit, int expBackOffBound, Runnable task) {
        this.name = name;
        // 调度线程池
        this.scheduler = scheduler;
        // 实际执行任务的线程池
        this.executor = executor;
        // 执行超时时间
        this.timeoutMillis = timeUnit.toMillis(timeout);
        // 实际需要执行的任务
        this.task = task;
        // 延迟时间设置为超时时间
        this.delay = new AtomicLong(timeoutMillis);
        // 最大调度延迟 = 超时时间 * expBackOffBound，expBackOffBound 默认为10
        this.maxDelay = timeoutMillis * expBackOffBound;

        // 初始化计数器
        successCounter = Monitors.newCounter("success");
        timeoutCounter = Monitors.newCounter("timeouts");
        rejectedCounter = Monitors.newCounter("rejectedExecutions");
        throwableCounter = Monitors.newCounter("throwables");
        threadPoolLevelGauge = new LongGauge(MonitorConfig.builder("threadPoolUsed").build());
        // 注册监控
        Monitors.registerObject(name, this);
    }

    /**
     * 重写了 TimerTask 的 run() 方法
     */
    @Override
    public void run() {
        Future<?> future = null;
        try {
            // 提交任务到执行线程池
            future = executor.submit(task);
            // 更新活跃线程数
            threadPoolLevelGauge.set((long) executor.getActiveCount());
            // 获取执行结果，直至等待超时
            future.get(timeoutMillis, TimeUnit.MILLISECONDS);  // block until done or timeout
            // 执行成功，延迟时间设为等待超时时间
            delay.set(timeoutMillis);
            // 更新活跃线程数
            threadPoolLevelGauge.set((long) executor.getActiveCount());
            // 执行成功次数+1
            successCounter.increment();
        } catch (TimeoutException e) { // 等待超时
            logger.warn("task supervisor timed out", e);
            // 等待超时次数+1
            timeoutCounter.increment();

            // 等待超时，延迟时间设为当前延迟时间的2倍，且不得超过最大延迟时间
            // 延迟时间2倍递增是因为短时间内可能还是会发生等待超时，因此延长调度间隔稍后再试，如果下次执行成功就会重置延迟时间为等待超时时间
            long currentDelay = delay.get();
            long newDelay = Math.min(maxDelay, currentDelay * 2);
            // CAS 更新延迟时间
            delay.compareAndSet(currentDelay, newDelay);
        } catch (RejectedExecutionException e) { // 拒绝任务
            if (executor.isShutdown() || scheduler.isShutdown()) {
                logger.warn("task supervisor shutting down, reject the task", e);
            } else {
                logger.warn("task supervisor rejected the task", e);
            }
            // 任务拒绝次数+1
            rejectedCounter.increment();
        } catch (Throwable e) { // 任务执行异常
            if (executor.isShutdown() || scheduler.isShutdown()) {
                logger.warn("task supervisor shutting down, can't accept the task");
            } else {
                logger.warn("task supervisor threw an exception", e);
            }
            // 任务执行异常次数+1
            throwableCounter.increment();
        } finally {
            // 不管执行成功还是等待超时，直接取消任务
            if (future != null) {
                future.cancel(true);
            }
            // 重新对任务进行调度
            if (!scheduler.isShutdown()) {
                scheduler.schedule(this, delay.get(), TimeUnit.MILLISECONDS);
            }
        }
    }

    @Override
    public boolean cancel() {
        Monitors.unregisterObject(name, this);
        return super.cancel();
    }
}