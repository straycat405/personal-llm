package com.bigteam.btllm.common.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Set;

@Component
public class JwtProvider {

    // [설계] HS256 최소 요구 키 길이(jjwt Keys.hmacShaKeyFor 기준 256비트)와 동일한 32바이트를
    //   애플리케이션 레벨에서도 강제한다. jjwt의 WeakKeyException보다 먼저, 원인이 분명한
    //   한국어 메시지로 기동을 실패시켜 운영자가 바로 원인을 알 수 있게 한다.
    private static final int MIN_SECRET_BYTES = 32;

    // [설계] Compose 저장소 기본값 등 "알려진" 취약 시크릿을 명시적으로 차단한다.
    //   일반적인 엔트로피 측정은 하지 않는다(범위 밖) — 대신 실수로 예전 기본값이나
    //   흔한 placeholder를 그대로 쓰는 사고를 막는 데 집중한다.
    private static final Set<String> KNOWN_INSECURE_SECRETS = Set.of(
            "btllm-dev-secret-change-in-production",
            "changeme", "change-me", "change_me",
            "secret", "password", "test-secret", "default-secret"
    );

    private final SecretKey key;
    private final long accessTokenMs;

    public JwtProvider(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.access-token-expiry-ms}") long accessTokenMs) {
        this.key = Keys.hmacShaKeyFor(validateSecret(secret));
        this.accessTokenMs = accessTokenMs;
    }

    private static byte[] validateSecret(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "jwt.secret(JWT_SECRET)이 설정되지 않았습니다. "
                            + "예: openssl rand -base64 32 로 생성한 값을 JWT_SECRET 환경변수로 주입하세요.");
        }
        if (KNOWN_INSECURE_SECRETS.contains(secret.trim().toLowerCase())) {
            throw new IllegalStateException(
                    "jwt.secret(JWT_SECRET)이 알려진 기본값/placeholder입니다. "
                            + "openssl rand -base64 32 등으로 새로 생성한 고유 값을 사용하세요.");
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "jwt.secret(JWT_SECRET) 길이가 " + bytes.length + "바이트로 너무 짧습니다. "
                            + "HS256 최소 요구치인 " + MIN_SECRET_BYTES + "바이트(256비트) 이상이어야 합니다. "
                            + "예: openssl rand -base64 32");
        }
        return bytes;
    }

    // [설계] claim "email": OAuth 연동 시 provider email과 동일한 키 사용 → 통일성 확보
    public String createAccessToken(Long userId, String email) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("email", email)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + accessTokenMs))
                .signWith(key)
                .compact();
    }

    public Claims validateAndGetClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean isValid(String token) {
        try {
            validateAndGetClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }
}
