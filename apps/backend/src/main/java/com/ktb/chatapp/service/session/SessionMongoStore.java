package com.ktb.chatapp.service.session;

import com.ktb.chatapp.model.Session;
import com.ktb.chatapp.repository.SessionRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * MongoDB implementation of SessionStore.
 * Uses SessionRepository for persistence.
 */
@Component
@ConditionalOnProperty(name = "session.store", havingValue = "mongo")
@RequiredArgsConstructor
public class SessionMongoStore implements SessionStore {

    private final SessionRepository sessionRepository;

    @Override
    public Optional<Session> findBySessionId(String sessionId) {
        return sessionRepository.findBySessionId(sessionId);
    }

    @Override
    public Session save(Session session) {
        return sessionRepository.save(session);
    }

    @Override
    public void delete(String userId, String sessionId) {
        Session session = sessionRepository.findBySessionId(sessionId).orElse(null);
        if (session != null && session.getUserId().equals(userId)) {
            sessionRepository.delete(session);
        }
    }

    @Override
    public void deleteAll(String userId) {
        sessionRepository.deleteByUserId(userId);
    }
}
