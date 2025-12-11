package com.ktb.chatapp.service.ratelimit;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.Duration;

@Service
@RequiredArgsConstructor
public class RateLimitRedisService {

    private final RedisTemplate<String, String> redisTemplate;
    private static final String PREFIX = "rl:"; // rate limit
    private static final int LIMIT_PER_SECOND = 5;

    public boolean isAllowed(String userId) {
        long currentSecond = Instant.now().getEpochSecond();
        String key = PREFIX + userId + ":" + currentSecond;

        // 증가 후 카운트 반환
        Long count = redisTemplate.opsForValue().increment(key);

        // 첫 증가라면 TTL 설정
        if (count != null && count == 1L) {
            redisTemplate.expire(key, Duration.ofSeconds(1));
        }

        return count != null && count <= LIMIT_PER_SECOND;
    }
}