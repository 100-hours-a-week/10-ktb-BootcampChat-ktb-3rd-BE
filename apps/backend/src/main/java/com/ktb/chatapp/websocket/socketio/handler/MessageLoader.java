package com.ktb.chatapp.websocket.socketio.handler;

import com.ktb.chatapp.dto.FetchMessagesRequest;
import com.ktb.chatapp.dto.FetchMessagesResponse;
import com.ktb.chatapp.dto.MessageResponse;
import com.ktb.chatapp.dto.MessagesReadResponse;
import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.MessageRepository;
import com.ktb.chatapp.repository.RoomRepository;
import com.ktb.chatapp.repository.UserRepository;
import com.ktb.chatapp.service.MessageReadStatusService;
import com.ktb.chatapp.service.command.MessageReadCommandService;
import com.ktb.chatapp.websocket.socketio.broadcast.BroadcastService;
import com.ktb.chatapp.websocket.socketio.pubsub.ChatBroadcastEvent;
import jakarta.annotation.Nullable;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import static com.ktb.chatapp.websocket.socketio.SocketIOEvents.MESSAGES_READ;
import static java.util.Collections.emptyList;

@Slf4j
@Component
@RequiredArgsConstructor
public class MessageLoader {

    private final MessageRepository messageRepository;
    private final UserRepository userRepository;
    private final MessageResponseMapper messageResponseMapper;

    private static final int BATCH_SIZE = 30;

    public FetchMessagesResponse loadMessages(FetchMessagesRequest data, String userId) {
        try {
            return loadMessagesInternal(
                    data.roomId(),
                    data.limit(BATCH_SIZE),
                    data.before(LocalDateTime.now())
            );
        } catch (Exception e) {
            log.error("Error loading messages for room {}", data.roomId(), e);
            return FetchMessagesResponse.builder()
                    .messages(List.of())
                    .hasMore(false)
                    .build();
        }
    }

    private FetchMessagesResponse loadMessagesInternal(
            String roomId,
            int limit,
            LocalDateTime before
    ) {
        Pageable pageable =
                PageRequest.of(0, limit, Sort.by("timestamp").descending());

        Page<Message> page =
                messageRepository.findByRoomIdAndIsDeletedAndTimestampBefore(
                        roomId, false, before, pageable
                );

        List<Message> sorted =
                page.getContent().reversed();

        // sender bulk fetch (N+1 제거)
        Set<String> senderIds = sorted.stream()
                .map(Message::getSenderId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<String, User> userMap =
                userRepository.findAllById(senderIds).stream()
                        .collect(Collectors.toMap(User::getId, u -> u));

        List<MessageResponse> responses =
                sorted.stream()
                        .map(m -> messageResponseMapper.mapToMessageResponse(
                                m,
                                userMap.get(m.getSenderId())
                        ))
                        .toList();

        return FetchMessagesResponse.builder()
                .messages(responses)
                .hasMore(page.hasNext())
                .build();
    }
}
