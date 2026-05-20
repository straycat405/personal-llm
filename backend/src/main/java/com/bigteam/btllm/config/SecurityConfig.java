package com.bigteam.btllm.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

	@Bean
	public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
		http
			// 개발 단계: CSRF 토큰 검증 비활성화 (운영 전 활성화 필요)
			.csrf(AbstractHttpConfigurer::disable)
			// 개발 단계: 모든 요청 인증 없이 허용 (운영 전 경로별 권한 설정 필요)
			.authorizeHttpRequests(auth -> auth
				.anyRequest().permitAll()
			)
			// WebSocket 연결에 HTTP Basic 인증 팝업 뜨지 않도록 비활성화
			.httpBasic(AbstractHttpConfigurer::disable)
			.formLogin(AbstractHttpConfigurer::disable);

		return http.build();
	}
}