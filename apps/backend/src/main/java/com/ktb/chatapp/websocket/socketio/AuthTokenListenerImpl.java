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

    private static final int MAX_CONCURRENT_HANDSHAKES = 100;
    private static final Semaphore HANDSHAKE_SEMAPHORE =
            new Semaphore(MAX_CONCURRENT_HANDSHAKES);

    private final JwtService jwtService;
    private final SessionService sessionService;
    private final UserRepository userRepository;
    private final ObjectProvider<ConnectionLoginHandler> socketIOChatHandlerProvider;
    private final HandshakeSessionCacheService handshakeCache;
    private final ObjectMapper objectMapper;

    @Override
    public AuthTokenResult getAuthTokenResult(Object _authToken, SocketIOClient client) {

        if (!HANDSHAKE_SEMAPHORE.tryAcquire()) {
            return new AuthTokenResult(false, Map.of(
                    "message", "Server busy",
                    "retryAfterMs", 3000
            ));
        }

        String effectiveUserId;
        String sessionId;

        try {
            SocketAuth auth;
            try {
                auth = objectMapper.convertValue(_authToken, SocketAuth.class);
            } catch (IllegalArgumentException e) {
                return new AuthTokenResult(false, Map.of("message", "Invalid auth payload"));
            }

            String token = auth.token();
            sessionId = auth.sessionId();

            if (token == null || sessionId == null) {
                return new AuthTokenResult(false, Map.of("message", "Missing token or sessionId"));
            }

            String userId;
            try {
                userId = jwtService.extractUserId(token);
            } catch (JwtException e) {
                return new AuthTokenResult(false, Map.of("message", "Invalid token"));
            }

            String cachedUserId = handshakeCache.getUserId(sessionId);
            if (cachedUserId != null) {
                effectiveUserId = cachedUserId;
            } else {
                SessionValidationResult validation =
                        sessionService.validateSessionForHandshake(userId, sessionId);

                if (!validation.isValid()) {
                    return new AuthTokenResult(false, Map.of("message", "Invalid session"));
                }

                handshakeCache.cache(sessionId, userId);
                effectiveUserId = userId;
            }

        } finally {
            HANDSHAKE_SEMAPHORE.release();
        }

        User user = userRepository.findById(effectiveUserId).orElse(null);
        if (user == null) {
            return new AuthTokenResult(false, Map.of("message", "User not found"));
        }

        socketIOChatHandlerProvider.getObject().onConnect(
                client,
                new SocketUser(
                        user.getId(),
                        user.getName(),
                        sessionId,
                        client.getSessionId().toString()
                )
        );

        return AuthTokenResult.AuthTokenResultSuccess;
    }
}