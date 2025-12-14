package com.ktb.chatapp.model;

import lombok.Getter;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Getter
public class ReadBatch {

    private final String roomId;
    private final String userId;
    private final Set<String> messageIds = ConcurrentHashMap.newKeySet();

    public ReadBatch(String roomId, String userId) {
        this.roomId = roomId;
        this.userId = userId;
    }

    public void add(List<String> ids) {
        messageIds.addAll(ids);
    }

    public int size() {
        return messageIds.size();
    }

    public Set<String> getMessageIds() {  // ✅ flush 시 필요
        return messageIds;
    }

    public List<String> drain() {
        List<String> list = new ArrayList<>(messageIds);
        messageIds.clear();
        return list;
    }
}