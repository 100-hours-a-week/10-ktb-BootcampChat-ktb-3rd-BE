package com.ktb.chatapp.websocket.socketio.broadcast;

import com.ktb.chatapp.websocket.socketio.pubsub.ChatBroadcastEvent;
import com.ktb.chatapp.websocket.socketio.pubsub.RedisMessagePublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Redis Pub/Sub 기반 브로드캐스트 서비스.
 *
 * 멀티 서버 환경에서 모든 서버에 메시지를 전파한다.
 * Redis에 PUBLISH하면 모든 구독 서버가 메시지를 수신하여
 * 각자의 Socket.IO 클라이언트에게 전달한다.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "chat.broadcast.type", havingValue = "redis", matchIfMissing = true)
@RequiredArgsConstructor
public class RedisBroadcastService implements BroadcastService {

    private final RedisMessagePublisher redisMessagePublisher;

    @Override
    public void broadcastToRoom(String roomId, String socketEvent, Object payload) {
        broadcastToRoom(ChatBroadcastEvent.TYPE_MESSAGE, roomId, socketEvent, payload);
    }

    @Override
    public void broadcastToRoom(String eventType, String roomId, String socketEvent, Object payload) {
        ChatBroadcastEvent event = ChatBroadcastEvent.builder()
                .eventType(eventType)
                .roomId(roomId)
                .socketEvent(socketEvent)
                .payload(payload)
                .build();

        redisMessagePublisher.publish(event);

        log.debug("Broadcast to room via Redis - eventType: {}, room: {}, socketEvent: {}",
                eventType, roomId, socketEvent);
    }
}
