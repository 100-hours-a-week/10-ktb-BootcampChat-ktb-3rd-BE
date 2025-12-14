package com.ktb.chatapp.repository;

import com.ktb.chatapp.model.Message;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.Aggregation;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface MessageRepository extends MongoRepository<Message, String> {

    /**
     * 단일 roomId에 대해 최근 메시지 수를 카운트
     */
    @Query(value = "{ 'roomId': ?0, 'isDeleted': false, 'timestamp': { $gte: ?1 } }", count = true)
    long countRecentMessagesByRoomId(String roomId, LocalDateTime since);


    /**
     * 여러 roomId를 한 번에 카운트하는 Aggregation
     * → 방 목록 조회 최적화에 사용됨
     */
    @Aggregation(pipeline = {
            "{ '$match': { 'roomId': { '$in': ?0 }, 'isDeleted': false, 'timestamp': { '$gte': ?1 } } }",
            "{ '$group': { '_id': '$roomId', 'count': { '$sum': 1 } } }",
            "{ '$project': { '_id': 0, 'roomId': '$_id', 'count': 1 } }"
    })
    List<MessageCount> countRecentMessagesByRoomIds(List<String> roomIds, LocalDateTime since);

    Page<Message> findByRoomIdAndIsDeletedAndTimestampBefore(String roomId, Boolean isDeleted, LocalDateTime timestamp, Pageable pageable);

    /**
     * fileId로 메시지 조회 (파일 권한 검증용)
     */
    Optional<Message> findByFileId(String fileId);

    /**
     * Aggregation 결과를 담기 위한 Projection Interface
     */
    interface MessageCount {
        String getRoomId();
        long getCount();
    }

}
