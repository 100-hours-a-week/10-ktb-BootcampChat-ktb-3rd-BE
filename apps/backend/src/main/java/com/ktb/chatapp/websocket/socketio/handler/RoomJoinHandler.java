package com.ktb.chatapp.websocket.socketio.handler;

import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.annotation.OnEvent;
import com.ktb.chatapp.dto.FetchMessagesRequest;
import com.ktb.chatapp.dto.FetchMessagesResponse;
import com.ktb.chatapp.dto.JoinRoomSuccessResponse;
import com.ktb.chatapp.dto.UserResponse;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.model.MessageType;
import com.ktb.chatapp.model.Room;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.repository.RoomRepository;
import com.ktb.chatapp.repository.UserRepository;
import com.ktb.chatapp.websocket.socketio.SocketUser;
import com.ktb.chatapp.websocket.socketio.UserRooms;
import com.ktb.chatapp.websocket.socketio.broadcast.BroadcastService;
import com.ktb.chatapp.websocket.socketio.pubsub.ChatBroadcastEvent;
import java.time.LocalDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.*;

/**
 * 방 입장 처리 핸들러
 * 채팅방 입장, 참가자 관리, 초기 메시지 로드 담당
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class RoomJoinHandler {

    private final SocketIOServer socketIOServer;
    private final MessageRepository messageRepository;
    private final RoomRepository roomRepository;
    private final UserRepository userRepository;
    private final UserRooms userRooms;
    private final MessageLoader messageLoader;
    private final MessageResponseMapper messageResponseMapper;
    private final RoomLeaveHandler roomLeaveHandler;
    private final BroadcastService broadcastService;

    @Value("${loadtest.enabled:false}")
    private boolean loadTestMode;


    @OnEvent(JOIN_ROOM)
    public void handleJoinRoom(SocketIOClient client, String roomId) {
        try {
            SocketUser socketUser = client.get("user");
            if (socketUser == null) {
                client.sendEvent(JOIN_ROOM_ERROR, Map.of("message", "Unauthorized"));
                return;
            }

            String userId = socketUser.id();

            Room room = roomRepository.findById(roomId).orElse(null);
            if (room == null) {
                client.sendEvent(JOIN_ROOM_ERROR, Map.of("message", "채팅방을 찾을 수 없습니다."));
                return;
            }

            boolean firstJoin = !userRooms.isInRoom(userId, roomId);

            if (firstJoin) {
                roomRepository.addParticipant(roomId, userId);
                userRooms.add(userId, roomId);
            }

            client.joinRoom(roomId);
            client.set("currentRoomId", roomId);

            // 1️⃣ 본인에게만 전체 participants 전달
            List<Map<String, Object>> participants = buildParticipantsPayload(room);
            User me = userRepository.findById(userId).orElse(null);
            client.sendEvent(JOIN_ROOM_SUCCESS, Map.of(
                    "roomId", roomId,
                    "joined", true,
                    "participants", participants,
                    "me", me == null ? null : Map.of("_id", me.getId(), "name", me.getName())
            ));

            // 2️⃣ 다른 사람들에게는 diff 이벤트만
            if (firstJoin) {
                UserResponse joinedUser = UserResponse.builder()
                        .id(userId)
                        .name(socketUser.name())
                        .build();

                broadcastService.broadcastToRoom(
                        ChatBroadcastEvent.TYPE_PARTICIPANTS_UPDATE,
                        roomId,
                        USER_JOINED,
                        Map.of("user", joinedUser)
                );
            }

        } catch (Exception e) {
            log.error("Error handling joinRoom", e);
            client.sendEvent(JOIN_ROOM_ERROR, Map.of("message", "채팅방 입장 실패"));
        }
    }

    private List<Map<String, Object>> buildParticipantsPayload(Room room) {
        // room.getParticipants()가 userId 리스트/셋이라고 가정
        // (필드/메서드명이 다르면 너 Room 모델에 맞게 바꿔줘)
        Collection<String> participantIds = room.getParticipantIds();

        if (participantIds == null || participantIds.isEmpty()) {
            return List.of(); // ✅ 항상 배열
        }

        // userRepo 조회 (너 repo에 맞춰 findAllById 사용)
        List<User> users = userRepository.findAllById(participantIds);

        // 프론트는 participant._id 를 사용하므로 "_id" 필수
        return users.stream()
                .map(u -> Map.<String, Object>of(
                        "_id", u.getId(),
                        "name", u.getName()
                        // 필요하면 추가:
                        // ,"profileImage", u.getProfileImage()
                ))
                .toList();
    }


    private SocketUser getUser(SocketIOClient client) {
        return client.get("user");
    }

    private String getUserId(SocketIOClient client) {
        SocketUser user = getUser(client);
        return user != null ? user.id() : null;
    }

    private String getUserName(SocketIOClient client) {
        SocketUser user = getUser(client);
        return user != null ? user.name() : null;
    }
}
