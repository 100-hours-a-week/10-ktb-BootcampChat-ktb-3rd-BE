package com.ktb.chatapp.websocket.socketio;

import com.corundumstudio.socketio.AuthTokenListener;
import com.corundumstudio.socketio.AuthTokenResult;
import com.corundumstudio.socketio.SocketIOClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ktb.chatapp.dto.SocketAuth;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.UserRepository;
import com.ktb.chatapp.service.JwtService;
import com.ktb.chatapp.service.SessionService;
import com.ktb.chatapp.service.SessionValidationResult;
import com.ktb.chatapp.service.session.CachedHandshake;
import com.ktb.chatapp.service.session.HandshakeSessionCacheService;
import com.ktb.chatapp.websocket.socketio.handler.ConnectionLoginHandler;
import java.util.Map;
import java.util.concurrent.Semaphore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

/**
 * Socket.IO Authorization Handler
 * socket.handshake.auth.token과 sessionId를 처리한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
public class AuthTokenListenerImpl implements AuthTokenListener {

//    private static final int MAX_CONCURRENT_HANDSHAKES = 1000;
//    private static final Semaphore HANDSHAKE_SEMAPHORE =
//            new Semaphore(MAX_CONCURRENT_HANDSHAKES);

    private final JwtService jwtService;
    private final SessionService sessionService;
    private final UserRepository userRepository;
    private final ObjectProvider<ConnectionLoginHandler> socketIOChatHandlerProvider;
    private final HandshakeSessionCacheService handshakeCache;
    private final ObjectMapper objectMapper;

    @Override
    public AuthTokenResult getAuthTokenResult(Object authPayload, SocketIOClient client) {

        final String engineSessionId = client.getSessionId().toString();

        /* -------------------------------------------------
         * 1️⃣ EngineSession 기반 캐시 우선 (재연결 / E2E 핵심)
         * ------------------------------------------------- */
        CachedHandshake cached = handshakeCache.getByEngineSession(engineSessionId);
        if (cached != null) {
            client.set("user", new SocketUser(
                    cached.userId(),
                    cached.username(),
                    cached.sessionId(),
                    engineSessionId
            ));
            return AuthTokenResult.AuthTokenResultSuccess;
        }

        /* -------------------------------------------------
         * 2️⃣ auth payload 파싱
         * ------------------------------------------------- */
        SocketAuth auth;
        try {
            auth = objectMapper.convertValue(authPayload, SocketAuth.class);
        } catch (IllegalArgumentException e) {
            return new AuthTokenResult(false, null);
        }

        if (auth == null || auth.token() == null || auth.sessionId() == null) {
            return new AuthTokenResult(false, null);
        }

        /* -------------------------------------------------
         * 3️⃣ JWT → userId 추출
         * ------------------------------------------------- */
        final String userId;
        try {
            userId = jwtService.extractUserId(auth.token());
        } catch (JwtException e) {
            return new AuthTokenResult(false, null);
        }

        /* -------------------------------------------------
         * 4️⃣ 세션 검증 (STALE 허용)
         * ------------------------------------------------- */
        SessionValidationResult validation =
                sessionService.validateSessionForHandshake(userId, auth.sessionId());

        if (!validation.isValid()) {
            return new AuthTokenResult(false, null);
        }

        /* -------------------------------------------------
         * 5️⃣ User 조회 (name 채우기)
         * ------------------------------------------------- */
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return new AuthTokenResult(false, null);
        }

        /* -------------------------------------------------
         * 6️⃣ SocketUser 세팅
         * ------------------------------------------------- */
        SocketUser socketUser = new SocketUser(
                user.getId(),
                null,              // ✅ 핵심
                auth.sessionId(),
                engineSessionId
        );

        client.set("user", socketUser);

        /* -------------------------------------------------
         * 7️⃣ Handshake 캐시 저장 (best-effort)
         * ------------------------------------------------- */
        handshakeCache.cacheByEngineSession(
                engineSessionId,
                new CachedHandshake(user.getId(), auth.sessionId(), user.getName())
        );

        log.info("[AUTH] success userId={} engineSession={}", user.getId(), engineSessionId);
        return AuthTokenResult.AuthTokenResultSuccess;
    }
}