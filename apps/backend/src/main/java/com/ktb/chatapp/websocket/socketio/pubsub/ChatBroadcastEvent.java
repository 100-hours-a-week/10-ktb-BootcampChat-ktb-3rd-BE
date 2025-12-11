package com.ktb.chatapp.websocket.socketio.pubsub;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Redis Pub/Sub를 통해 서버 간 전달되는 채팅 브로드캐스트 이벤트.
 *
 * [왜 이 DTO가 필요한가?]
 * - 서버1에서 발생한 이벤트를 서버2, 서버3, ...에 전달해야 함
 * - 각 서버는 이 DTO를 받아서 자신에게 연결된 클라이언트에게 Socket.IO로 전송
 *
 * [포함 정보]
 * - eventType: 어떤 종류의 이벤트인지 (MESSAGE, JOIN, LEAVE, AI_CHUNK 등)
 * - roomId: 어느 채팅방에 브로드캐스트할지
 * - socketEvent: Socket.IO 클라이언트에게 보낼 이벤트 이름
 * - payload: 실제 데이터 (메시지 내용, 유저 정보 등)
 *
 * [흐름 예시]
 * 1. 서버1에서 유저A가 메시지 전송
 * 2. 서버1이 ChatBroadcastEvent 생성 후 Redis에 PUBLISH
 * 3. 서버1~10이 모두 이 이벤트를 수신
 * 4. 각 서버는 roomId에 해당하는 Socket.IO Room에 socketEvent 발송
 * 5. 결과: 모든 서버에 연결된 해당 방 유저들이 메시지 수신
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatBroadcastEvent {

    /**
     * 이벤트 타입 - 어떤 종류의 브로드캐스트인지
     *
     * [타입 목록]
     * - MESSAGE: 일반 채팅 메시지
     * - SYSTEM_MESSAGE: 시스템 메시지 (입장/퇴장 알림)
     * - PARTICIPANTS_UPDATE: 참가자 목록 변경
     * - MESSAGES_READ: 읽음 상태 업데이트
     * - REACTION_UPDATE: 리액션 변경
     * - AI_MESSAGE_START: AI 응답 시작
     * - AI_MESSAGE_CHUNK: AI 응답 청크 (스트리밍)
     * - AI_MESSAGE_COMPLETE: AI 응답 완료
     * - AI_MESSAGE_ERROR: AI 응답 에러
     * - ROOM_CREATED: 새 채팅방 생성
     * - ROOM_UPDATED: 채팅방 정보 변경
     * - USER_LEFT: 유저 퇴장
     */
    private String eventType;

    /**
     * 대상 채팅방 ID
     * - 이 방에 연결된 클라이언트들에게만 브로드캐스트
     * - "room-list"인 경우 방 목록을 구독 중인 클라이언트들에게 전송
     */
    private String roomId;

    /**
     * Socket.IO 이벤트 이름
     * - 클라이언트가 수신할 이벤트 이름
     * - 예: "message", "participantsUpdate", "aiMessageChunk" 등
     */
    private String socketEvent;

    /**
     * 실제 전송할 데이터
     * - Map 형태로 다양한 데이터 구조 지원
     * - 클라이언트에게 그대로 전달됨
     *
     * [예시]
     * MESSAGE 타입:
     * {
     *   "id": "msg123",
     *   "content": "안녕하세요",
     *   "sender": { "id": "user1", "name": "홍길동" },
     *   "timestamp": 1702345678000
     * }
     *
     * PARTICIPANTS_UPDATE 타입:
     * [
     *   { "id": "user1", "name": "홍길동" },
     *   { "id": "user2", "name": "김철수" }
     * ]
     */
    private Object payload;

    /**
     * 이벤트 타입 상수들
     * - 타입 안전성을 위해 상수로 정의
     */
    public static final String TYPE_MESSAGE = "MESSAGE";
    public static final String TYPE_SYSTEM_MESSAGE = "SYSTEM_MESSAGE";
    public static final String TYPE_PARTICIPANTS_UPDATE = "PARTICIPANTS_UPDATE";
    public static final String TYPE_MESSAGES_READ = "MESSAGES_READ";
    public static final String TYPE_REACTION_UPDATE = "REACTION_UPDATE";
    public static final String TYPE_AI_MESSAGE_START = "AI_MESSAGE_START";
    public static final String TYPE_AI_MESSAGE_CHUNK = "AI_MESSAGE_CHUNK";
    public static final String TYPE_AI_MESSAGE_COMPLETE = "AI_MESSAGE_COMPLETE";
    public static final String TYPE_AI_MESSAGE_ERROR = "AI_MESSAGE_ERROR";
    public static final String TYPE_ROOM_CREATED = "ROOM_CREATED";
    public static final String TYPE_ROOM_UPDATED = "ROOM_UPDATED";
    public static final String TYPE_USER_LEFT = "USER_LEFT";
    public static final String TYPE_SESSION_ENDED = "SESSION_ENDED";
}
