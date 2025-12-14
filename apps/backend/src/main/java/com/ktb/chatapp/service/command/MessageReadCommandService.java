package com.ktb.chatapp.service.command;

import com.ktb.chatapp.model.ReadBatch;
import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.repository.RoomRepository;
import com.ktb.chatapp.service.MessageReadStatusService;
import com.ktb.chatapp.websocket.socketio.broadcast.BroadcastService;
import com.ktb.chatapp.websocket.socketio.pubsub.ChatBroadcastEvent;
import com.ktb.chatapp.dto.MessagesReadResponse;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.MESSAGES_READ;

@Slf4j
@Service
@RequiredArgsConstructor
public class MessageReadCommandService {

    private final MessageReadStatusService messageReadStatusService;
    private final BroadcastService broadcastService;
    private final MessageRepository messageRepository;
    private final RoomRepository roomRepository;

    // roomId:userId → batch
    private final ConcurrentMap<String, ReadBatch> buffer = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "message-read-batch-flusher");
                t.setDaemon(true);
                return t;
            });


    private static final int MAX_BATCH_SIZE = 200;
    private static final long FLUSH_INTERVAL_MS = 500;

    @PostConstruct
    void init() {
        scheduler.scheduleAtFixedRate(
                this::flushAll,
                FLUSH_INTERVAL_MS,
                FLUSH_INTERVAL_MS,
                TimeUnit.MILLISECONDS
        );
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdown();
    }

    /** 🔥 handler가 호출하는 진짜 entry */
    @Async("messageExecutor")
    public void processAsync(String roomId, String userId, List<String> messageIds) {

        String key = roomId + ":" + userId;

        ReadBatch batch = buffer.computeIfAbsent(
                key,
                k -> new ReadBatch(roomId, userId)
        );

        batch.add(messageIds);

        // 사이즈 초과 시 즉시 flush
        if (batch.size() >= MAX_BATCH_SIZE) {
            flush(key, batch);
        }
    }

    private void flushAll() {
        buffer.entrySet().forEach(e -> flush(e.getKey(), e.getValue()));
    }


    private void flush(String key, ReadBatch batch) {
        if (batch.size() == 0) return;
        if (!buffer.remove(key, batch)) return;

        List<String> messageIds = new ArrayList<>(batch.getMessageIds());

        // 1️⃣ readers bulk update
        messageReadStatusService.updateReadStatus(
                messageIds,
                batch.getUserId()
        );

        // 2️⃣ 🔥 all-read 판정 (여기가 빠져 있었음)
        int participantsToRead =
                roomRepository.countParticipants(batch.getRoomId()) - 1;

        List<String> allReadMessageIds =
                messageRepository.findAllReadMessages(
                        messageIds,
                        participantsToRead
                );

        if (!allReadMessageIds.isEmpty()) {

            // 3️⃣ 상태 고정
            messageRepository.markAllRead(allReadMessageIds);

            // 4️⃣ 🔥 ACK 브로드캐스트 (E2E 핵심)
            broadcastService.broadcastToRoom(
                    ChatBroadcastEvent.TYPE_MESSAGES_READ,
                    batch.getRoomId(),
                    MESSAGES_READ,
                    new MessagesReadResponse(
                            "ALL",
                            allReadMessageIds
                    )
            );
        }
    }
}