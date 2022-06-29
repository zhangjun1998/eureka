package com.netflix.discovery;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.discovery.util.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 应用实例复制器。
 * 实现了 Runnable 接口，本身就是一个可执行的任务，可以在应用实例发生变化时重新注册应用实例，还支持健康检查健康应用实例状态，
 * 比如刷新了应用实例配置的续约间隔或过期时间，那么会重新注册应用实例；健康检查失败时会将应用实例下线。
 *
 * <p>
 * A task for updating and replicating the local instanceinfo to the remote server. Properties of this task are:
 * - configured with a single update thread to guarantee sequential update to the remote server
 * - update tasks can be scheduled on-demand via onDemandUpdate()
 * - task processing is rate limited by burstSize
 * - a new update task is always scheduled automatically after an earlier update task. However if an on-demand task
 *   is started, the scheduled automatic update task is discarded (and a new one will be scheduled after the new
 *   on-demand update).
 *
 *   @author dliu
 */
class InstanceInfoReplicator implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(InstanceInfoReplicator.class);

    // eureka-client 客户端
    private final DiscoveryClient discoveryClient;
    // 应用实例信息
    private final InstanceInfo instanceInfo;

    // 注册间隔，默认30s
    private final int replicationIntervalSeconds;
    // 调度线程池，核心线程数为1
    private final ScheduledExecutorService scheduler;
    // 任务执行结果的原子引用
    private final AtomicReference<Future> scheduledPeriodicRef;

    // 任务启动状态
    private final AtomicBoolean started;

    // 基于令牌桶的限流器
    // 限流器只在应用实例状态发生变更时用到，因为正常调度是30s一次频率是固定的，而应用实例状态变更的频率是不固定的
    // 因此使用限流器来限制由于应用实例状态变更导致的频繁注册
    private final RateLimiter rateLimiter;
    // 突发注册请求的并发限制量，用于限制突发注册请求频率，默认2
    private final int burstSize;
    // 每分钟的注册请求阈值，60s * burstSize / replicationIntervalSeconds，即 60 * 2 / 30 = 4
    private final int allowedRatePerMinute;

    /**
     * 构造函数
     */
    InstanceInfoReplicator(DiscoveryClient discoveryClient, InstanceInfo instanceInfo, int replicationIntervalSeconds, int burstSize) {
        this.discoveryClient = discoveryClient;
        this.instanceInfo = instanceInfo;
        // 初始化调度线程池，核心线程1
        this.scheduler = Executors.newScheduledThreadPool(
                1,
                new ThreadFactoryBuilder()
                        .setNameFormat("DiscoveryClient-InstanceInfoReplicator-%d")
                        .setDaemon(true)
                        .build());

        // 初始化任务执行结果的原子引用
        this.scheduledPeriodicRef = new AtomicReference<Future>();
        // 初始化应用实例复制器的任务状态为未开始
        this.started = new AtomicBoolean(false);
        // 初始化限流器
        this.rateLimiter = new RateLimiter(TimeUnit.MINUTES);
        // 初始化注册间隔，30s
        this.replicationIntervalSeconds = replicationIntervalSeconds;
        // 初始化并发数
        this.burstSize = burstSize;

        // 初始化每分钟请求阈值，60 * 2 / 30 = 4，也就是说每分钟的注册请求不得超过4次
        this.allowedRatePerMinute = 60 * this.burstSize / this.replicationIntervalSeconds;
        logger.info("InstanceInfoReplicator onDemand update allowed rate per min is {}", allowedRatePerMinute);
    }

    /**
     * 启动应用实例复制器
     */
    public void start(int initialDelayMs) {
        // 更新应用实例复制器的状态为已开始
        if (started.compareAndSet(false, true)) {
            // 设置应用实例 dirty 属性为 true，并更新应用实例最后一次设置 dirty 属性的时间
            // dirty 属性用于标记应用实例是否发生了变化
            instanceInfo.setIsDirty();  // for initial register
            // 调度当前任务，默认延迟 40s 执行
            Future next = scheduler.schedule(this, initialDelayMs, TimeUnit.SECONDS);
            // 将任务执行结果设置到原子引用中
            scheduledPeriodicRef.set(next);
        }
    }

    public void stop() {
        shutdownAndAwaitTermination(scheduler);
        started.set(false);
    }

    private void shutdownAndAwaitTermination(ExecutorService pool) {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(3, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            logger.warn("InstanceInfoReplicator stop interrupted");
        }
    }

    /**
     * 当应用实例的状态发生变更时，就会触发监听器，从而执行该方法
     * {@link com.netflix.appinfo.ApplicationInfoManager.StatusChangeListener}
     * {@link com.netflix.discovery.DiscoveryClient#statusChangeListener}
     */
    public boolean onDemandUpdate() {
        // 获取限流令牌
        if (rateLimiter.acquire(burstSize, allowedRatePerMinute)) {
            // 调度线程池没有关闭
            if (!scheduler.isShutdown()) {
                // 封装成任务丢到线程池中
                scheduler.submit(new Runnable() {
                    @Override
                    public void run() {
                        logger.debug("Executing on-demand update of local InstanceInfo");

                        // 如果上次的任务执行还未完成，直接取消
                        Future latestPeriodic = scheduledPeriodicRef.get();
                        if (latestPeriodic != null && !latestPeriodic.isDone()) {
                            logger.debug("Canceling the latest scheduled update, it will be rescheduled at the end of on demand update");
                            latestPeriodic.cancel(false);
                        }

                        // 执行任务
                        InstanceInfoReplicator.this.run();
                    }
                });
                return true;
            } else {
                logger.warn("Ignoring onDemand update due to stopped scheduler");
                return false;
            }
        } else {
            logger.warn("Ignoring onDemand update due to rate limiter");
            return false;
        }
    }

    /**
     * 重写 Runnable 接口方法，注册任务，在线程池中每 30s 一次调度执行
     */
    public void run() {
        try {
            // 刷新应用实例信息
            discoveryClient.refreshInstanceInfo();

            // 根据应用实例的 dirty 属性决定是否注册实例
            // 应用实例复制器在启动时会将 dirty 设置为了 true 并更新了最后设置时间
            // 在首次注册后 dirty 变为false，只有当应用实例信息发生变化时 dirty 属性才会变为true
            Long dirtyTimestamp = instanceInfo.isDirtyWithTime();
            if (dirtyTimestamp != null) {
                // 注册应用实例
                discoveryClient.register();
                // 设置 dirty 属性为 false
                instanceInfo.unsetIsDirty(dirtyTimestamp);
            }
        } catch (Throwable t) {
            logger.warn("There was a problem with the instance info replicator", t);
        } finally {
            // 继续下一次的调度，默认延迟30s执行
            Future next = scheduler.schedule(this, replicationIntervalSeconds, TimeUnit.SECONDS);
            // 更新下一次任务执行结果的原子引用
            scheduledPeriodicRef.set(next);
        }
    }

}
