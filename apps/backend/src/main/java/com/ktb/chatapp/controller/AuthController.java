package com.ktb.chatapp.controller;

import com.ktb.chatapp.dto.*;
import com.ktb.chatapp.event.SessionEndedEvent;
import com.ktb.chatapp.model.User;
import com.ktb.chatapp.repository.UserRepository;
import com.ktb.chatapp.service.*;
import com.mongodb.client.MongoDatabase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.bson.Document;

@Tag(name = "인증 (Authentication)", description = "사용자 인증 관련 API - 회원가입, 로그인, 로그아웃, 토큰 관리")
@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final SessionService sessionService;
    private final ApplicationEventPublisher eventPublisher;
    private final MongoTemplate mongoTemplate;

    @Operation(summary = "인증 API 상태 확인", description = "인증 API의 사용 가능한 엔드포인트 목록을 반환합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "API 상태 정보 조회 성공")
    })
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = false)
    @SecurityRequirement(name = "")
    @GetMapping
    public ResponseEntity<?> getAuthStatus() {
        Map<String, String> routes = new LinkedHashMap<>();
        routes.put("/register", "POST - 새 사용자 등록");
        routes.put("/login", "POST - 사용자 로그인");
        routes.put("/logout", "POST - 로그아웃 (인증 필요)");
        routes.put("/verify-token", "POST - 토큰 검증");
        routes.put("/refresh-token", "POST - 토큰 갱신 (인증 필요)");
        return ResponseEntity.ok(Map.of("status", "active", "routes", routes));
    }

    @Operation(summary = "회원가입", description = "새로운 사용자를 등록합니다. 등록 성공 시 JWT 토큰과 세션 ID가 반환됩니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "회원가입 성공",
            content = @Content(schema = @Schema(implementation = LoginResponse.class))),
        @ApiResponse(responseCode = "400", description = "유효하지 않은 입력값",
            content = @Content(schema = @Schema(implementation = StandardResponse.class),
                examples = @ExampleObject(value = "{\"success\":false,\"code\":\"VALIDATION_ERROR\",\"errors\":[{\"field\":\"email\",\"message\":\"올바른 이메일 형식이 아닙니다.\"}]}"))),
        @ApiResponse(responseCode = "409", description = "이미 등록된 이메일",
            content = @Content(schema = @Schema(implementation = StandardResponse.class),
                examples = @ExampleObject(value = "{\"success\":false,\"message\":\"이미 등록된 이메일입니다.\"}"))),
        @ApiResponse(responseCode = "500", description = "서버 내부 오류",
            content = @Content(schema = @Schema(implementation = StandardResponse.class)))
    })
    @SecurityRequirement(name = "")
    @PostMapping("/register")
    public ResponseEntity<?> registerUser(
            @Valid @RequestBody RegisterRequest registerRequest,
            BindingResult bindingResult,
            HttpServletRequest request) {

        // Handle validation errors
        ResponseEntity<?> errors = getBindingError(bindingResult);
        if (errors != null) return errors;
        
        // Check existing user
        if (userRepository.findByEmail(registerRequest.getEmail()).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(StandardResponse.error("이미 등록된 이메일입니다."));
        }

        try {
            // Create user
            User user = User.builder()
                    .name(registerRequest.getName())
                    .email(registerRequest.getEmail().toLowerCase())
                    .password(passwordEncoder.encode(registerRequest.getPassword()))
                    .build();

            user = userRepository.save(user);

//            MongoDatabase db = mongoTemplate.getDb();
//            db.runCommand(new Document("ping", 1));

            LoginResponse response = LoginResponse.builder()
                    .success(true)
                    .message("회원가입이 완료되었습니다.")
                    .user(new AuthUserDto(user.getId(), user.getName(), user.getEmail(), user.getProfileImage()))
                    .build();

            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(response);

        } catch (org.springframework.dao.DuplicateKeyException e) {
            log.error("Register error: ", e);
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(StandardResponse.error("이미 등록된 이메일입니다."));
        } catch (IllegalArgumentException e) {
            log.error("Register error: ", e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(StandardResponse.error(e.getMessage()));
        } catch (Exception e) {
            log.error("Register error: ", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(StandardResponse.error("회원가입 처리 중 오류가 발생했습니다."));
        }
    }
    
    @Operation(summary = "로그인", description = "이메일과 비밀번호로 로그인합니다. 성공 시 JWT 토큰과 세션 ID가 반환됩니다. 기존 세션은 자동으로 종료됩니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "로그인 성공",
            content = @Content(schema = @Schema(implementation = LoginResponse.class))),
        @ApiResponse(responseCode = "400", description = "유효하지 않은 입력값",
            content = @Content(schema = @Schema(implementation = StandardResponse.class))),
        @ApiResponse(responseCode = "401", description = "인증 실패 - 이메일 또는 비밀번호가 올바르지 않음",
            content = @Content(schema = @Schema(implementation = StandardResponse.class),
                examples = @ExampleObject(value = "{\"success\":false,\"message\":\"이메일 또는 비밀번호가 올바르지 않습니다.\"}"))),
        @ApiResponse(responseCode = "500", description = "서버 내부 오류",
            content = @Content(schema = @Schema(implementation = StandardResponse.class)))
    })
    @SecurityRequirement(name = "")
    @PostMapping("/login")
    public ResponseEntity<?> login(
            @Valid @RequestBody LoginRequest loginRequest,
            BindingResult bindingResult,
            HttpServletRequest request) {

        // Handle validation errors
        ResponseEntity<?> errors = getBindingError(bindingResult);
        if (errors != null) return errors;
        
        try {
            // Authenticate user
            User user = userRepository.findByEmail(loginRequest.getEmail().toLowerCase())
                    .orElseThrow(() -> new UsernameNotFoundException("User not found"));
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            user.getEmail(),
                            loginRequest.getPassword()
                    )
            );

            SecurityContextHolder.getContext().setAuthentication(authentication);
            
            // 단일 세션 정책을 위해 기존 세션 제거
