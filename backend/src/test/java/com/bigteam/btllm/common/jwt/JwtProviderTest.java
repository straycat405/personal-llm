package com.bigteam.btllm.common.jwt;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [범위] Compose/직접 실행/CI 어디서든 jwt.secret이 안전하지 않으면 애플리케이션이
 * 기동 실패해야 한다는 요구(HANDOFF P0 #1)를 JwtProvider 생성자 검증만으로 재현한다.
 * 실제 docker compose 기동 실패는 compose 파일의 `${JWT_SECRET:?...}` 문법으로 보장되며,
 * 이 테스트는 그와 별개로 애플리케이션 레벨 방어선(저엔트로피/알려진 기본값)을 검증한다.
 */
@DisplayName("JwtProvider 시크릿 검증")
class JwtProviderTest {

    private static final long EXPIRY_MS = 3_600_000L;
    private static final String VALID_SECRET = "this-is-a-sufficiently-long-random-test-secret-value";

    @Nested
    @DisplayName("기동 실패해야 하는 secret")
    class RejectedSecrets {

        @Test
        @DisplayName("null이면 시작 실패")
        void nullSecretFailsFast() {
            assertThatThrownBy(() -> new JwtProvider(null, EXPIRY_MS))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("설정되지 않았습니다");
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "   "})
        @DisplayName("빈 값/공백이면 시작 실패")
        void blankSecretFailsFast(String blank) {
            assertThatThrownBy(() -> new JwtProvider(blank, EXPIRY_MS))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("32바이트 미만이면 시작 실패")
        void shortSecretFailsFast() {
            assertThatThrownBy(() -> new JwtProvider("short-secret", EXPIRY_MS))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("너무 짧습니다");
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "btllm-dev-secret-change-in-production",
                "changeme", "CHANGEME", "change-me",
                "secret", "password", "test-secret", "default-secret"
        })
        @DisplayName("알려진 기본값/placeholder면 시작 실패")
        void knownInsecureSecretFailsFast(String insecure) {
            assertThatThrownBy(() -> new JwtProvider(insecure, EXPIRY_MS))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("알려진 기본값");
        }
    }

    @Nested
    @DisplayName("기동 성공해야 하는 secret")
    class AcceptedSecrets {

        @Test
        @DisplayName("32바이트 이상 고유 값이면 정상 기동하고 토큰 발급/검증이 동작한다")
        void validSecretIssuesAndValidatesToken() {
            JwtProvider provider = new JwtProvider(VALID_SECRET, EXPIRY_MS);

            String token = provider.createAccessToken(1L, "user@test.com");

            assertThat(provider.isValid(token)).isTrue();
            Claims claims = provider.validateAndGetClaims(token);
            assertThat(claims.getSubject()).isEqualTo("1");
            assertThat(claims.get("email", String.class)).isEqualTo("user@test.com");
        }

        @Test
        @DisplayName("Spring random.value 대체값과 동일한 32자 길이 값도 기동 성공한다")
        void randomValueLengthSecretSucceeds() {
            // ${random.value} 대체는 하이픈 없는 32자 UUID 형태(32바이트) — 경계값 검증
            String uuidLike = "0123456789abcdef0123456789abcdef";
            assertThat(uuidLike.length()).isGreaterThanOrEqualTo(32);

            assertThat(new JwtProvider(uuidLike, EXPIRY_MS)).isNotNull();
        }
    }
}
