package com.ktb.chatapp.service.session;


import com.ktb.chatapp.service.SessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class SessionBatchUpdater {

    private final SessionStore sessionStore;
    private final SessionService sessionService;

    @Scheduled(fixedRate = 60000) // 1분마다 실행
    public void flushLastActivityBatch() {

        Map<String, Long> copy = new HashMap<>(sessionService.getLastActivityCache());
        sessionService.clearLastActivityCache();

        if (copy.isEmpty()) return;

        log.info("Flushing {} session updates", copy.size());

        // MongoDB bulk update
        for (Map.Entry<String, Long> entry : copy.entrySet()) {
            String sessionId = entry.getKey();
            Long lastActivity = entry.getValue();

            sessionStore.updateLastActivityBatch(sessionId, lastActivity);
        }
    }
}