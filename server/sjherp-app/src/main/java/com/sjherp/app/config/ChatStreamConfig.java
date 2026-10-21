package com.sjherp.app.config;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 聊天 SSE 线程池（M7-T03）：把 LLM 流式读取从 Tomcat 请求线程挪开，还 D-4。
 */
@Configuration
public class ChatStreamConfig {

    @Bean(name = "chatStreamExecutor")
    public Executor chatStreamExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(64);
        executor.setThreadNamePrefix("chat-sse-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    @Bean(name = "chatHeartbeatScheduler", destroyMethod = "shutdown")
    public ScheduledExecutorService chatHeartbeatScheduler() {
        return Executors.newSingleThreadScheduledExecutor(new CustomizableThreadFactory("chat-sse-ping-"));
    }
}
