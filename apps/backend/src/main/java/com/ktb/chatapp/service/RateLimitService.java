package com.ktb.chatapp.service;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import static java.net.InetAddress.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimitService {

    private final RedisTemplate<String, String> redis;

    @Value("${HOSTNAME:''}")
    private String hostName;
    
    @PostConstruct
    public void init() {
        if (!hostName.isEmpty()) {
            return;
        }
        hostName = generateHostname();
    }
    
    private String generateHostname() {
        try {
            return getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        }
    }


    public RateLimitCheckResult checkRateLimit(String clientId, int maxRequests, Duration window) {
        String key = "rate:" + hostName + ":" + clientId;

        // 윈도우(seconds)
        long windowSeconds = Math.max(window.getSeconds(), 1);

        return this.redis.execute((RedisCallback<RateLimitCheckResult>) conn -> {

            byte[] rawKey = key.getBytes();

            // 1) INCR (원자적 증가)
            Long count = conn.stringCommands().incr(rawKey);

            // 2) 처음 생성된 키라면 TTL 설정
            if (count != null && count == 1L) {
                conn.keyCommands().expire(rawKey, windowSeconds);
            }

            // 3) 제한 초과 여부 판단
            if (count != null && count > maxRequests) {

                Long ttl = conn.keyCommands().ttl(rawKey);
                long resetSeconds = (ttl != null && ttl > 0) ? ttl : windowSeconds;

                return RateLimitCheckResult.rejected(
                        maxRequests,
                        windowSeconds,
                        Instant.now().plusSeconds(resetSeconds).getEpochSecond(),
                        resetSeconds
                );
            }

            Long ttl = conn.keyCommands().ttl(rawKey);
            long remainingTtl = (ttl != null && ttl > 0) ? ttl : windowSeconds;

            int remaining = (int) Math.max(0, maxRequests - (count != null ? count : 1));

            return RateLimitCheckResult.allowed(
                    maxRequests,
                    remaining,
                    windowSeconds,
                    Instant.now().plusSeconds(remainingTtl).getEpochSecond(),
                    remainingTtl
            );
        });
    }
}
