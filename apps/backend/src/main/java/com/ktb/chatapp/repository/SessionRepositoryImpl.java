package com.ktb.chatapp.repository;

import com.ktb.chatapp.model.Session;
import com.ktb.chatapp.service.SessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

import java.time.Instant;

@Repository
@RequiredArgsConstructor
public class SessionRepositoryImpl implements SessionRepositoryCustom {

    private final MongoTemplate mongoTemplate;

    @Override
    public void updateLastActivity(String sessionId, long lastActivity) {

        Query query = new Query(Criteria.where("sessionId").is(sessionId));

        Update update = new Update()
                .set("lastActivity", lastActivity)
                .set("expiresAt", Instant.now().plusSeconds(SessionService.SESSION_TTL_SEC));

        mongoTemplate.updateFirst(query, update, Session.class);
    }
}