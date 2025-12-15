package com.ktb.chatapp.repository;

import com.ktb.chatapp.model.Session;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SessionRepository extends MongoRepository<Session, String>, SessionRepositoryCustom {
    Optional<Session> findBySessionId(String sessionId);
    void deleteByUserId(String userId);
    @Query("{ 'sessionId': ?0 }")
    void updateLastActivity(String sessionId, long lastActivity);
}
