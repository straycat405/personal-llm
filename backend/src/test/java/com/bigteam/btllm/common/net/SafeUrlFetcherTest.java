package com.bigteam.btllm.common.net;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * [범위] P0 #4 SSRF 방어. 실 네트워크/DNS 없이도 검증 가능한 부분만 다룬다:
 * - scheme/포트 파싱은 순수 문자열 로직
 * - IP 판정(isBlockedAddress)은 InetAddress.getByName에 IP 리터럴을 넣으면 실제 DNS 조회 없이
 *   즉시 파싱되므로(예: "127.0.0.1", "169.254.169.254") 네트워크 의존 없이 검증 가능
 * - validate()가 IP 리터럴 host를 막는 경우, fetch()는 실제 소켓 연결 전에 예외를 던지므로
 *   fetch() 자체도 네트워크 없이 "차단됨"을 확인할 수 있다
 *
 * 실제 원격 서버 대상 리다이렉트 추적·성공 fetch·응답 크기 제한은 여기서 다루지 않는다 —
 * CI에서 재현 가능한 네트워크가 없다. 별도로 로컬에 HTTP 서버를 띄워 수동 검증하거나,
 * WireMock 등 목 서버 도입은 후속 과제로 남긴다.
 */
class SafeUrlFetcherTest {

    private final SafeUrlFetcher fetcher = new SafeUrlFetcher();
    private static final SafeUrlFetcher.FetchOptions OPTIONS =
        SafeUrlFetcher.FetchOptions.of("test-agent", 1000, 1024);

    @Nested
    @DisplayName("scheme 검증")
    class SchemeValidation {

        @ParameterizedTest
        @ValueSource(strings = {"file:///etc/passwd", "ftp://example.com/", "gopher://example.com/"})
        @DisplayName("http/https가 아닌 scheme은 거부한다")
        void rejectsNonHttpSchemes(String url) {
            assertThatThrownBy(() -> fetcher.validate(url))
                .isInstanceOf(SafeUrlException.class)
                .hasMessageContaining("scheme");
        }

        @Test
        @DisplayName("scheme이 없으면 거부한다")
        void rejectsMissingScheme() {
            assertThatThrownBy(() -> fetcher.validate("example.com/path"))
                .isInstanceOf(SafeUrlException.class);
        }
    }

    @Nested
    @DisplayName("포트 검증")
    class PortValidation {

        @Test
        @DisplayName("명시 포트가 80/443이 아니면 거부한다 — 내부 서비스 포트 스캔 차단")
        void rejectsNonStandardPort() {
            assertThatThrownBy(() -> fetcher.validate("http://93.184.216.34:6379/"))
                .isInstanceOf(SafeUrlException.class)
                .hasMessageContaining("포트");
        }

        @Test
        @DisplayName("포트 미명시 시 scheme 기본 포트(http=80)로 판정해 통과시킨다")
        void defaultsToSchemePort() throws Exception {
            // 공인 IP + 포트 미명시 → 80으로 간주되어 포트 검증을 통과해야 한다(예외 없음).
            URI uri = fetcher.validate("http://93.184.216.34/");
            assertThat(uri.getPort()).isEqualTo(-1);   // URI 파싱 자체는 미명시(-1) 그대로 유지
        }
    }

    @Nested
    @DisplayName("사설/루프백/링크로컬 IP 차단 — validate() 경유")
    class BlockedHostValidation {

        @ParameterizedTest
        @ValueSource(strings = {
            "http://127.0.0.1/",
            "http://127.53.0.1/",
            "http://10.0.0.1/",
            "http://172.16.0.1/",
            "http://192.168.1.1/",
            "http://169.254.169.254/",   // 클라우드 메타데이터 엔드포인트
            "http://0.0.0.0/",
            "http://100.64.0.1/",        // CGNAT
            "http://[::1]/",
            "http://[fc00::1]/",
        })
        @DisplayName("사설/루프백/링크로컬/예약 IP를 가리키는 URL은 거부한다")
        void rejectsBlockedLiteralIps(String url) {
            assertThatThrownBy(() -> fetcher.validate(url))
                .isInstanceOf(SafeUrlException.class)
                .hasMessageContaining("차단된 IP");
        }

        @Test
        @DisplayName("공인 IP는 IP 판정을 통과한다(scheme/포트 등 다른 사유로만 실패 가능)")
        void allowsPublicLiteralIp() throws Exception {
            URI uri = fetcher.validate("http://93.184.216.34/");
            assertThat(uri.getHost()).isEqualTo("93.184.216.34");
        }
    }

    @Nested
    @DisplayName("fetch()는 실제 연결 전에 차단한다 (네트워크 미의존)")
    class FetchFailsClosedBeforeConnecting {

