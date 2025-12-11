package com.ktb.chatapp.service.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ktb.chatapp.model.Session;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class SessionRedisService {

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private static final String PREFIX = "session:";

    public void save(Session session) {
        try {
            String key = PREFIX + session.getSessionId();
            String value = objectMapper.writeValueAsString(session);
            long ttl = session.getExpiresAt().toEpochMilli() - Instant.now().toEpochMilli();
            redisTemplate.opsForValue().set(key, value, Duration.ofMillis(ttl));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Redis Session serialize 실패", e);
        }
    }

    public Session get(String sessionId) {
        String key = PREFIX + sessionId;
        String json = redisTemplate.opsForValue().get(key);
        if (json == null) return null;
        try {
            return objectMapper.readValue(json, Session.class);
        } catch (Exception e) {
            throw new RuntimeException("Redis Session deserialize 실패", e);
        }
    }

    public void delete(String sessionId) {
        redisTemplate.delete(PREFIX + sessionId);
    }
}
