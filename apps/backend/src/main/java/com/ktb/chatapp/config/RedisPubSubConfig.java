package com.ktb.chatapp.config;

import com.ktb.chatapp.websocket.socketio.pubsub.RedisMessageSubscriber;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;

/**
 * Redis Pub/Sub 설정.
 *
 * [왜 Pub/Sub가 필요한가?]
 *
 * 문제 상황 (Pub/Sub 없이):
 * ┌─────────────┐                    ┌─────────────┐
 * │  서버1 (EC2) │                    │  서버2 (EC2) │
 * │  userA 연결  │                    │  userB 연결  │
 * │             │                    │             │
 * │ userA가 메시지 전송               │             │
 * │ → 서버1에만 브로드캐스트           │ → userB는 못 받음!
 * └─────────────┘                    └─────────────┘
 *
 * 해결 (Pub/Sub 사용):
 * ┌─────────────┐     Redis Pub/Sub    ┌─────────────┐
 * │  서버1 (EC2) │ ──── PUBLISH ────→  │             │
 * │  userA 연결  │                      │    Redis    │
 * │             │ ←── SUBSCRIBE ────  │   (채널)     │
 * └─────────────┘                      │             │
 *                                      │             │
 * ┌─────────────┐                      │             │
 * │  서버2 (EC2) │ ←── SUBSCRIBE ────  │             │
 * │  userB 연결  │                      └─────────────┘
 * │             │
 * │ userB도 메시지 수신!
 * └─────────────┘
 *
 * [동작 방식]
 * 1. 모든 서버가 "chat:messages" 채널을 구독 (SUBSCRIBE)
 * 2. 어떤 서버에서 메시지 발생 → Redis에 발행 (PUBLISH)
 * 3. Redis가 모든 구독 서버에게 메시지 전달
 * 4. 각 서버는 자신에게 연결된 클라이언트에게만 Socket.IO로 전송
 */
@Slf4j
@Configuration
public class RedisPubSubConfig {

    /**
     * 채팅 메시지용 Redis 채널
     * - 모든 서버가 이 채널을 구독
     * - 채팅 메시지, 입장/퇴장, AI 응답 등 모든 실시간 이벤트가 이 채널로 전파
     */
    public static final String CHAT_CHANNEL = "chat:messages";

    /**
     * Redis 메시지 리스너 컨테이너
     * - Spring이 Redis 구독을 관리하는 컨테이너
     * - 백그라운드 스레드에서 Redis 메시지를 수신
     *
     * @param connectionFactory Redis 연결 팩토리
     * @param listenerAdapter   메시지 수신 시 호출될 어댑터
     */
    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            MessageListenerAdapter listenerAdapter) {

        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);

        // "chat:messages" 채널 구독 등록
        container.addMessageListener(listenerAdapter, new ChannelTopic(CHAT_CHANNEL));

        log.info("Redis Pub/Sub 리스너 등록 완료 - 채널: {}", CHAT_CHANNEL);
        return container;
    }

    /**
     * 메시지 리스너 어댑터
     * - Redis에서 메시지 수신 시 RedisMessageSubscriber의 onMessage() 호출
     *
     * @param subscriber 실제 메시지 처리 로직을 담당하는 컴포넌트
     */
    @Bean
    public MessageListenerAdapter listenerAdapter(RedisMessageSubscriber subscriber) {
        // "onMessage" 메서드가 호출됨
        return new MessageListenerAdapter(subscriber, "onMessage");
    }
}
