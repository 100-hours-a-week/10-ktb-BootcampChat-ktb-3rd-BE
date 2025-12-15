package com.ktb.chatapp.config;

import com.ktb.chatapp.security.CustomBearerTokenResolver;
import com.ktb.chatapp.security.SessionAwareJwtAuthenticationConverter;
import java.time.Duration;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
@Slf4j
public class SecurityConfig {

    private final JwtDecoder jwtDecoder;
    private final CustomBearerTokenResolver bearerTokenResolver;
    private final SessionAwareJwtAuthenticationConverter jwtAuthenticationConverter;

    private static final List<String> CORS_ALLOWED_ORIGINS = List.of("*");

    private static final List<String> CORS_ALLOWED_HEADERS = List.of(
            "Content-Type",
            "Authorization",
            "x-auth-token",
            "x-session-id",
            "Cache-Control",
            "Pragma"
    );

    private static final List<String> CORS_EXPOSED_HEADERS = List.of(
            "Authorization",
            "x-auth-token",
            "x-session-id"
    );

    private static final List<String> CORS_ALLOWED_METHODS = List.of("GET", "POST", "PUT", "DELETE", "OPTIONS");

    @Bean
    public PasswordEncoder passwordEncoder(
            @Value("${security.bcrypt.strength:10}") int strength
    ) {
        return new BCryptPasswordEncoder(strength);
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authenticationConfiguration) throws Exception {
        return authenticationConfiguration.getAuthenticationManager();
    }
        /* ===============================
           1️⃣ AUTH 전용 (JWT 검사 ❌)
           =============================== */
        @Bean
        @Order(1)
        public SecurityFilterChain publicChain(HttpSecurity http) throws Exception {
            http
                    .securityMatcher(
                            "/api/auth/**",
                            "/api/health",
                            "/api/files/**",
                            "/api/uploads/**",
                            "/api/v3/api-docs/**",
                            "/api/swagger-ui/**",
                            "/api/swagger-ui.html",
                            "/api/docs/**"
                    )
                    .csrf(AbstractHttpConfigurer::disable)
                    .cors(cors -> cors.configurationSource(request -> createCorsConfiguration()))
                    .authorizeHttpRequests(auth -> auth
                            .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                            .anyRequest().permitAll()
                    );

            return http.build();
        }

    @Bean
    @Order(2)
    public SecurityFilterChain apiChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/api/**")
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(request -> createCorsConfiguration()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/api/health").permitAll()
                        .anyRequest().authenticated()
                )
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .bearerTokenResolver(bearerTokenResolver)
                        .jwt(jwt -> jwt
                                .decoder(jwtDecoder)
                                .jwtAuthenticationConverter(jwtAuthenticationConverter)
                        )
                );

        return http.build();
    }

    private CorsConfiguration createCorsConfiguration() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(
                "http://localhost:3000",
                "https://chat.goorm-ktb-010.goorm.team"
        ));
//        config.setAllowedOriginPatterns(List.of("*"));
        config.setAllowedMethods(CORS_ALLOWED_METHODS);
        config.setAllowedHeaders(CORS_ALLOWED_HEADERS);
        config.setExposedHeaders(CORS_EXPOSED_HEADERS);
        config.setAllowCredentials(true);
        config.setMaxAge(Duration.ofHours(1).getSeconds());
        return config;
    }

//    private CorsConfiguration cors(HttpServletRequest request) {
//        CorsConfiguration config = new CorsConfiguration();
//        config.setAllowedOriginPatterns(List.of("*"));
//        config.setAllowedMethods(List.of("GET","POST","PUT","DELETE","OPTIONS"));
//        config.setAllowedHeaders(List.of("*"));
//        config.setExposedHeaders(List.of("x-auth-token","x-session-id"));
//        config.setAllowCredentials(true);
//        config.setMaxAge(3600L);
//        return config;
//    }

//    @Bean
//    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
//
//        http
//                .csrf(AbstractHttpConfigurer::disable)
//                .cors(cors -> cors.configurationSource(request -> createCorsConfiguration()))
//                .authorizeHttpRequests(authorize -> authorize
//                        .requestMatchers(org.springframework.http.HttpMethod.OPTIONS, "/api/**").permitAll()
//                        .requestMatchers(
//                                "/api/health",
//                                "/api/auth/**",
//                                "/api/v3/api-docs/**",
//                                "/api/swagger-ui/**",
//                                "/api/swagger-ui.html",
//                                "/api/docs/**",
//                                "/api/files/**",
//                                 "/api/files/upload",
//                                "/api/uploads",
//                                "/api/uploads/**"
//                        ).permitAll()
//                        .requestMatchers("/api/**").authenticated()
//                        .anyRequest().permitAll()
//                )
//                .securityMatcher("/api/**")
//                .sessionManagement(session -> session
//                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
//                )
//                // Spring Security 6 OAuth2 Resource Server 설정
//                .oauth2ResourceServer(oauth2 -> oauth2
//                        .bearerTokenResolver(bearerTokenResolver)
//                        .jwt(jwt -> jwt
//                                .decoder(jwtDecoder)
//                                .jwtAuthenticationConverter(jwtAuthenticationConverter)
//                        )
//                );
//
//        return http.build();
//    }

}
