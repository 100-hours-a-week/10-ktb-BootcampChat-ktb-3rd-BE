package com.ktb.chatapp.websocket.socketio.handler;

import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.annotation.OnEvent;
import com.ktb.chatapp.dto.MarkAsReadRequest;
import com.ktb.chatapp.dto.MessagesReadResponse;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.model.Room;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.repository.RoomRepository;
import com.ktb.chatapp.repository.UserRepository;
import com.ktb.chatapp.service.MessageReadStatusService;
import com.ktb.chatapp.service.cache.RoomCacheService;
import com.ktb.chatapp.service.command.MessageReadCommandService;
import com.ktb.chatapp.websocket.socketio.SocketUser;
import com.ktb.chatapp.websocket.socketio.broadcast.BroadcastService;
import com.ktb.chatapp.websocket.socketio.pubsub.ChatBroadcastEvent;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.*;

/**
 * 메시지 읽음 상태 처리 핸들러
 * 메시지 읽음 상태 업데이트 및 브로드캐스트 담당
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class MessageReadHandler {

    private final SocketIOServer socketIOServer;
    private final MessageReadStatusService messageReadStatusService;
    private final MessageRepository messageRepository;
    private final RoomRepository roomRepository;
    private final UserRepository userRepository;
    private final BroadcastService broadcastService;
    private final MessageReadCommandService messageReadCommandService;
    // 캐시 서비스 (MongoDB 호출 최소화)
    private final RoomCacheService roomCacheService;

    @Value("${loadtest.enabled:false}")
    private boolean loadTestMode;

    @OnEvent(MARK_MESSAGES_AS_READ)
    public void handleMarkAsRead(SocketIOClient client, MarkAsReadRequest data) {

        if (loadTestMode) return;

        String userId = getUserId(client);
        if (userId == null || data == null || data.getMessageIds().isEmpty()) return;

        String roomId = client.get("currentRoomId");
        if (roomId == null) return;

        // 1️⃣ DB 업데이트는 batch로 (부하 대응)
        messageReadCommandService.processAsync(
                roomId,
                userId,
                data.getMessageIds()
        );

        // 2️⃣ 🔥 참가자 수 확인 (캐시 사용으로 MongoDB 조회 최소화)
        int participantCount = roomCacheService.countParticipants(roomId);

        // 3️⃣ 🔥 2인 채팅이면 즉시 "모두 읽음" 브로드캐스트
        if (participantCount == 2) {
            broadcastService.broadcastToRoom(
                    ChatBroadcastEvent.TYPE_MESSAGES_READ,
                    roomId,
                    MESSAGES_READ,
                    new MessagesReadResponse(
                            userId,               // 읽은 사람
                            data.getMessageIds()  // 읽은 메시지
                    )
            );
        }
    }
//        try {
//            if (loadTestMode) {
//                return;
//            }
//            String userId = getUserId(client);
//            if (userId == null) {
//                client.sendEvent(ERROR, Map.of("message", "Unauthorized"));
//                return;
//            }
//
//            if (data == null || data.getMessageIds() == null || data.getMessageIds().isEmpty()) {
//                return;
//            }
//
//            String roomId = messageRepository.findById(data.getMessageIds().getFirst())
//                    .map(Message::getRoomId).orElse(null);
//
//            if (roomId == null || roomId.isBlank()) {
//                client.sendEvent(ERROR, Map.of("message", "Invalid room"));
//                return;
//            }
//
//            User user = userRepository.findById(userId).orElse(null);
//            if (user == null) {
//                client.sendEvent(ERROR, Map.of("message", "User not found"));
//                return;
//            }
//
//            Room room = roomRepository.findById(roomId).orElse(null);
//            if (room == null || !room.getParticipantIds().contains(userId)) {
//                client.sendEvent(ERROR, Map.of("message", "Room access denied"));
//                return;
//            }
//
//            messageReadStatusService.updateReadStatus(data.getMessageIds(), userId);
//
//            MessagesReadResponse response = new MessagesReadResponse(userId, data.getMessageIds());
//
//            // Redis Pub/Sub를 통해 읽음 상태 브로드캐스트
//            broadcastService.broadcastToRoom(
//                    ChatBroadcastEvent.TYPE_MESSAGES_READ,
//                    roomId,
//                    MESSAGES_READ,
//                    response
//            );
//
//        } catch (Exception e) {
//            log.error("Error handling markMessagesAsRead", e);
//            client.sendEvent(ERROR, Map.of(
//                    "message", "읽음 상태 업데이트 중 오류가 발생했습니다."
//            ));
//        }
//    }
//
    private String getUserId(SocketIOClient client) {
        var user = (SocketUser) client.get("user");
        return user.id();
    }
}
