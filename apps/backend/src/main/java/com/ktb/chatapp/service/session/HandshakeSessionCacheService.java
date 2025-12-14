package com.ktb.chatapp.service.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class HandshakeSessionCacheService {

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String PREFIX = "ws:handshake:engine:";
    private static final Duration TTL = Duration.ofSeconds(60);

    public void cacheByEngineSession(String engineSessionId, CachedHandshake handshake) {
        try {
            String json = objectMapper.writeValueAsString(handshake);
            redisTemplate.opsForValue().set(PREFIX + engineSessionId, json, TTL);
        } catch (Exception e) {
            log.warn("[HANDSHAKE][CACHE] write failed engineSessionId={}", engineSessionId, e);
            // 절대 throw 하지 말기
        }
    }

    public CachedHandshake getByEngineSession(String engineSessionId) {
        String json = redisTemplate.opsForValue().get(PREFIX + engineSessionId);
        if (json == null) return null;

        try {
            return objectMapper.readValue(json, CachedHandshake.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // 캐시 깨졌으면 evict 하고 null
            evictByEngineSession(engineSessionId);
            return null;
        }
    }

    public void evictByEngineSession(String engineSessionId) {
        redisTemplate.delete(PREFIX + engineSessionId);
    }
}

//    /**
//     * Engine.IO sessionId 기준으로 userId 조회
//     */
//    public String getUserIdByEngineSession(String engineSessionId) {
//        return redisTemplate.opsForValue().get(PREFIX + engineSessionId);
//    }

//    /**
//     * AuthTokenListener에서만 호출
//     */
//    public void cacheByEngineSession(String engineSessionId, String userId) {
//        redisTemplate.opsForValue().set(
//                PREFIX + engineSessionId,
//                userId,
//                TTL
//        );
//    }
//
//    public void evictByEngineSession(String engineSessionId) {
//        redisTemplate.delete(PREFIX + engineSessionId);
//    }
//}
