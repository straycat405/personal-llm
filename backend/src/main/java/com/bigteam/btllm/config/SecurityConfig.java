package com.bigteam.btllm.config;

import com.bigteam.btllm.common.jwt.JwtAuthFilter;
import com.bigteam.btllm.common.jwt.JwtProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtProvider jwtProvider;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // [설계] DelegatingSecurityContextRepository 명시:
            //   - RequestAttributeSecurityContextRepository: request attribute 기반 (Spring Security 6.1+)
            //   - HttpSessionSecurityContextRepository(allowSessionCreation=false):
            //       @WebMvcTest의 authentication() post-processor가 생성한 세션에서 SecurityContext 로드
            //       allowSessionCreation=false → 운영 환경(세션 없음)에서는 getSession(false)=null → STATELESS 유지
            .securityContext(ctx -> ctx
                .securityContextRepository(new DelegatingSecurityContextRepository(
                    new RequestAttributeSecurityContextRepository(),
                    sessionSecurityContextRepository()
                ))
            )
            // [설계] 미인증 접근 시 기본값 Http403ForbiddenEntryPoint(403) → 401로 변경
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
            )
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/auth/**").permitAll()
                .requestMatchers("/ws/**").permitAll()
                // [설계] SSE 엔드포인트 permitAll: EventSource는 커스텀 헤더 미지원
                //   UUID jobId가 난수성으로 보호 (추측 불가 — 36자리 UUID v4)
                .requestMatchers("/api/v1/admin/etl/*/progress").permitAll()
                .anyRequest().authenticated()
            )
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .addFilterBefore(new JwtAuthFilter(jwtProvider), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    // [설계] allowSessionCreation=false: 세션이 이미 존재할 때만 읽음
    //   운영: JWT Stateless → 세션 없음 → getSession(false) → null → 저장/로드 없음
    //   테스트: authentication() post-processor가 생성한 세션에서 SecurityContext 로드
    private HttpSessionSecurityContextRepository sessionSecurityContextRepository() {
        HttpSessionSecurityContextRepository repo = new HttpSessionSecurityContextRepository();
        repo.setAllowSessionCreation(false);
        return repo;
    }
}
