package com.ktb.chatapp.service.session;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
@RequiredArgsConstructor
public class HandshakeSessionCacheService {

    private final RedisTemplate<String, String> redisTemplate;

    private static final String PREFIX = "ws:handshake:session:";
    private static final Duration TTL = Duration.ofSeconds(30);

    public String getUserId(String sessionId) {
        return redisTemplate.opsForValue().get(PREFIX + sessionId);
    }

    public void cache(String sessionId, String userId) {
        redisTemplate.opsForValue().set(
                PREFIX + sessionId,
                userId,
                TTL
        );
    }

    public void evict(String sessionId) {
        redisTemplate.delete(PREFIX + sessionId);
    }
}