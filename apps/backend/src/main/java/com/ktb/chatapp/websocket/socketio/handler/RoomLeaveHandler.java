package com.ktb.chatapp.websocket.socketio.handler;

import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.annotation.OnEvent;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.model.MessageType;
import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.websocket.socketio.RoomUserCache;
import com.ktb.chatapp.websocket.socketio.SocketUser;
import com.ktb.chatapp.websocket.socketio.UserRooms;
import com.ktb.chatapp.websocket.socketio.broadcast.BroadcastService;
import com.ktb.chatapp.websocket.socketio.pubsub.ChatBroadcastEvent;
import java.time.LocalDateTime;
import java.util.*;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.*;

/**
 * 방 퇴장 처리 핸들러
 * 채팅방 퇴장, 스트리밍 세션 종료, 참가자 목록 업데이트 담당
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class RoomLeaveHandler {

    private final MessageRepository messageRepository;
    private final UserRooms userRooms;
    private final BroadcastService broadcastService;
    private final RoomUserCache roomUserCache;

    @OnEvent(LEAVE_ROOM)
    public void handleLeaveRoom(SocketIOClient client, String roomId) {
        try {
            SocketUser socketUser = client.get("user");
            if (socketUser == null) {
                client.sendEvent(ERROR, Map.of("message", "Unauthorized"));
                return;
            }

            String userId = socketUser.id();
            String userName = socketUser.name();

            if (!userRooms.isInRoom(userId, roomId)) {
                return;
            }

            // 1️⃣ 메모리 상태 정리
            userRooms.remove(userId, roomId);
            roomUserCache.removeUser(roomId, userId);
            client.leaveRoom(roomId);

            log.info("[LEAVE] userId={} roomId={}", userId, roomId);

            // 2️⃣ system message (선택)
            sendSystemMessage(roomId, userName + "님이 퇴장하였습니다.");

            // 3️⃣ diff 이벤트만 브로드캐스트
            broadcastService.broadcastToRoom(
                    ChatBroadcastEvent.TYPE_USER_LEFT,
                    roomId,
                    USER_LEFT,
                    Map.of("userId", userId)
            );

        } catch (Exception e) {
            log.error("Error handling leaveRoom", e);
            client.sendEvent(ERROR, Map.of("message", "채팅방 퇴장 중 오류"));
        }
    }

    private void sendSystemMessage(String roomId, String content) {
        try {
            Message systemMessage = new Message();
            systemMessage.setRoomId(roomId);
            systemMessage.setContent(content);
            systemMessage.setType(MessageType.system);
            systemMessage.setTimestamp(LocalDateTime.now());

            Message saved = messageRepository.save(systemMessage);

            broadcastService.broadcastToRoom(
                    ChatBroadcastEvent.TYPE_SYSTEM_MESSAGE,
                    roomId,
                    MESSAGE,
                    Map.of(
                            "type", "system",
                            "content", saved.getContent(),
                            "timestamp", saved.getTimestamp()
                    )
            );
        } catch (Exception e) {
            log.warn("Failed to send system message", e);
        }
    }
}
