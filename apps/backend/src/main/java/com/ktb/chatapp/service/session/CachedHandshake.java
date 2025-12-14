package com.ktb.chatapp.service.session;

public record CachedHandshake(
        String userId, String sessionId, String username
) {
}
