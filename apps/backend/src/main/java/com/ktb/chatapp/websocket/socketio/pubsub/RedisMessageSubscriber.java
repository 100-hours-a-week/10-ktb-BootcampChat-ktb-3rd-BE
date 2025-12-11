package com.ktb.chatapp.websocket.socketio.pubsub;

import com.corundumstudio.socketio.SocketIOServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Redis Pub/Sub 메시지 수신자.
 *
 * [역할]
 * - Redis 채널에서 메시지 수신 (SUBSCRIBE)
 * - 수신한 메시지를 해당 Socket.IO Room에 브로드캐스트
 *
 * [핵심 개념]
 * 이 클래스가 Redis와 Socket.IO를 연결하는 "다리" 역할을 합니다:
 *
 *   Redis (Pub/Sub)  →  RedisMessageSubscriber  →  Socket.IO (클라이언트)
 *
 * [동작 흐름]
 * 1. 서버2에서 유저가 메시지 전송
 * 2. 서버2가 Redis에 PUBLISH
 * 3. 서버1, 서버3, ..., 서버10의 RedisMessageSubscriber가 메시지 수신
 * 4. 각 서버의 Subscriber가 자신의 Socket.IO 서버를 통해 클라이언트에게 전송
 *
 * [중요]
 * - 각 서버는 자신에게 연결된 클라이언트에게만 전송
 * - Socket.IO Room 기능을 사용하여 해당 방의 클라이언트만 수신
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
public class RedisMessageSubscriber {

    /**
     * Socket.IO 서버 - 이 서버에 연결된 클라이언트들에게 메시지 전송
     * - 10대 서버 각각이 자신만의 SocketIOServer 인스턴스를 가짐
     * - 각 서버는 자신에게 연결된 클라이언트에게만 전송 가능
     *
     * [@Lazy 사용 이유]
     * SpringAnnotationScanner가 BeanPostProcessor로서 모든 Bean 생성 시 @OnEvent 핸들러를 스캔함.
     * 이 과정에서 SocketIOServer가 아직 생성 중인데 다시 요청되어 순환 참조 발생.
     * @Lazy로 프록시를 주입받아 실제 사용 시점까지 초기화를 지연시킴.
     */
    private final SocketIOServer socketIOServer;

    private final ObjectMapper objectMapper;

    public RedisMessageSubscriber(@Lazy SocketIOServer socketIOServer, ObjectMapper objectMapper) {
        this.socketIOServer = socketIOServer;
        this.objectMapper = objectMapper;
    }

    /**
     * Redis에서 메시지 수신 시 호출되는 메서드.
     *
     * [호출 시점]
     * - 다른 서버(또는 자기 자신)가 Redis에 PUBLISH할 때
     * - Spring의 RedisMessageListenerContainer가 자동으로 이 메서드 호출
     *
     * @param message Redis에서 수신한 JSON 문자열
     *
     * [처리 과정]
     * 1. JSON을 ChatBroadcastEvent로 역직렬화
     * 2. eventType에 따라 적절한 Socket.IO 이벤트 발송
     * 3. roomId에 해당하는 Room의 클라이언트들만 수신
     */
    public void onMessage(String message) {
        try {
            ChatBroadcastEvent event = objectMapper.readValue(message, ChatBroadcastEvent.class);

            log.debug("Redis 메시지 수신 - type: {}, room: {}, socketEvent: {}",
                    event.getEventType(), event.getRoomId(), event.getSocketEvent());

            // Socket.IO Room에 이벤트 브로드캐스트
            // - roomId에 join한 클라이언트들에게만 전송됨
            // - 이 서버에 연결되지 않은 클라이언트는 다른 서버에서 처리
            socketIOServer.getRoomOperations(event.getRoomId())
                    .sendEvent(event.getSocketEvent(), event.getPayload());

        } catch (Exception e) {
            log.error("Redis 메시지 처리 실패 - message: {}", message, e);
        }
    }
}
