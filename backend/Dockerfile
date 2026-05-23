# ── Build stage ─────────────────────────────────────────────
FROM eclipse-temurin:17-jdk-alpine AS builder
WORKDIR /app

# Gradle Wrapper + 의존성 선언 파일 먼저 COPY → 소스 변경 시 의존성 레이어 캐시 재사용
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon --quiet

# 소스 복사 + JAR 빌드 (테스트는 CI 단계에서 별도 실행)
COPY src ./src
RUN ./gradlew bootJar --no-daemon -x test

# ── Runtime stage ────────────────────────────────────────────
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# non-root 실행: 컨테이너 탈출 시 host 권한 최소화
RUN adduser -S -H appuser
COPY --from=builder /app/build/libs/*.jar app.jar
RUN chown appuser app.jar
USER appuser

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
