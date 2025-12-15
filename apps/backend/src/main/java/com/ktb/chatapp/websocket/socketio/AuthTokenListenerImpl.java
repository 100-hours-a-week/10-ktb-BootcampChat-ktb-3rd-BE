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

    // ✅ macOS 기준 권장: 100~300
    private static final int MAX_CONCURRENT_HANDSHAKES = 300;
    private static final Semaphore HANDSHAKE_SEMAPHORE =
            new Semaphore(MAX_CONCURRENT_HANDSHAKES);

    private final JwtService jwtService;
    private final SessionService sessionService;
    private final HandshakeSessionCacheService handshakeCache;
    private final ObjectMapper objectMapper;


    @Override
    public AuthTokenResult getAuthTokenResult(Object authPayload, SocketIOClient client) {

        String engineSessionId = client.getSessionId().toString();

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

        SocketAuth auth;
        try {
            auth = objectMapper.convertValue(authPayload, SocketAuth.class);
        } catch (IllegalArgumentException e) {
            return new AuthTokenResult(false, null);
        }
        if (auth == null || auth.token() == null || auth.sessionId() == null) {
            return new AuthTokenResult(false, null);
        }

        String userId;
        try {
            userId = jwtService.extractUserId(auth.token());
        } catch (JwtException e) {
            return new AuthTokenResult(false, null);
        }

        if (!sessionService
                .validateSessionForHandshake(userId, auth.sessionId())
                .isValid()) {
            return new AuthTokenResult(false, null);
        }

        SocketUser socketUser = new SocketUser(
                userId,
                null, // name은 JOIN_ROOM에서 채워도 됨
                auth.sessionId(),
                engineSessionId
        );

        client.set("user", socketUser);

        handshakeCache.cacheByEngineSession(
                engineSessionId,
                new CachedHandshake(userId, auth.sessionId(), null)
        );

        return AuthTokenResult.AuthTokenResultSuccess;
    }


//    @Override
//    public AuthTokenResult getAuthTokenResult(Object authPayload, SocketIOClient client) {
//
//        // 1️⃣ handshake rate limit
//        if (!HANDSHAKE_SEMAPHORE.tryAcquire()) {
//            return new AuthTokenResult(false, Map.of("message", "server busy"));
//        }
//
//        try {
//            final String engineSessionId = client.getSessionId().toString();
//
//            /* --------------------------------------------
//             * 2️⃣ EngineSession cache 우선
//             * -------------------------------------------- */
//            CachedHandshake cached = handshakeCache.getByEngineSession(engineSessionId);
//            if (cached != null) {
//                client.set("user", new SocketUser(
//                        cached.userId(),
//                        cached.username(), // null 가능
//                        cached.sessionId(),
//                        engineSessionId
//                ));
//                return AuthTokenResult.AuthTokenResultSuccess;
//            }
//
//            /* --------------------------------------------
//             * 3️⃣ auth payload 파싱
//             * -------------------------------------------- */
//            SocketAuth auth;
//            try {
//                auth = objectMapper.convertValue(authPayload, SocketAuth.class);
//            } catch (IllegalArgumentException e) {
//                return new AuthTokenResult(false, null);
//            }
//
//            if (auth == null || auth.token() == null || auth.sessionId() == null) {
//                return new AuthTokenResult(false, null);
//            }
//
//            /* --------------------------------------------
//             * 4️⃣ JWT → userId
//             * -------------------------------------------- */
//            final String userId;
//            try {
//                userId = jwtService.extractUserId(auth.token());
//            } catch (JwtException e) {
//                return new AuthTokenResult(false, null);
//            }
//
//            /* --------------------------------------------
//             * 5️⃣ 세션 검증 (STALE 허용)
//             * -------------------------------------------- */
//            SessionValidationResult validation =
//                    sessionService.validateSessionForHandshake(userId, auth.sessionId());
//
//            if (!validation.isValid()) {
//                return new AuthTokenResult(false, null);
//            }
//
//            /* --------------------------------------------
//             * 6️⃣ SocketUser (name 없음 → JOIN에서 채움)
//             * -------------------------------------------- */
//            SocketUser socketUser = new SocketUser(
//                    userId,
//                    null,
//                    auth.sessionId(),
//                    engineSessionId
//            );
//            client.set("user", socketUser);
//
//            /* --------------------------------------------
//             * 7️⃣ handshake cache
//             * -------------------------------------------- */
//            handshakeCache.cacheByEngineSession(
//                    engineSessionId,
//                    new CachedHandshake(userId, auth.sessionId(), null)
//            );
//
//            return AuthTokenResult.AuthTokenResultSuccess;
//
//        } finally {
//            // ✅ acquire 성공한 경우에만 release
//            HANDSHAKE_SEMAPHORE.release();
//        }
//    }
}
