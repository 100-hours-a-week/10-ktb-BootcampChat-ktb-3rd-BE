package com.ktb.chatapp.service;

import com.ktb.chatapp.model.Message;
import com.ktb.chatapp.repository.MessageRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * 메시지 읽음 상태 관리 서비스
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageReadStatusService {

    private final MessageRepository messageRepository;
    private final MongoTemplate mongoTemplate;

    public void updateReadStatus(List<String> messageIds, String userId) {
        if (messageIds == null || messageIds.isEmpty()) return;

        LocalDateTime now = LocalDateTime.now();

        Message.MessageReader reader =
                Message.MessageReader.builder()
                        .userId(userId)
                        .readAt(now)
                        .build();

        try {
            // 메시지 목록 전체에 대해 한 번에 updateMany 실행
            Query query = Query.query(Criteria.where("_id").in(messageIds));

            Update update = new Update()
                    .addToSet("readers").each(reader);

            mongoTemplate.updateMulti(query, update, Message.class);

            log.debug("Bulk read status updated for {} messages by user {}",
                    messageIds.size(), userId);

        } catch (Exception e) {
            log.error("Bulk read status update failed for user {}", userId, e);
        }
    }
}
