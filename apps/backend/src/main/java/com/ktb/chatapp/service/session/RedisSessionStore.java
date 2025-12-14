package com.ktb.chatapp.service.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ktb.chatapp.model.Session;
import com.ktb.chatapp.service.SessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;


@Slf4j
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

            final byte[] keySessionBytes = sessionKey(session.getSessionId()).getBytes();
            final byte[] sessionJsonBytes = sessionJson.getBytes();
            final byte[] keyUserSessionsBytes = userSessionsKey(session.getUserId()).getBytes();
            final byte[] sessionIdBytes = session.getSessionId().getBytes();
            final long finalTtlMillis = ttlMillis;

            // --- MULTI / EXEC ---
            List<Object> results = redis.execute((RedisCallback<List<Object>>) connection -> {

                connection.multi();

                // 1) 세션 저장
                connection.stringCommands().set(
                        keySessionBytes,
                        sessionJsonBytes
                );

                // 2) TTL 설정
                connection.keyCommands().pExpire(
                        keySessionBytes,
                        finalTtlMillis
                );

                // 3) 유저의 세션 목록에 sessionId 추가
                connection.setCommands().sAdd(
                        keyUserSessionsBytes,
                        sessionIdBytes
                );

                return connection.exec();
            });

            if (results == null || results.isEmpty()) {
                throw new IllegalStateException("Redis MULTI EXEC failed");
            }

            String check = redis.opsForValue().get(sessionKey(session.getSessionId()));

            if (check == null) {
                // propagation delay 발생 가능 → 5ms만 기다렸다가 재확인
                Thread.sleep(5);
                check = redis.opsForValue().get(sessionKey(session.getSessionId()));
            }

            if (check == null) {
                throw new IllegalStateException("Redis session not ready (propagation delay)");
            }

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
        try {
            String key = userSessionsKey(userId);
            var sessionIds = redis.opsForSet().members(key);

            if (sessionIds != null) {
                for (String sessionId : sessionIds) {
                    try {
                        redis.delete(sessionKey(sessionId));
                    } catch (Exception ex) {
                        log.warn("단일 세션 삭제 실패(userId={}, sessionId={})", userId, sessionId, ex);
                    }
                }
            }

            redis.delete(key);
        } catch (Exception e) {
            log.error("deleteAll 실패 (전체 무시)", e);
        }
    }

    @Override
    public void updateLastActivityBatch(String sessionId, long lastActivity) {
        try {
            String key = sessionKey(sessionId);
            String json = redis.opsForValue().get(key);

            if (json == null) return;

            Session session = mapper.readValue(json, Session.class);
            session.setLastActivity(lastActivity);
            session.setExpiresAt(Instant.now().plusSeconds(SessionService.SESSION_TTL_SEC));

            redis.opsForValue().set(key, mapper.writeValueAsString(session));
        } catch (Exception e) {
            log.error("Failed batch update for sessionId {}", sessionId, e);
        }
    }
}