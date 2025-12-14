package com.ktb.chatapp.config;

import com.corundumstudio.socketio.AuthTokenListener;
import com.corundumstudio.socketio.SocketConfig;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.Transport;
import com.corundumstudio.socketio.namespace.Namespace;
import com.corundumstudio.socketio.protocol.JacksonJsonSupport;
import com.corundumstudio.socketio.store.MemoryStoreFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ktb.chatapp.service.session.HandshakeSessionCacheService;
import com.ktb.chatapp.websocket.socketio.ChatDataStore;
import com.ktb.chatapp.websocket.socketio.LocalChatDataStore;
import com.ktb.chatapp.websocket.socketio.handler.ConnectionLoginHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(name = "socketio.enabled", havingValue = "true", matchIfMissing = true)
public class SocketIOConfig {
    private final HandshakeSessionCacheService handshakeCache;
    private final ObjectProvider<ConnectionLoginHandler> connectionLoginHandlerProvider;

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

        // 튜닝필요
        config.setBossThreads(2);
        config.setWorkerThreads(cores * 4);

        config.setPingInterval(60000);
        config.setUpgradeTimeout(180000);
        config.setPingTimeout(180000);

        config.setMaxFramePayloadLength(1024 * 1024);
        config.setMaxHttpContentLength(1024 * 1024);
        config.setTransports(Transport.POLLING, Transport.WEBSOCKET);


        config.setAllowCustomRequests(true);
        config.setOrigin("*");

        // Socket.IO settings
        var socketConfig = new SocketConfig();
        socketConfig.setReuseAddress(true);
        socketConfig.setAcceptBackLog(32768);
        socketConfig.setTcpSendBufferSize(65536);
        socketConfig.setTcpReceiveBufferSize(65536);
        socketConfig.setTcpNoDelay(true);

        config.setSocketConfig(socketConfig);

        config.setJsonSupport(new JacksonJsonSupport(new JavaTimeModule()));
        config.setStoreFactory(new MemoryStoreFactory()); // 단일노드 전용

        // ✅ 서버는 여기서 단 한 번만 생성
        SocketIOServer server = new SocketIOServer(config);

        // ✅ Engine.IO 연결 성공
        server.addConnectListener(client -> {
            log.info("[CONNECT] clientId={}", client.getSessionId());
        });


        // ✅ 연결 종료
        server.addDisconnectListener(client -> {
            log.info("[DISCONNECT] clientId={} transport={}", client.getSessionId(), client.getTransport());
        });

        // ✅ Auth handshake
        server.getNamespace(Namespace.DEFAULT_NAME)
                .addAuthTokenListener(authTokenListener);

        log.info("Socket.IO server started on {}:{} (boss={}, worker={})",
                host, port, config.getBossThreads(), config.getWorkerThreads());
        return server;
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
