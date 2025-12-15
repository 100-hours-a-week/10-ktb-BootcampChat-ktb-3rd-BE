package com.ktb.chatapp.websocket.socketio.handler;

import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.annotation.OnConnect;
import com.corundumstudio.socketio.annotation.OnDisconnect;
import com.ktb.chatapp.websocket.socketio.*;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.*;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.TaskScheduler;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Cache;
import org.springframework.stereotype.Component;

import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.*;

/**
 * Socket.IO Chat Handler
 * 어노테이션 기반 이벤트 처리와 인증 흐름을 정의한다.
 * 연결/해제 및 중복 로그인 처리를 담당
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
public class ConnectionLoginHandler {

    private final SocketIOServer socketIOServer;
    private final ConnectedUsers connectedUsers;
    private final UserRooms userRooms;
    private final RoomJoinHandler roomJoinHandler;
    private final RoomLeaveHandler roomLeaveHandler;
    private final TaskScheduler taskScheduler;
    private final RedisChatDataStore redisChatDataStore;
    private final java.util.concurrent.Executor socketAuthExecutor;
    private final Cache<String, Boolean> handshakeCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(180)) // 3분 유지
            .maximumSize(10_000)
            .build();
    
    public ConnectionLoginHandler(
            SocketIOServer socketIOServer,
            ConnectedUsers connectedUsers,
            UserRooms userRooms,
            RoomJoinHandler roomJoinHandler,
            RoomLeaveHandler roomLeaveHandler,
            MeterRegistry meterRegistry,
            TaskScheduler taskScheduler,
            RedisChatDataStore chatDataStore,
            java.util.concurrent.Executor socketAuthExecutor

    ) {
        this.socketIOServer = socketIOServer;
        this.connectedUsers = connectedUsers;
        this.userRooms = userRooms;
        this.roomJoinHandler = roomJoinHandler;
        this.roomLeaveHandler = roomLeaveHandler;
        this.taskScheduler = taskScheduler;
        this.redisChatDataStore = chatDataStore;
        this.socketAuthExecutor = socketAuthExecutor;


        // Register gauge metric for concurrent users
        Gauge.builder("socketio.concurrent.users", () -> chatDataStore.connectedCount())
                .description("Current number of concurrent Socket.IO users")
                .register(meterRegistry);
    }

    
    /**
     * auth 처리가 선행되어야 해서 @OnConnect 대신 별도 메서드로 구현
     */
    @OnConnect
    public void onConnect(SocketIOClient client) {


        SocketUser socketUser = client.get("user");

        if (socketUser == null) {
            log.warn("Connect without auth, disconnect");
            client.disconnect();
            return;
        }

        String userId = socketUser.id();
        String socketId = client.getSessionId().toString();
        SocketUser existing = connectedUsers.get(userId);
        if (existing != null) {
            notifyDuplicateLogin(client, userId);
        }
        // 중복 로그인 처리
//        notifyDuplicateLogin(client, userId);

        connectedUsers.set(userId, new SocketUser(
                userId,
                socketUser.name(),
                socketUser.authSessionId(),
                socketId
        ));

        redisChatDataStore.incrementConnected();

        log.info("[CONNECT] userId={} socketId={}", userId, socketId);
    }
    
    @OnDisconnect
    public void onDisconnect(SocketIOClient client) {
        String userId = getUserId(client);
        String userName = getUserName(client);
        
        try {
            if (userId == null) {
                return;
            }
            
            userRooms.get(userId).forEach(roomId -> {
                roomLeaveHandler.handleLeaveRoom(client, roomId);
            });
            String socketId = client.getSessionId().toString();
            
            // 해당 사용자의 현재 활성 연결인 경우에만 정리
            var socketUser = connectedUsers.get(userId);
            if (socketUser != null && socketId.equals(socketUser.socketId())) {
                connectedUsers.del(userId);
                redisChatDataStore.decrementConnected();
            } else {
                log.warn("Socket.IO disconnect: User {} has a different active connection. Skipping cleanup.", userId);
            }

            client.leaveRooms(Set.of("user:" + userId, "room-list"));
            client.del("user");
//            client.disconnect();
            
        } catch (Exception e) {
            log.error("Error handling Socket.IO disconnection", e);
            client.sendEvent(ERROR, Map.of(
                "message", "연결 종료 처리 중 오류가 발생했습니다."
            ));
        }
        
    }
    
    private SocketUser getUserDto(SocketIOClient client) {
        return client.get("user");
    }
    
    private String getUserId(SocketIOClient client) {
        SocketUser user = getUserDto(client);
        return user != null ? user.id() : null;
    }
    
    private String getUserName(SocketIOClient client) {
        SocketUser user = getUserDto(client);
        return user != null ? user.name() : null;
    }

    private void notifyDuplicateLogin(SocketIOClient client, String userId) {
        var socketUser = connectedUsers.get(userId);
        if (socketUser == null) return;

        String existingSocketId = socketUser.socketId();
        SocketIOClient existingClient = null;
        try {
            existingClient = socketIOServer.getClient(UUID.fromString(existingSocketId));
        } catch (IllegalArgumentException e) {
            log.warn("existingSocketId is not UUID: {}", existingSocketId);
            return;
        }
        if (existingClient == null) return;
        final SocketIOClient targetClient = existingClient;

        String deviceInfo = client.getHandshakeData().getHttpHeaders().get("User-Agent");
        if (deviceInfo == null) deviceInfo = "unknown";

        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "new_login_attempt");
        payload.put("deviceInfo", deviceInfo);
        payload.put("ipAddress", client.getRemoteAddress() != null ? client.getRemoteAddress().toString() : "unknown");
        payload.put("timestamp", System.currentTimeMillis());

        existingClient.sendEvent(DUPLICATE_LOGIN, payload);

        taskScheduler.schedule(() -> {
            Map<String, Object> endPayload = new HashMap<>();
            endPayload.put("reason", "duplicate_login");
            endPayload.put("message", "다른 기기에서 로그인하여 현재 세션이 종료되었습니다.");

            try {
                targetClient.sendEvent(SESSION_ENDED, endPayload);
            } catch (Exception e) {
                log.error("Error sending session ended", e);
            }
        }, new Date(System.currentTimeMillis() + 10000));
    }
    
    /**
     * TODO 멀티 클러스터에서 동작 안함 다중 노드의 경우 다른  노드에 접속된 사용자는 통보 불가함
     * socketIOServer.getRoomOperations("user:" + userId) 로 처리 변경.
     */
//    private void notifyDuplicateLogin(SocketIOClient client, String userId) {
//        var socketUser = connectedUsers.get(userId);
//        if (socketUser == null) {
//            return;
//        }
//        String existingSocketId = socketUser.socketId();
//        SocketIOClient existingClient = socketIOServer.getClient(UUID.fromString(existingSocketId));
//        if (existingClient == null) {
//            return;
//        }
//
//        // Send duplicate login notification
//        existingClient.sendEvent(DUPLICATE_LOGIN, Map.of(
//                "type", "new_login_attempt",
//                "deviceInfo", client.getHandshakeData().getHttpHeaders().get("User-Agent"),
//                "ipAddress", client.getRemoteAddress().toString(),
//                "timestamp", System.currentTimeMillis()
//        ));
//
//        new Thread(() -> {
//            try {
//                Thread.sleep(Duration.ofSeconds(10));
//                existingClient.sendEvent(SESSION_ENDED, Map.of(
//                        "reason", "duplicate_login",
//                        "message", "다른 기기에서 로그인하여 현재 세션이 종료되었습니다."
//                ));
//            } catch (InterruptedException e) {
//                Thread.currentThread().interrupt();
//                log.error("Error in duplicate login notification thread", e);
//            }
//        }).start();
//    }
}
