package com.ktb.chatapp.websocket.socketio.pubsub;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ktb.chatapp.config.RedisPubSubConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis Pub/Sub 메시지 발행자.
 *
 * [역할]
 * - 채팅 이벤트를 Redis 채널에 발행 (PUBLISH)
 * - 발행된 메시지는 모든 구독 서버에게 전달됨
 *
 * [사용 시점]
 * - 새 메시지 전송 시
 * - 유저 입장/퇴장 시
 * - AI 응답 시
 * - 읽음 상태 변경 시
 * - 리액션 추가/제거 시
 *
 * [흐름]
 * ChatMessageHandler → RedisMessagePublisher.publish() → Redis → 모든 서버의 Subscriber
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisMessagePublisher {

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 채팅 이벤트를 Redis에 발행
     *
     * @param event 발행할 이벤트 (ChatBroadcastEvent)
     *
     * [동작]
     * 1. ChatBroadcastEvent를 JSON으로 직렬화
     * 2. Redis "chat:messages" 채널에 PUBLISH
     * 3. 모든 구독 서버가 이 메시지를 수신
     *
     * [예시]
     * 서버1에서 유저A가 메시지 전송:
     * publish(ChatBroadcastEvent{
     *   eventType: "MESSAGE",
     *   roomId: "room123",
     *   payload: {...메시지 데이터...}
     * })
     * → Redis PUBLISH "chat:messages" "{...JSON...}"
     * → 서버1, 서버2, ..., 서버10 모두 수신
     */
    public void publish(ChatBroadcastEvent event) {
        try {
            String message = objectMapper.writeValueAsString(event);
            redisTemplate.convertAndSend(RedisPubSubConfig.CHAT_CHANNEL, message);

            log.debug("Redis Pub/Sub 메시지 발행 - type: {}, room: {}",
                    event.getEventType(), event.getRoomId());
        } catch (JsonProcessingException e) {
            log.error("Redis 메시지 직렬화 실패 - eventType: {}", event.getEventType(), e);
        }
    }
}
