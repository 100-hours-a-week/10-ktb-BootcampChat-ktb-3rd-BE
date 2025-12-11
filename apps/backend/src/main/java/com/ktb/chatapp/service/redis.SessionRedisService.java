package com.ktb.chatapp.service.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ktb.chatapp.model.Session;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;


@Service
@Primary
@ConditionalOnProperty(name = "session.store", havingValue = "redis")
@RequiredArgsConstructor
public class RedisSessionStore implements SessionStore {

    private final RedisTemplate<String, String> redis;
    private final ObjectMapper mapper;

    private String sessionKey(String sessionId) {
        return "session:session:" + sessionId;
    }

    private String userSessionsKey(String userId) {
        return "session:user:" + userId;
    }

    @Override
    public Session save(Session session) {

        try {
            String sessionJson = mapper.writeValueAsString(session);
            long ttlMillis = session.getExpiresAt().toEpochMilli() - Instant.now().toEpochMilli();
            if (ttlMillis <= 500) ttlMillis = 1000;

            // 1) 세션 정보 저장
            redis.opsForValue().set(sessionKey(session.getSessionId()), sessionJson, Duration.ofMillis(ttlMillis));

            // 2) userId → sessionId 목록에 추가
            redis.opsForSet().add(userSessionsKey(session.getUserId()), session.getSessionId());

            return session;
        } catch (Exception e) {
            throw new RuntimeException("세션 저장 실패", e);
        }
    }

    @Override
    public Optional<Session> findBySessionId(String sessionId) {
        String json = redis.opsForValue().get(sessionKey(sessionId));

        if (json == null) return Optional.empty();

        try {
            return Optional.of(mapper.readValue(json, Session.class));
        } catch (Exception e) {
            throw new RuntimeException("세션 역직렬화 실패", e);
        }
    }

    @Override
    public void delete(String userId, String sessionId) {
        // 1) session:session:{sessionId} 삭제
        redis.delete(sessionKey(sessionId));

        // 2) userSessions 에서 sessionId 제거
        redis.opsForSet().remove(userSessionsKey(userId), sessionId);
    }

    @Override
    public void deleteAll(String userId) {
        String key = userSessionsKey(userId);
        var sessionIds = redis.opsForSet().members(key);

        if (sessionIds != null) {
            for (String sessionId : sessionIds) {
                redis.delete(sessionKey(sessionId));
            }
        }

        redis.delete(key);
    }
}