//            sessionService.removeAllUserSessions(user.getId());

            // Create new session
            SessionMetadata metadata = new SessionMetadata(
                    request.getHeader("User-Agent"),
                    getClientIpAddress(request),
                    request.getHeader("User-Agent")
            );

            SessionCreationResult sessionInfo =
                    sessionService.createSession(user.getId(), metadata);

            // Generate JWT token
            String token = jwtService.generateToken(
                sessionInfo.getSessionId(),
                user.getEmail(),
                user.getId()
            );

            ResponseCookie accessTokenCookie = ResponseCookie.from("accessToken", token)
                    .httpOnly(true)
                    .secure(true)              // 🔥 서버 필수
                    .sameSite("None")          // 🔥 cross-origin 필수
                    .path("/")
                    .maxAge(60 * 60 * 24)
                    .build();

            ResponseCookie sessionIdCookie = ResponseCookie.from("sessionId", sessionInfo.getSessionId())
                    .httpOnly(true)
                    .secure(true)
                    .sameSite("None")
                    .path("/")
                    .maxAge(60 * 60 * 24)
                    .build();


            LoginResponse response = LoginResponse.builder()
                    .success(true)
                    .token(token)
                    .sessionId(sessionInfo.getSessionId())
                    .user(new AuthUserDto(user.getId(), user.getName(), user.getEmail(), user.getProfileImage()))
                    .build();

            return ResponseEntity.ok()
                    .header(HttpHeaders.SET_COOKIE, accessTokenCookie.toString())
                    .header(HttpHeaders.SET_COOKIE, sessionIdCookie.toString())
                    .header("Authorization", "Bearer " + token)
                    .header("x-session-id", sessionInfo.getSessionId())
                    .body(response);

        } catch (UsernameNotFoundException | BadCredentialsException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(StandardResponse.error("이메일 또는 비밀번호가 올바르지 않습니다."));
        } catch (Exception e) {
            log.error("Login error: ", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(StandardResponse.error("로그인 처리 중 오류가 발생했습니다."));
        }
    }
    
    @Operation(summary = "로그아웃", description = "현재 세션을 종료합니다. x-session-id 헤더가 필요합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "로그아웃 성공",
            content = @Content(schema = @Schema(implementation = StandardResponse.class),
                examples = @ExampleObject(value = "{\"success\":true,\"message\":\"로그아웃이 완료되었습니다.\"}"))),
        @ApiResponse(responseCode = "400", description = "x-session-id 헤더 누락",
            content = @Content(schema = @Schema(implementation = StandardResponse.class),
                examples = @ExampleObject(value = "{\"success\":false,\"message\":\"x-session-id 헤더가 필요합니다.\"}"))),
        @ApiResponse(responseCode = "401", description = "인증 실패",
            content = @Content(schema = @Schema(implementation = StandardResponse.class))),
        @ApiResponse(responseCode = "500", description = "서버 내부 오류",
            content = @Content(schema = @Schema(implementation = StandardResponse.class)))
    })
    @PostMapping("/logout")
    public ResponseEntity<StandardResponse<Void>> logout(
            HttpServletRequest request,
            Authentication authentication) {

        try {
            // x-session-id 헤더 필수
            String sessionId = extractSessionId(request);
            if (sessionId == null || sessionId.isEmpty()) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(StandardResponse.error("x-session-id 헤더가 필요합니다."));
            }
            
            if (authentication != null) {
                // Spring Security 6 패턴: Authentication의 Details에서 userId 추출
                @SuppressWarnings("unchecked")
                Map<String, Object> details = (Map<String, Object>) authentication.getDetails();
                String userId = (String) details.get("userId");
                
                if (userId != null) {
                    sessionService.removeSession(userId, sessionId);
                    
                    // Publish event for session ended
                    eventPublisher.publishEvent(new SessionEndedEvent(
                            this, userId, "logout", "로그아웃되었습니다."
                    ));
                }
            }

            SecurityContextHolder.clearContext();
            
            return ResponseEntity.ok(StandardResponse.success("로그아웃이 완료되었습니다.", null));

        } catch (Exception e) {
            log.error("Logout error: ", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(StandardResponse.error("로그아웃 처리 중 오류가 발생했습니다."));
        }
    }
    

    @Operation(summary = "토큰 검증", description = "JWT 토큰과 세션의 유효성을 검증합니다. x-auth-token 또는 Authorization 헤더와 x-session-id 헤더가 필요합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "토큰 검증 성공",
            content = @Content(schema = @Schema(implementation = TokenVerifyResponse.class))),
        @ApiResponse(responseCode = "400", description = "토큰 또는 세션 ID 누락",
            content = @Content(schema = @Schema(implementation = TokenVerifyResponse.class),
                examples = @ExampleObject(value = "{\"valid\":false,\"message\":\"토큰 또는 세션 ID가 필요합니다.\"}"))),
        @ApiResponse(responseCode = "401", description = "유효하지 않은 토큰 또는 만료된 세션",
            content = @Content(schema = @Schema(implementation = TokenVerifyResponse.class),
                examples = @ExampleObject(value = "{\"valid\":false,\"message\":\"유효하지 않은 토큰입니다.\"}"))),
        @ApiResponse(responseCode = "500", description = "서버 내부 오류",
            content = @Content(schema = @Schema(implementation = TokenVerifyResponse.class)))
    })
    @SecurityRequirement(name = "")
    @PostMapping("/verify-token")
    public ResponseEntity<?> verifyToken(HttpServletRequest request) {
        try {
            String token = extractToken(request);
            String sessionId = extractSessionId(request);

            if (token == null || sessionId == null) {
                return ResponseEntity.badRequest()
                        .body(new TokenVerifyResponse(false, "토큰 또는 세션 ID가 필요합니다.", null));
            }

            if (!jwtService.validateToken(token)) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(new TokenVerifyResponse(false, "유효하지 않은 토큰입니다.", null));
            }

            String userId = jwtService.extractUserId(token);

            // ✅ handshake 기준으로 단일 검증
            SessionValidationResult result =
                    sessionService.validateSessionForHandshake(userId, sessionId);

            if (!result.isValid()) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(new TokenVerifyResponse(false, "만료된 세션입니다.", null));
            }

            return ResponseEntity.ok(
                    new TokenVerifyResponse(
                            true,
                            "토큰이 유효합니다.",
                            new AuthUserDto(userId, null, null, null)
                    )
            );

        } catch (Exception e) {
            log.error("Token verification error", e);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new TokenVerifyResponse(false, "토큰 검증 중 오류가 발생했습니다.", null));
        }
    }

    @Operation(summary = "토큰 갱신", description = "만료된 토큰을 갱신합니다. 새로운 토큰과 세션 ID가 발급됩니다. 기존 세션은 종료됩니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "토큰 갱신 성공",
            content = @Content(schema = @Schema(implementation = TokenRefreshResponse.class))),
        @ApiResponse(responseCode = "400", description = "토큰 또는 세션 ID 누락",
            content = @Content(schema = @Schema(implementation = TokenRefreshResponse.class),
                examples = @ExampleObject(value = "{\"success\":false,\"message\":\"토큰 또는 세션 ID가 필요합니다.\"}"))),
        @ApiResponse(responseCode = "401", description = "유효하지 않은 사용자 또는 만료된 세션",
            content = @Content(schema = @Schema(implementation = TokenRefreshResponse.class),
                examples = @ExampleObject(value = "{\"success\":false,\"message\":\"만료된 세션입니다.\"}"))),
        @ApiResponse(responseCode = "500", description = "서버 내부 오류",
            content = @Content(schema = @Schema(implementation = TokenRefreshResponse.class)))
    })
    @SecurityRequirement(name = "")
    @PostMapping("/refresh-token")
    public ResponseEntity<?> refreshToken(HttpServletRequest request) {
        try {
            String token = extractToken(request);
            String sessionId = extractSessionId(request);
            
            if (token == null || sessionId == null) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(new TokenRefreshResponse(false, "토큰 또는 세션 ID가 필요합니다.", null, null));
            }

            // 만료된 토큰이라도 사용자 정보는 추출 가능
            String userId = jwtService.extractUserIdFromExpiredToken(token);
            
            if (!sessionService.validateSession(userId, sessionId).isValid()) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(new TokenRefreshResponse(false, "만료된 세션입니다.", null, null));
            }

            // 세션 갱신 - 새로운 세션 ID 생성
            sessionService.removeSession(userId, sessionId);
            SessionMetadata metadata = new SessionMetadata(
                    request.getHeader("User-Agent"),
                    getClientIpAddress(request),
                    request.getHeader("User-Agent")
            );

            SessionCreationResult newSessionInfo = sessionService.createSession(userId, metadata);

            // 새로운 토큰과 세션 ID 생성
            String newToken = jwtService.generateToken(
                    newSessionInfo.getSessionId(),
                    null,
                    userId
            );
            return ResponseEntity.ok(new TokenRefreshResponse(true, "토큰이 갱신되었습니다.", newToken, newSessionInfo.getSessionId()));

        } catch (Exception e) {
            log.error("Token refresh error: ", e);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new TokenRefreshResponse(false, "토큰 갱신 중 오류가 발생했습니다.", null, null));
        }
    }
    
    private String getClientIpAddress(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }

        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isEmpty()) {
            return xRealIp;
        }

        return request.getRemoteAddr();
    }

    private String extractSessionId(HttpServletRequest request) {

        // ✅ 1. Cookie 우선
        if (request.getCookies() != null) {
            for (var cookie : request.getCookies()) {
                if ("sessionId".equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }

        // 2. 헤더
        String sessionId = request.getHeader("x-session-id");
        if (sessionId != null && !sessionId.isEmpty()) {
            return sessionId;
        }

        // 3. query param (fallback)
        return request.getParameter("sessionId");
    }

    private String extractToken(HttpServletRequest request) {

        // ✅ 1. Cookie 우선 (프론트/E2E 핵심)
        if (request.getCookies() != null) {
            for (var cookie : request.getCookies()) {
                if ("accessToken".equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }

        // 2. x-auth-token 헤더
        String token = request.getHeader("x-auth-token");
        if (token != null && !token.isEmpty()) {
            return token;
        }

        // 3. Authorization 헤더
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }

        return null;
    }
    
    private ResponseEntity<?> getBindingError(BindingResult bindingResult) {
        if (!bindingResult.hasErrors()) {
            return null;
        }
        List<ValidationError> errors = bindingResult.getFieldErrors().stream()
                .map(error -> ValidationError.builder()
                        .field(error.getField())
                        .message(error.getDefaultMessage())
                        .build())
                .collect(java.util.stream.Collectors.toList());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(StandardResponse.validationError("입력값이 올바르지 않습니다.", errors));
    }
}
