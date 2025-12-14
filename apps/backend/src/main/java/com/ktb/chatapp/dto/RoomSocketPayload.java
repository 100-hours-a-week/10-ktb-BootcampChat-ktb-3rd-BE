package com.ktb.chatapp.dto;

import java.util.List;
import java.util.Map;

public record RoomSocketPayload(
        String roomId,
        List<Map<String, Object>> participants
) {}