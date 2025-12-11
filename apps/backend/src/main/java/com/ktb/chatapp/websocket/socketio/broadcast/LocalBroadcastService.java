package com.ktb.chatapp.websocket.socketio.broadcast;

import com.corundumstudio.socketio.SocketIOServer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/**
 * 로컬 브로드캐스트 서비스 (단일 서버용).
 *
 * 단일 서버 환경에서 직접 Socket.IO로 브로드캐스트한다.
 * 개발/테스트 환경 또는 단일 인스턴스 배포 시 사용.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "chat.broadcast.type", havingValue = "local")
public class LocalBroadcastService implements BroadcastService {

    private final SocketIOServer socketIOServer;

    public LocalBroadcastService(@Lazy SocketIOServer socketIOServer) {
        this.socketIOServer = socketIOServer;
    }

    @Override
    public void broadcastToRoom(String roomId, String socketEvent, Object payload) {
        socketIOServer.getRoomOperations(roomId).sendEvent(socketEvent, payload);
        log.debug("Broadcast to room (local) - room: {}, socketEvent: {}", roomId, socketEvent);
    }

    @Override
    public void broadcastToRoom(String eventType, String roomId, String socketEvent, Object payload) {
        broadcastToRoom(roomId, socketEvent, payload);
    }
}
