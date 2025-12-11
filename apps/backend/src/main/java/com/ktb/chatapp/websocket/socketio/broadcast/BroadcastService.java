package com.ktb.chatapp.websocket.socketio.broadcast;

/**
 * 채팅 이벤트 브로드캐스트 서비스 인터페이스.
 *
 * 단일 서버 환경에서는 직접 Socket.IO로 전송하고,
 * 멀티 서버 환경에서는 Redis Pub/Sub를 통해 모든 서버에 전파한다.
 */
public interface BroadcastService {

    /**
     * 특정 Room에 이벤트 브로드캐스트
     *
     * @param roomId      대상 채팅방 ID
     * @param socketEvent Socket.IO 이벤트 이름
     * @param payload     전송할 데이터
     */
    void broadcastToRoom(String roomId, String socketEvent, Object payload);

    /**
     * 특정 Room에 이벤트 브로드캐스트 (이벤트 타입 명시)
     *
     * @param eventType   이벤트 타입 (ChatBroadcastEvent.TYPE_*)
     * @param roomId      대상 채팅방 ID
     * @param socketEvent Socket.IO 이벤트 이름
     * @param payload     전송할 데이터
     */
    void broadcastToRoom(String eventType, String roomId, String socketEvent, Object payload);
}
