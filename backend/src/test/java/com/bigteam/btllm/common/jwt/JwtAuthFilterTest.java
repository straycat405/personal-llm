package com.bigteam.btllm.common.jwt;

import com.bigteam.btllm.user.repository.UserRepository;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * [범위] HANDOFF P0 #1 완료 조건: "JWT subject를 활성 사용자 DB 레코드와 대조".
 * 서명이 유효해도 subject의 사용자가 DB에 없으면(탈퇴/삭제) 인증을 세팅하지 않는지 검증한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("JwtAuthFilter — DB 사용자 대조")
class JwtAuthFilterTest {

    @Mock private JwtProvider jwtProvider;
    @Mock private UserRepository userRepository;
    @Mock private HttpServletRequest request;
    @Mock private HttpServletResponse response;
    @Mock private FilterChain filterChain;
    @Mock private Claims claims;

    private static final String TOKEN = "valid.jwt.token";

    @Test
    @DisplayName("서명은 유효하지만 사용자가 DB에 없으면 SecurityContext를 세팅하지 않는다")
    void deletedUserTokenIsNotAuthenticated() throws Exception {
        given(request.getHeader("Authorization")).willReturn("Bearer " + TOKEN);
        given(jwtProvider.isValid(TOKEN)).willReturn(true);
        given(jwtProvider.validateAndGetClaims(TOKEN)).willReturn(claims);
        given(claims.getSubject()).willReturn("999");
        given(userRepository.existsById(999L)).willReturn(false);

        SecurityContextHolder.clearContext();
        new JwtAuthFilter(jwtProvider, userRepository)
                .doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("서명이 유효하고 사용자가 존재하면 SecurityContext를 세팅한다")
    void activeUserTokenIsAuthenticated() throws Exception {
        given(request.getHeader("Authorization")).willReturn("Bearer " + TOKEN);
        given(jwtProvider.isValid(TOKEN)).willReturn(true);
        given(jwtProvider.validateAndGetClaims(TOKEN)).willReturn(claims);
        given(claims.getSubject()).willReturn("1");
        given(claims.get("email", String.class)).willReturn("user@test.com");
        given(userRepository.existsById(1L)).willReturn(true);

        SecurityContextHolder.clearContext();
        new JwtAuthFilter(jwtProvider, userRepository)
                .doFilterInternal(request, response, filterChain);

        AuthUser principal = (AuthUser) SecurityContextHolder.getContext()
                .getAuthentication().getPrincipal();
        assertThat(principal.id()).isEqualTo(1L);
        assertThat(principal.email()).isEqualTo("user@test.com");

        SecurityContextHolder.clearContext();
    }
}