        @Test
        @DisplayName("루프백 URL은 소켓 연결 없이 즉시 SafeUrlException")
        void blocksLoopbackWithoutConnecting() {
            assertThatThrownBy(() -> fetcher.fetch("http://127.0.0.1:80/", OPTIONS))
                .isInstanceOf(SafeUrlException.class);
        }

        @Test
        @DisplayName("클라우드 메타데이터 엔드포인트는 소켓 연결 없이 즉시 SafeUrlException")
        void blocksCloudMetadataWithoutConnecting() {
            assertThatThrownBy(() -> fetcher.fetch("http://169.254.169.254/latest/meta-data/", OPTIONS))
                .isInstanceOf(SafeUrlException.class);
        }
    }

    @Nested
    @DisplayName("isBlockedAddress() — IP 분류 매트릭스")
    class IsBlockedAddressMatrix {

        @Test
        @DisplayName("루프백/사설/링크로컬/멀티캐스트/any-local은 차단된다")
        void blocksJdkFlaggedRanges() throws Exception {
            assertThat(fetcher.isBlockedAddress(InetAddress.getByName("127.0.0.1"))).isTrue();
            assertThat(fetcher.isBlockedAddress(InetAddress.getByName("10.1.2.3"))).isTrue();
            assertThat(fetcher.isBlockedAddress(InetAddress.getByName("172.31.255.255"))).isTrue();
            assertThat(fetcher.isBlockedAddress(InetAddress.getByName("192.168.0.1"))).isTrue();
            assertThat(fetcher.isBlockedAddress(InetAddress.getByName("169.254.169.254"))).isTrue();
            assertThat(fetcher.isBlockedAddress(InetAddress.getByName("224.0.0.1"))).isTrue();
            assertThat(fetcher.isBlockedAddress(InetAddress.getByName("0.0.0.0"))).isTrue();
        }

        @Test
        @DisplayName("JDK 플래그가 놓치는 구간(0.0.0.0/8, CGNAT, 예약대역, IPv6 unique-local)도 차단된다")
        void blocksSupplementalRanges() throws Exception {
            assertThat(fetcher.isBlockedAddress(InetAddress.getByName("0.1.2.3"))).isTrue();       // 0.0.0.0/8
            assertThat(fetcher.isBlockedAddress(InetAddress.getByName("100.64.5.5"))).isTrue();    // CGNAT
            assertThat(fetcher.isBlockedAddress(InetAddress.getByName("100.100.0.1"))).isTrue();   // CGNAT
            assertThat(fetcher.isBlockedAddress(InetAddress.getByName("240.0.0.1"))).isTrue();     // 예약
            assertThat(fetcher.isBlockedAddress(InetAddress.getByName("255.255.255.255"))).isTrue();
            assertThat(fetcher.isBlockedAddress(InetAddress.getByName("fc00::1"))).isTrue();       // unique-local
            assertThat(fetcher.isBlockedAddress(InetAddress.getByName("fd12:3456::1"))).isTrue();
        }

        @Test
        @DisplayName("공인 IP는 차단하지 않는다")
        void allowsPublicAddresses() throws Exception {
            assertThat(fetcher.isBlockedAddress(InetAddress.getByName("93.184.216.34"))).isFalse();
            assertThat(fetcher.isBlockedAddress(InetAddress.getByName("100.63.255.255"))).isFalse(); // CGNAT 바로 아래
            assertThat(fetcher.isBlockedAddress(InetAddress.getByName("100.128.0.1"))).isFalse();    // CGNAT 바로 위
            assertThat(fetcher.isBlockedAddress(InetAddress.getByName("8.8.8.8"))).isFalse();
        }
    }

    @Nested
    @DisplayName("리다이렉트 URL 재구성")
    class RedirectResolution {

        @Test
        @DisplayName("절대 URL Location은 그대로 사용한다")
        void resolvesAbsoluteLocation() throws Exception {
            String next = fetcher.resolveRedirect(
                new URI("https://example.com/a"), "http://169.254.169.254/steal");
            assertThat(next).isEqualTo("http://169.254.169.254/steal");
        }

        @Test
        @DisplayName("상대 경로 Location은 base 기준으로 절대 URL이 된다")
        void resolvesRelativeLocation() throws Exception {
            String next = fetcher.resolveRedirect(new URI("https://example.com/a/b"), "../c");
            assertThat(next).isEqualTo("https://example.com/c");
        }

        @Test
        @DisplayName("리다이렉트로 재구성된 내부망 URL은 이어서 validate()에 걸린다")
        void redirectedInternalUrlStillGetsBlocked() throws Exception {
            String next = fetcher.resolveRedirect(new URI("https://example.com/a"), "http://127.0.0.1/admin");
            assertThatThrownBy(() -> fetcher.validate(next))
                .isInstanceOf(SafeUrlException.class)
                .hasMessageContaining("차단된 IP");
        }
    }
}
