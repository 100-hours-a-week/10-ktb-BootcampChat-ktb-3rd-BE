package com.ktb.chatapp.websocket.socketio;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Redis 기반 ChatDataStore 구현체.
 *
 * [왜 Redis를 사용하는가?]
 * - 10대의 EC2 인스턴스가 로드밸런싱될 때, 각 서버의 메모리는 독립적
 * - 유저A가 서버1에, 유저B가 서버5에 연결되면 서로의 상태를 모름
 * - Redis는 모든 서버가 공유하는 "중앙 저장소" 역할
 *
 * [저장되는 데이터]
 * - 유저별 참여 중인 채팅방 목록 (userroom:roomids:{userId})
 * - 채팅 관련 임시 상태 데이터
 *
 * [TTL 설정 이유]
 * - 24시간 TTL: 연결이 끊어진 유저의 데이터가 영원히 남지 않도록
 * - 재연결 시 갱신됨
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "chat.datastore.type", havingValue = "redis", matchIfMissing = true)
@RequiredArgsConstructor
public class RedisChatDataStore implements ChatDataStore {

    /**
     * Redis 키 prefix - 다른 Redis 데이터와 구분하기 위함
     * 예: chat:data:userroom:roomids:user123
     */
    private static final String KEY_PREFIX = "chat:data:";

    /**
     * 데이터 만료 시간 (24시간)
     * - 유저가 연결 해제 후 재접속하지 않으면 24시간 후 자동 삭제
     * - 메모리 누수 방지
     */
    private static final long DEFAULT_TTL_HOURS = 24;

    /**
     * RedisTemplate - Spring이 제공하는 Redis 연산 도구
     * - String 타입으로 직렬화하여 저장 (JSON 형태)
     */
    private final RedisTemplate<String, String> redisTemplate;

    /**
     * ObjectMapper - Java 객체 <-> JSON 변환
     * - Set<String>, Map 등의 복잡한 객체도 JSON으로 저장
     */
    private final ObjectMapper objectMapper;

    /**
     * Redis에서 데이터 조회
     *
     * @param key  저장 키 (예: "userroom:roomids:user123")
     * @param type 반환받을 타입 (예: Set.class)
     * @return Optional로 감싼 결과 (없으면 empty)
     */
    @Override
    public <T> Optional<T> get(String key, Class<T> type) {
        try {
            String json = redisTemplate.opsForValue().get(buildKey(key));
            if (json == null || json.isBlank()) {
                return Optional.empty();
            }

            // Set 타입은 별도 처리 필요 (제네릭 타입 정보 유지)
            if (Set.class.isAssignableFrom(type)) {
                @SuppressWarnings("unchecked")
                T result = (T) objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(Set.class, String.class));
                return Optional.of(result);
            }

            return Optional.of(objectMapper.readValue(json, type));
        } catch (JsonProcessingException e) {
            log.error("Redis 데이터 역직렬화 실패 - key: {}", key, e);
            return Optional.empty();
        }
    }

    /**
     * Redis에 데이터 저장
     *
     * @param key   저장 키
     * @param value 저장할 객체 (JSON으로 변환되어 저장)
     *
     * [중요] TTL(만료시간) 설정
     * - 매 저장 시 24시간 TTL 갱신
     * - 활성 유저의 데이터는 계속 갱신되어 유지
     * - 비활성 유저의 데이터는 24시간 후 자동 삭제
     */
    @Override
    public void set(String key, Object value) {
        try {
            String json = objectMapper.writeValueAsString(value);
            redisTemplate.opsForValue().set(buildKey(key), json, DEFAULT_TTL_HOURS, TimeUnit.HOURS);
            log.debug("Redis 데이터 저장 - key: {}", buildKey(key));
        } catch (JsonProcessingException e) {
            log.error("Redis 데이터 직렬화 실패 - key: {}", key, e);
            throw new RuntimeException("채팅 데이터 저장 실패", e);
        }
    }

    /**
     * Redis에서 데이터 삭제
     * - 유저가 채팅방을 나갈 때 등에 사용
     */
    @Override
    public void delete(String key) {
        redisTemplate.delete(buildKey(key));
        log.debug("Redis 데이터 삭제 - key: {}", buildKey(key));
    }

    /**
     * 저장된 채팅 데이터 개수 조회 (모니터링/디버깅용)
     *
     * [주의] keys() 명령은 프로덕션에서 성능 이슈 가능
     * - Redis에 키가 많으면 블로킹 발생
     * - 모니터링 목적으로만 사용 권장
     */
    @Override
    public int size() {
        Set<String> keys = redisTemplate.keys(KEY_PREFIX + "*");
        return keys != null ? keys.size() : 0;
    }

    /**
     * 실제 Redis 키 생성
     * - prefix를 붙여서 다른 데이터와 충돌 방지
     * - 예: "userroom:roomids:user123" -> "chat:data:userroom:roomids:user123"
     */
    private String buildKey(String key) {
        return KEY_PREFIX + key;
    }
}
