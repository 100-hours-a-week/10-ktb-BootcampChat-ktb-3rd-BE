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
    private static final Duration TTL = Duration.ofSeconds(300);

    private final ConcurrentMap<String, CachedHandshake> localCache =
            new ConcurrentHashMap<>();

    public void cacheByEngineSession(String engineSessionId, CachedHandshake handshake) {

        // 1️⃣ local cache는 무조건
        localCache.put(engineSessionId, handshake);

        // 2️⃣ Redis는 실패해도 OK
        try {
            String json = objectMapper.writeValueAsString(handshake);
            redisTemplate.opsForValue().set(
                    PREFIX + engineSessionId,
                    json,
                    TTL
            );
        } catch (Exception e) {
            log.debug("[HANDSHAKE][CACHE] redis write skipped engineSessionId={}", engineSessionId);
        }
    }

    public CachedHandshake getByEngineSession(String engineSessionId) {

        // 1️⃣ local hit
        CachedHandshake local = localCache.get(engineSessionId);
        if (local != null) {
            return local;
        }

        // 2️⃣ redis hit (optional)
        try {
            String json = redisTemplate.opsForValue().get(PREFIX + engineSessionId);
            if (json == null) return null;

            CachedHandshake parsed =
                    objectMapper.readValue(json, CachedHandshake.class);

            // local warm
            localCache.put(engineSessionId, parsed);
            return parsed;

        } catch (Exception e) {
            // ❗ 절대 throw 금지
            log.debug("[HANDSHAKE][CACHE] redis read failed engineSessionId={}", engineSessionId);
            return null;
        }
    }
    public void evictByEngineSession(String engineSessionId) {
        localCache.remove(engineSessionId);
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
