package com.ktb.chatapp.repository;

public interface SessionRepositoryCustom {
    void updateLastActivity(String sessionId, long lastActivity);
}
