package com.ktb.chatapp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class SocketAuthExecutorConfig {

    @Bean
    public Executor socketAuthExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("socket-auth-");
        executor.setCorePoolSize(64);
        executor.setMaxPoolSize(256);
        executor.setQueueCapacity(5000);
        executor.initialize();
        return executor;
    }
}
