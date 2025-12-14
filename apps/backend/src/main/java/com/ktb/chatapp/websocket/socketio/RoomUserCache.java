package com.ktb.chatapp.websocket.socketio;

import com.ktb.chatapp.dto.UserResponse;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RoomUserCache {

    // roomId -> (userId -> UserResponse)
    private final Map<String, Map<String, UserResponse>> roomUsers = new ConcurrentHashMap<>();

    public Map<String, UserResponse> getRoom(String roomId) {
        return roomUsers.computeIfAbsent(roomId, k -> new ConcurrentHashMap<>());
    }

    public void removeUser(String roomId, String userId) {
        Map<String, UserResponse> room = roomUsers.get(roomId);
        if (room == null) return;

        room.remove(userId);
        if (room.isEmpty()) {
            roomUsers.remove(roomId);
        }
    }
}
