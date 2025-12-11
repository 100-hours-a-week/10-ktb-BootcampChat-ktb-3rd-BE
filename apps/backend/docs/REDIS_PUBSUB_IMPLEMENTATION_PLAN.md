# Redis Pub/Sub 기반 멀티 인스턴스 실시간 채팅 구현 계획서

## 목차
1. [현재 상태 분석](#1-현재-상태-분석)
2. [목표 아키텍처](#2-목표-아키텍처)
3. [구현 단계](#3-구현-단계)
4. [검증 체크리스트](#4-검증-체크리스트)
5. [롤백 계획](#5-롤백-계획)

---

## 1. 현재 상태 분석

### 1.1 기존 아키텍처 (단일 인스턴스)

```
┌─────────────────────────────────────────────────────────┐
│                    단일 서버 인스턴스                      │
│  ┌─────────────────────────────────────────────────┐   │
│  │           Socket.IO Server (Port 5002)           │   │
│  │                                                  │   │
│  │  ChatMessageHandler ──┐                         │   │
│  │  RoomJoinHandler ─────┼─→ socketIOServer        │   │
│  │  RoomLeaveHandler ────┤   .getRoomOperations()  │   │
│  │  MessageReactionHandler   .sendEvent()          │   │
│  │  MessageReadHandler ──┘                         │   │
│  │  SocketIOEventListener                          │   │
│  └─────────────────────────────────────────────────┘   │
│                          │                              │
│                          ▼                              │
│  ┌──────────────────┐  ┌──────────────────┐           │
│  │ LocalChatDataStore│  │    MongoDB       │           │
│  │   (인메모리)       │  │  (영구 저장)     │           │
│  └──────────────────┘  └──────────────────┘           │
└─────────────────────────────────────────────────────────┘
```

**문제점:**
- `LocalChatDataStore`: 인메모리 HashMap → 서버 간 상태 공유 불가
- 직접 `socketIOServer.sendEvent()` 호출 → 현재 서버 클라이언트에게만 전달
- `RedisMessagePublisher` 구현되어 있으나 **실제 호출하는 코드 없음**

### 1.2 영향받는 파일 목록

| 파일 | 변경 유형 | 설명 |
|-----|---------|------|
| `SocketIOConfig.java` | 수정 | ChatDataStore Bean 조건부 생성 변경 |
| `ChatMessageHandler.java` | 수정 | Redis Pub/Sub 연동 |
| `RoomJoinHandler.java` | 수정 | Redis Pub/Sub 연동 |
| `RoomLeaveHandler.java` | 수정 | Redis Pub/Sub 연동 |
| `MessageReactionHandler.java` | 수정 | Redis Pub/Sub 연동 |
| `MessageReadHandler.java` | 수정 | Redis Pub/Sub 연동 |
| `SocketIOEventListener.java` | 수정 | Redis Pub/Sub 연동 |
| `RedisChatDataStore.java` | 수정 | 이미 구현됨, Primary Bean으로 설정 |
| `application.properties` | 수정 | 설정 추가 |

### 1.3 기존 인터페이스 유지 항목

- `ChatDataStore` 인터페이스 → **변경 없음**
- `ChatBroadcastEvent` DTO → **변경 없음**
- `RedisMessagePublisher.publish()` 메서드 시그니처 → **변경 없음**
- `RedisMessageSubscriber.onMessage()` 메서드 시그니처 → **변경 없음**
- 모든 Socket.IO 이벤트 이름 → **변경 없음**
- 클라이언트 API → **변경 없음**

---

## 2. 목표 아키텍처

### 2.1 멀티 인스턴스 아키텍처 (10대 서버)

```
┌─────────────┐  ┌─────────────┐       ┌─────────────┐
│   서버 1     │  │   서버 2     │  ...  │   서버 10    │
│ Socket.IO   │  │ Socket.IO   │       │ Socket.IO   │
│ (유저 A,B)  │  │ (유저 C,D)  │       │ (유저 Y,Z)  │
└──────┬──────┘  └──────┬──────┘       └──────┬──────┘
       │                │                      │
       │   PUBLISH      │   SUBSCRIBE          │
       ▼                ▼                      ▼
┌─────────────────────────────────────────────────────────┐
│                    Redis Pub/Sub                         │
│              Channel: "chat:messages"                    │
│                                                          │
│  ┌─────────────────────────────────────────────────┐    │
│  │ ChatBroadcastEvent {                             │    │
│  │   eventType: "MESSAGE",                          │    │
│  │   roomId: "room123",                             │    │
│  │   socketEvent: "message",                        │    │
│  │   payload: { ... }                               │    │
│  │ }                                                │    │
│  └─────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────┘
       │                │                      │
       ▼                ▼                      ▼
┌─────────────────────────────────────────────────────────┐
│                    Redis Data Store                      │
│                                                          │
│  chat:data:userroom:roomids:userA → ["room1", "room2"]  │
│  chat:data:userroom:roomids:userB → ["room1"]           │
│  ...                                                     │
└─────────────────────────────────────────────────────────┘
       │
       ▼
┌─────────────────────────────────────────────────────────┐
│                      MongoDB                             │
│  (메시지 영구 저장 - 변경 없음)                           │
└─────────────────────────────────────────────────────────┘
```

### 2.2 메시지 흐름 (변경 후)

```
유저A (서버1) → "chatMessage" 이벤트 전송
                    │
                    ▼
         ┌─────────────────────┐
         │  ChatMessageHandler  │
         │  1. 검증/저장 (기존)  │
         │  2. MongoDB 저장     │
         └─────────────────────┘
                    │
                    ▼
         ┌─────────────────────┐
         │ RedisMessagePublisher│
         │ .publish(event)      │  ← 새로 추가되는 호출
         └─────────────────────┘
                    │
                    ▼ PUBLISH "chat:messages"
         ┌─────────────────────┐
         │    Redis Pub/Sub    │
         └─────────────────────┘
                    │
        ┌───────────┼───────────┐
        ▼           ▼           ▼
     서버1       서버2  ...   서버10
        │           │           │
        ▼           ▼           ▼
  ┌─────────────────────────────────┐
  │    RedisMessageSubscriber       │
  │    .onMessage(json)             │
  └─────────────────────────────────┘
        │           │           │
        ▼           ▼           ▼
  socketIOServer.getRoomOperations(roomId)
       .sendEvent("message", payload)
        │           │           │
        ▼           ▼           ▼
     유저A,B      유저C,D      유저Y,Z
     (방 참가자만 수신)
```

---

## 3. 구현 단계

### Phase 1: ChatDataStore를 Redis로 전환

**목표:** `LocalChatDataStore` → `RedisChatDataStore` 전환

#### Step 1.1: application.properties 설정 추가

```properties
# 파일: src/main/resources/application.properties
# 추가할 내용:

# Chat Data Store 설정 (local 또는 redis)
chat.datastore.type=${CHAT_DATASTORE_TYPE:redis}
```

#### Step 1.2: SocketIOConfig 수정

```java
// 파일: src/main/java/com/ktb/chatapp/config/SocketIOConfig.java
// 변경: chatDataStore() Bean 제거 또는 조건부 생성

// 기존 코드 (제거 또는 조건 추가):
@Bean
@ConditionalOnProperty(name = "chat.datastore.type", havingValue = "local")
public ChatDataStore localChatDataStore() {
    return new LocalChatDataStore();
}
```

#### Step 1.3: RedisChatDataStore를 Primary Bean으로 설정

```java
// 파일: src/main/java/com/ktb/chatapp/websocket/socketio/RedisChatDataStore.java
// 변경: @Primary 또는 @ConditionalOnProperty 추가

@Slf4j
@Component
@ConditionalOnProperty(name = "chat.datastore.type", havingValue = "redis", matchIfMissing = true)
@RequiredArgsConstructor
public class RedisChatDataStore implements ChatDataStore {
    // 기존 코드 유지
}
```

#### Step 1.4: 검증

```bash
# 테스트 명령어
./mvnw test -Dtest=RedisChatDataStoreTest

# 수동 검증
1. 애플리케이션 시작
2. Redis CLI로 데이터 확인:
   redis-cli KEYS "chat:data:*"
3. 방 입장 후 키 생성 확인:
   redis-cli GET "chat:data:userroom:roomids:{userId}"
```

---

### Phase 2: 브로드캐스트 서비스 추상화

**목표:** 모든 핸들러에서 사용할 통합 브로드캐스트 서비스 생성

#### Step 2.1: BroadcastService 인터페이스 생성

```java
// 파일: src/main/java/com/ktb/chatapp/websocket/socketio/broadcast/BroadcastService.java

package com.ktb.chatapp.websocket.socketio.broadcast;

public interface BroadcastService {

    /**
     * 특정 Room에 이벤트 브로드캐스트
     */
    void broadcastToRoom(String roomId, String socketEvent, Object payload);

    /**
     * 특정 Room에 이벤트 브로드캐스트 (이벤트 타입 명시)
     */
    void broadcastToRoom(String eventType, String roomId, String socketEvent, Object payload);
}
```

#### Step 2.2: RedisBroadcastService 구현

```java
// 파일: src/main/java/com/ktb/chatapp/websocket/socketio/broadcast/RedisBroadcastService.java

package com.ktb.chatapp.websocket.socketio.broadcast;

import com.ktb.chatapp.websocket.socketio.pubsub.ChatBroadcastEvent;
import com.ktb.chatapp.websocket.socketio.pubsub.RedisMessagePublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@ConditionalOnProperty(name = "chat.broadcast.type", havingValue = "redis", matchIfMissing = true)
@RequiredArgsConstructor
public class RedisBroadcastService implements BroadcastService {

    private final RedisMessagePublisher redisMessagePublisher;

    @Override
    public void broadcastToRoom(String roomId, String socketEvent, Object payload) {
        broadcastToRoom(ChatBroadcastEvent.TYPE_MESSAGE, roomId, socketEvent, payload);
    }

    @Override
    public void broadcastToRoom(String eventType, String roomId, String socketEvent, Object payload) {
        ChatBroadcastEvent event = ChatBroadcastEvent.builder()
                .eventType(eventType)
                .roomId(roomId)
                .socketEvent(socketEvent)
                .payload(payload)
                .build();

        redisMessagePublisher.publish(event);

        log.debug("Broadcast to room via Redis - eventType: {}, room: {}, socketEvent: {}",
                eventType, roomId, socketEvent);
    }
}
```

#### Step 2.3: LocalBroadcastService 구현 (개발/테스트용)

```java
// 파일: src/main/java/com/ktb/chatapp/websocket/socketio/broadcast/LocalBroadcastService.java

package com.ktb.chatapp.websocket.socketio.broadcast;

import com.corundumstudio.socketio.SocketIOServer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@ConditionalOnProperty(name = "chat.broadcast.type", havingValue = "local")
@RequiredArgsConstructor
public class LocalBroadcastService implements BroadcastService {

    @Lazy
    private final SocketIOServer socketIOServer;

    @Override
    public void broadcastToRoom(String roomId, String socketEvent, Object payload) {
        socketIOServer.getRoomOperations(roomId).sendEvent(socketEvent, payload);
        log.debug("Broadcast to room (local) - room: {}, socketEvent: {}", roomId, socketEvent);
    }

    @Override
    public void broadcastToRoom(String eventType, String roomId, String socketEvent, Object payload) {
        broadcastToRoom(roomId, socketEvent, payload);
    }
}
```

#### Step 2.4: 검증

```bash
# 단위 테스트
./mvnw test -Dtest=BroadcastServiceTest

# 통합 테스트
./mvnw test -Dtest=RedisBroadcastServiceIntegrationTest
```

---

### Phase 3: 핸들러 수정 - ChatMessageHandler

**목표:** 가장 핵심인 채팅 메시지 핸들러부터 Redis Pub/Sub 연동

#### Step 3.1: ChatMessageHandler 수정

```java
// 파일: src/main/java/com/ktb/chatapp/websocket/socketio/handler/ChatMessageHandler.java
// 변경 내용:

// 1. BroadcastService 주입 추가
private final BroadcastService broadcastService;

// 2. 기존 직접 호출 코드 (162-165행):
// socketIOServer.getRoomOperations(roomId)
//     .sendEvent(MESSAGE, createMessageResponse(savedMessage, sender));

// 3. 변경 후:
broadcastService.broadcastToRoom(
    ChatBroadcastEvent.TYPE_MESSAGE,
    roomId,
    MESSAGE,
    createMessageResponse(savedMessage, sender)
);
```

#### Step 3.2: 검증 (중요!)

```bash
# 1. 기존 테스트 통과 확인
./mvnw test -Dtest=ChatMessageHandlerTest

# 2. 통합 테스트 (두 클라이언트 시뮬레이션)
# 테스트 시나리오:
# - 클라이언트1: 메시지 전송
# - 클라이언트2: 메시지 수신 확인
# - Redis CLI: SUBSCRIBE "chat:messages" 로 메시지 흐름 확인

# 3. Redis 메시지 확인
redis-cli
> SUBSCRIBE chat:messages
# 다른 터미널에서 메시지 전송 후 구독 메시지 확인
```

---

### Phase 4: 핸들러 수정 - RoomJoinHandler / RoomLeaveHandler

#### Step 4.1: RoomJoinHandler 수정

```java
// 파일: src/main/java/com/ktb/chatapp/websocket/socketio/handler/RoomJoinHandler.java
// 변경 내용:

// 1. BroadcastService 주입 추가
private final BroadcastService broadcastService;

// 2. 기존 코드 (127-132행):
// socketIOServer.getRoomOperations(roomId)
//     .sendEvent(MESSAGE, messageResponseMapper.mapToMessageResponse(joinMessage, null));
// socketIOServer.getRoomOperations(roomId)
//     .sendEvent(PARTICIPANTS_UPDATE, participants);

// 3. 변경 후:
broadcastService.broadcastToRoom(
    ChatBroadcastEvent.TYPE_SYSTEM_MESSAGE,
    roomId,
    MESSAGE,
    messageResponseMapper.mapToMessageResponse(joinMessage, null)
);

broadcastService.broadcastToRoom(
    ChatBroadcastEvent.TYPE_PARTICIPANTS_UPDATE,
    roomId,
    PARTICIPANTS_UPDATE,
    participants
);
```

#### Step 4.2: RoomLeaveHandler 수정

```java
// 파일: src/main/java/com/ktb/chatapp/websocket/socketio/handler/RoomLeaveHandler.java
// 변경 내용:

// 1. BroadcastService 주입 추가
private final BroadcastService broadcastService;

// 2. sendSystemMessage() 메서드 내 (109-110행):
// 변경 후:
broadcastService.broadcastToRoom(
    ChatBroadcastEvent.TYPE_SYSTEM_MESSAGE,
    roomId,
    MESSAGE,
    response
);

// 3. broadcastParticipantList() 메서드 내 (136-137행):
// 변경 후:
broadcastService.broadcastToRoom(
    ChatBroadcastEvent.TYPE_PARTICIPANTS_UPDATE,
    roomId,
    PARTICIPANTS_UPDATE,
    participantList
);

// 4. handleLeaveRoom() 내 USER_LEFT 이벤트 (81-85행):
// 변경 후:
broadcastService.broadcastToRoom(
    ChatBroadcastEvent.TYPE_USER_LEFT,
    roomId,
    USER_LEFT,
    Map.of("userId", userId, "userName", userName)
);
```

#### Step 4.3: 검증

```bash
# 1. 기존 테스트 통과 확인
./mvnw test -Dtest=RoomJoinHandlerTest,RoomLeaveHandlerTest

# 2. 수동 테스트
# 시나리오 1: 유저 입장
# - 클라이언트1: 방 생성/입장
# - 클라이언트2: 같은 방 입장
# - 클라이언트1: "유저2님이 입장하였습니다" 메시지 수신 확인
# - 클라이언트1: 참가자 목록 업데이트 수신 확인

# 시나리오 2: 유저 퇴장
# - 클라이언트2: 방 퇴장
# - 클라이언트1: "유저2님이 퇴장하였습니다" 메시지 수신 확인
```

---

### Phase 5: 핸들러 수정 - MessageReactionHandler / MessageReadHandler

#### Step 5.1: MessageReactionHandler 수정

```java
// 파일: src/main/java/com/ktb/chatapp/websocket/socketio/handler/MessageReactionHandler.java
// 변경 내용:

// 1. BroadcastService 주입 추가
private final BroadcastService broadcastService;

// 2. 기존 코드 (66-67행):
// socketIOServer.getRoomOperations(message.getRoomId())
//     .sendEvent(MESSAGE_REACTION_UPDATE, response);

// 3. 변경 후:
broadcastService.broadcastToRoom(
    ChatBroadcastEvent.TYPE_REACTION_UPDATE,
    message.getRoomId(),
    MESSAGE_REACTION_UPDATE,
    response
);
```

#### Step 5.2: MessageReadHandler 수정

```java
// 파일: src/main/java/com/ktb/chatapp/websocket/socketio/handler/MessageReadHandler.java
// 변경 내용:

// 1. BroadcastService 주입 추가
private final BroadcastService broadcastService;

// 2. 기존 코드 (78-79행):
// socketIOServer.getRoomOperations(roomId)
//     .sendEvent(MESSAGES_READ, response);

// 3. 변경 후:
broadcastService.broadcastToRoom(
    ChatBroadcastEvent.TYPE_MESSAGES_READ,
    roomId,
    MESSAGES_READ,
    response
);
```

#### Step 5.3: 검증

```bash
# 리액션 테스트
# 1. 클라이언트1: 메시지 전송
# 2. 클라이언트2: 해당 메시지에 리액션 추가
# 3. 클라이언트1: 리액션 업데이트 수신 확인

# 읽음 상태 테스트
# 1. 클라이언트1: 메시지 전송
# 2. 클라이언트2: 메시지 읽음 처리
# 3. 클라이언트1: 읽음 상태 업데이트 수신 확인
```

---

### Phase 6: SocketIOEventListener 수정 (AI 이벤트)

#### Step 6.1: SocketIOEventListener 수정

```java
// 파일: src/main/java/com/ktb/chatapp/websocket/socketio/SocketIOEventListener.java
// 변경 내용:

// 1. BroadcastService 주입 추가
private final BroadcastService broadcastService;

// 2. handleRoomCreatedEvent (39행):
broadcastService.broadcastToRoom(
    ChatBroadcastEvent.TYPE_ROOM_CREATED,
    "room-list",
    ROOM_CREATED,
    event.getRoomResponse()
);

// 3. handleRoomUpdatedEvent (49행):
broadcastService.broadcastToRoom(
    ChatBroadcastEvent.TYPE_ROOM_UPDATED,
    event.getRoomId(),
    ROOM_UPDATE,
    event.getRoomResponse()
);

// 4. handleAiMessageStartEvent (64-65행):
broadcastService.broadcastToRoom(
    ChatBroadcastEvent.TYPE_AI_MESSAGE_START,
    event.getRoomId(),
    AI_MESSAGE_START,
    data
);

// 5. handleAiMessageChunkEvent (82-83행):
broadcastService.broadcastToRoom(
    ChatBroadcastEvent.TYPE_AI_MESSAGE_CHUNK,
    event.getRoomId(),
    AI_MESSAGE_CHUNK,
    data
);

// 6. handleAiMessageCompleteEvent (99-100행):
broadcastService.broadcastToRoom(
    ChatBroadcastEvent.TYPE_AI_MESSAGE_COMPLETE,
    event.getRoomId(),
    AI_MESSAGE_COMPLETE,
    data
);

// 7. handleAiMessageErrorEvent (116-117행):
broadcastService.broadcastToRoom(
    ChatBroadcastEvent.TYPE_AI_MESSAGE_ERROR,
    event.getRoomId(),
    AI_MESSAGE_ERROR,
    data
);

// 8. handleSessionEndedEvent (25-29행):
// 참고: session_ended는 특정 유저에게만 전송하므로
// "user:{userId}" 형태의 개인 room 사용 - 기존 방식 유지 또는 별도 처리
```

#### Step 6.2: 검증

```bash
# AI 스트리밍 테스트
# 1. 클라이언트1: @gpt 안녕 메시지 전송
# 2. 클라이언트1: AI_MESSAGE_START 수신 확인
# 3. 클라이언트1: AI_MESSAGE_CHUNK 수신 확인 (여러 번)
# 4. 클라이언트1: AI_MESSAGE_COMPLETE 수신 확인

# 다른 서버에 연결된 클라이언트도 AI 응답 수신 확인 (멀티 인스턴스 테스트)
```

---

### Phase 7: 전체 통합 테스트

#### Step 7.1: Docker Compose로 멀티 인스턴스 테스트 환경 구성

```yaml
# 파일: docker-compose.test.yaml

version: '3.8'
services:
  redis:
    image: redis:7.2
    ports:
      - "6379:6379"

  mongodb:
    image: mongo:8
    ports:
      - "27017:27017"

  chat-server-1:
    build: .
    ports:
      - "5001:5001"
      - "5002:5002"
    environment:
      - REDIS_HOST=redis
      - REDIS_PORT=6379
      - MONGODB_URI=mongodb://mongodb:27017/chatapp
      - CHAT_DATASTORE_TYPE=redis
      - CHAT_BROADCAST_TYPE=redis
      - HOSTNAME=server-1
    depends_on:
      - redis
      - mongodb

  chat-server-2:
    build: .
    ports:
      - "5003:5001"
      - "5004:5002"
    environment:
      - REDIS_HOST=redis
      - REDIS_PORT=6379
      - MONGODB_URI=mongodb://mongodb:27017/chatapp
      - CHAT_DATASTORE_TYPE=redis
      - CHAT_BROADCAST_TYPE=redis
      - HOSTNAME=server-2
    depends_on:
      - redis
      - mongodb
```

#### Step 7.2: 테스트 시나리오

```
[테스트 1: 기본 메시지 전달]
1. 클라이언트A → 서버1에 연결 → room1 입장
2. 클라이언트B → 서버2에 연결 → room1 입장
3. 클라이언트A: "안녕하세요" 메시지 전송
4. 검증: 클라이언트B가 메시지 수신

[테스트 2: 입장/퇴장 알림]
1. 클라이언트A → 서버1에 연결 → room1 입장
2. 클라이언트B → 서버2에 연결 → room1 입장
3. 검증: 클라이언트A가 "클라이언트B님이 입장하였습니다" 수신
4. 클라이언트B: room1 퇴장
5. 검증: 클라이언트A가 "클라이언트B님이 퇴장하였습니다" 수신

[테스트 3: 리액션 동기화]
1. 클라이언트A → 서버1에 연결 → room1 입장
2. 클라이언트B → 서버2에 연결 → room1 입장
3. 클라이언트A: 메시지 전송
4. 클라이언트B: 해당 메시지에 👍 리액션 추가
5. 검증: 클라이언트A가 리액션 업데이트 수신

[테스트 4: AI 응답 스트리밍]
1. 클라이언트A → 서버1에 연결 → room1 입장
2. 클라이언트B → 서버2에 연결 → room1 입장
3. 클라이언트A: "@gpt 안녕" 메시지 전송
4. 검증: 클라이언트A, 클라이언트B 모두 AI 스트리밍 응답 수신

[테스트 5: 서버 장애 복구]
1. 클라이언트A → 서버1에 연결 → room1 입장
2. 클라이언트B → 서버2에 연결 → room1 입장
3. 서버1 다운
4. 클라이언트A → 서버2에 재연결
5. 클라이언트A: room1 재입장
6. 검증: 이전 메시지 로드 + 실시간 메시지 수신 정상
```

---

## 4. 검증 체크리스트

### 4.1 Phase별 검증 항목

| Phase | 검증 항목 | 검증 방법 | 통과 기준 |
|-------|---------|----------|----------|
| 1 | Redis 데이터 저장 | `redis-cli KEYS "chat:data:*"` | 키 생성됨 |
| 1 | 기존 테스트 통과 | `./mvnw test` | 모든 테스트 통과 |
| 2 | BroadcastService 주입 | 애플리케이션 시작 | Bean 생성 로그 |
| 3 | 채팅 메시지 Redis 발행 | `redis-cli SUBSCRIBE chat:messages` | 메시지 수신 |
| 3 | 채팅 메시지 브로드캐스트 | 두 클라이언트 테스트 | 양쪽 수신 |
| 4 | 입장/퇴장 알림 | 두 클라이언트 테스트 | 양쪽 수신 |
| 5 | 리액션 동기화 | 두 클라이언트 테스트 | 양쪽 수신 |
| 6 | AI 스트리밍 | AI 멘션 테스트 | 청크/완료 수신 |
| 7 | 멀티 인스턴스 | Docker Compose 테스트 | 서버 간 동기화 |

### 4.2 성능 검증

```bash
# Redis Pub/Sub 지연시간 측정
redis-cli --latency

# 메시지 처리량 측정 (Artillery 사용)
artillery run load-test.yaml

# 예상 지표:
# - P50 지연시간: < 10ms
# - P99 지연시간: < 50ms
# - 초당 메시지 처리량: > 1000 msg/s (10인스턴스 기준)
```

---

## 5. 롤백 계획

### 5.1 설정 기반 롤백

모든 변경은 설정으로 제어 가능하도록 구현:

```properties
# 롤백 시 설정 변경
chat.datastore.type=local
chat.broadcast.type=local
```

### 5.2 단계별 롤백 절차

```
1. application.properties 수정
2. 애플리케이션 재시작
3. 기존 로직으로 동작 확인
```

### 5.3 롤백 트리거 조건

- Redis 연결 실패 지속 (5분 이상)
- 메시지 유실률 1% 초과
- P99 지연시간 500ms 초과

---

## 부록: 파일 변경 요약

### 신규 생성 파일

```
src/main/java/com/ktb/chatapp/websocket/socketio/broadcast/
├── BroadcastService.java          (인터페이스)
├── RedisBroadcastService.java     (Redis 구현체)
└── LocalBroadcastService.java     (로컬 구현체, 테스트용)
```

### 수정 파일

```
src/main/resources/application.properties
src/main/java/com/ktb/chatapp/config/SocketIOConfig.java
src/main/java/com/ktb/chatapp/websocket/socketio/RedisChatDataStore.java
src/main/java/com/ktb/chatapp/websocket/socketio/handler/ChatMessageHandler.java
src/main/java/com/ktb/chatapp/websocket/socketio/handler/RoomJoinHandler.java
src/main/java/com/ktb/chatapp/websocket/socketio/handler/RoomLeaveHandler.java
src/main/java/com/ktb/chatapp/websocket/socketio/handler/MessageReactionHandler.java
src/main/java/com/ktb/chatapp/websocket/socketio/handler/MessageReadHandler.java
src/main/java/com/ktb/chatapp/websocket/socketio/SocketIOEventListener.java
```

### 변경 없는 파일 (기존 구현 활용)

```
src/main/java/com/ktb/chatapp/config/RedisPubSubConfig.java
src/main/java/com/ktb/chatapp/websocket/socketio/pubsub/RedisMessagePublisher.java
src/main/java/com/ktb/chatapp/websocket/socketio/pubsub/RedisMessageSubscriber.java
src/main/java/com/ktb/chatapp/websocket/socketio/pubsub/ChatBroadcastEvent.java
src/main/java/com/ktb/chatapp/websocket/socketio/ChatDataStore.java (인터페이스)
```
