package com.ktb.chatapp.config;

import com.corundumstudio.socketio.AuthTokenListener;
import com.corundumstudio.socketio.SocketConfig;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.namespace.Namespace;
import com.corundumstudio.socketio.protocol.JacksonJsonSupport;
import com.corundumstudio.socketio.store.MemoryStoreFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ktb.chatapp.websocket.socketio.ChatDataStore;
import com.ktb.chatapp.websocket.socketio.LocalChatDataStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
public class SocketIOConfig {

    @Value("${socketio.server.host:localhost}")
    private String host;

    @Value("${socketio.server.port:5002}")
    private Integer port;

    @Bean(destroyMethod = "stop")
    public SocketIOServer socketIOServer(AuthTokenListener authTokenListener) {
        com.corundumstudio.socketio.Configuration config = new com.corundumstudio.socketio.Configuration();
        int cores = Runtime.getRuntime().availableProcessors();

        config.setHostname(host);
        config.setPort(port);

        config.setBossThreads(1);
//        config.setWorkerThreads(8);
        config.setWorkerThreads(cores * 3);

        var socketConfig = new SocketConfig();
        socketConfig.setReuseAddress(true);
        socketConfig.setAcceptBackLog(1024);
        socketConfig.setTcpSendBufferSize(65536);
        socketConfig.setTcpReceiveBufferSize(65536);
        socketConfig.setTcpNoDelay(true);
        config.setSocketConfig(socketConfig);

        config.setOrigin("*");

        // Socket.IO settings
//        config.setPingTimeout(60000);
        config.setPingInterval(25000);
//        config.setUpgradeTimeout(10000);
        config.setUpgradeTimeout(20_000);
        config.setPingTimeout(90_000);

        config.setJsonSupport(new JacksonJsonSupport(new JavaTimeModule()));
        config.setStoreFactory(new MemoryStoreFactory()); // 단일노드 전용

        log.debug("Socket.IO server configured on {}:{} with {} boss threads and {} worker threads",
                host, port, config.getBossThreads(), config.getWorkerThreads());
        var socketIOServer = new SocketIOServer(config);

        socketIOServer.getNamespace(Namespace.DEFAULT_NAME).addAuthTokenListener(authTokenListener);

        return socketIOServer;
    }

    // SpringAnnotationScanner 제거됨
    // - SocketIOEventRegistrar가 ApplicationReadyEvent에서 명시적으로 핸들러 등록
    // - SpringAnnotationScanner와 중복 등록되어 메시지가 2번 처리되는 문제 해결

    // 인메모리 저장소, 단일 노드 환경에서만 사용 (chat.datastore.type=local 일 때만 활성화)
    @Bean
    @ConditionalOnProperty(name = "chat.datastore.type", havingValue = "local")
    public ChatDataStore localChatDataStore() {
        return new LocalChatDataStore();
    }
}
