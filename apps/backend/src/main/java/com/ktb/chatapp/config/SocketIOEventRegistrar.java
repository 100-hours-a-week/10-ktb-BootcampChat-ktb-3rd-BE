package com.ktb.chatapp.config;

import com.corundumstudio.socketio.SocketIOServer;
import com.ktb.chatapp.websocket.socketio.handler.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Socket.IO 이벤트 핸들러 등록자.
 *
 * [왜 이 클래스가 필요한가?]
 *
 * 기존 방식 (SpringAnnotationScanner):
 * ┌─────────────────────────────────────────────────────────────┐
 * │ 1. Spring이 Bean 생성 시작                                   │
 * │ 2. ChatMessageHandler 생성 시도                              │
 * │ 3. SpringAnnotationScanner가 @OnEvent 발견                   │
 * │ 4. socketIOServer.addListeners() 호출                        │
 * │ 5. 💥 socketIOServer가 아직 생성 중 → 순환 참조!              │
 * └─────────────────────────────────────────────────────────────┘
 *
 * 새로운 방식 (SocketIOEventRegistrar):
 * ┌─────────────────────────────────────────────────────────────┐
 * │ 1. Spring이 모든 Bean 생성 완료                              │
 * │ 2. ApplicationReadyEvent 발생                                │
 * │ 3. SocketIOEventRegistrar가 이벤트 수신                      │
 * │ 4. socketIOServer.addListeners() 호출                        │
 * │ 5. ✅ 모든 Bean이 준비된 상태 → 순환 참조 없음!               │
 * │ 6. socketIOServer.start() 호출                               │
 * └─────────────────────────────────────────────────────────────┘
 *
 * [동작 시점]
 * - ApplicationReadyEvent: 애플리케이션이 완전히 시작된 후 발생
 * - 이 시점에는 모든 Bean이 생성 완료되어 순환 참조 위험 없음
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class SocketIOEventRegistrar {

    private final SocketIOServer socketIOServer;

    // 모든 @OnEvent 핸들러들
    private final ChatMessageHandler chatMessageHandler;
    private final RoomJoinHandler roomJoinHandler;
    private final RoomLeaveHandler roomLeaveHandler;
    private final MessageFetchHandler messageFetchHandler;
    private final MessageReactionHandler messageReactionHandler;
    private final MessageReadHandler messageReadHandler;

    /**
     * 애플리케이션 시작 완료 후 Socket.IO 이벤트 핸들러 등록 및 서버 시작.
     *
     * [실행 순서]
     * 1. 모든 핸들러를 SocketIOServer에 등록
     * 2. SocketIOServer 시작
     *
     * [왜 여기서 서버를 시작하나?]
     * - 기존: @Bean(initMethod = "start")로 Bean 생성 시 바로 시작
     * - 문제: 핸들러가 등록되기 전에 서버가 시작될 수 있음
     * - 해결: 핸들러 등록 완료 후 서버 시작
     */
    @EventListener(ApplicationReadyEvent.class)
    public void registerEventHandlers() {
        log.info("Socket.IO 이벤트 핸들러 등록 시작...");

        // @OnEvent 어노테이션이 붙은 핸들러들을 SocketIOServer에 등록
        socketIOServer.addListeners(chatMessageHandler);
        socketIOServer.addListeners(roomJoinHandler);
        socketIOServer.addListeners(roomLeaveHandler);
        socketIOServer.addListeners(messageFetchHandler);
        socketIOServer.addListeners(messageReactionHandler);
        socketIOServer.addListeners(messageReadHandler);

        log.info("Socket.IO 이벤트 핸들러 등록 완료 - 총 6개 핸들러");

        // 핸들러 등록 완료 후 서버 시작
        socketIOServer.start();
        log.info("Socket.IO 서버 시작 완료 - port: {}", socketIOServer.getConfiguration().getPort());
    }
}
