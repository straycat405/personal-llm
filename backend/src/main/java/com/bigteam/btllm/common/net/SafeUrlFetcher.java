package com.bigteam.btllm.common.net;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Set;

/**
 * [역할] ETL URL 수집(EtlPipelineService)과 LLM 크롤러 Tool(LlmTools)이 공유하는 SSRF 방어
 * 계층. 두 호출부 모두 사용자(또는 LLM이 tool-call로 전달한) 임의 URL을 서버가 대신
 * 요청하므로, scheme/포트/사설망 IP를 검증하지 않으면 내부망·클라우드 메타데이터
 * 엔드포인트(169.254.169.254 등)에 서버 신원으로 접근하는 통로가 된다.
 *
 * [설계 결정사항]
 * - scheme은 http/https만 허용 — file:// 등으로 로컬 파일을 읽어들이는 경로 차단.
 * - 포트는 80/443만 허용 — "웹 페이지 크롤러" 기능 범위를 벗어난 내부 서비스
 *   포트 스캔(DB 5432, Redis 6379, 관리 콘솔 등)을 원천 차단한다. 필요하면 나중에
 *   설정 가능한 allowlist로 확장한다.
 * - 호스트는 문자열 패턴이 아니라 실제 DNS 해석 결과(InetAddress)로 판정한다 — 호스트명이
 *   공인 도메인처럼 보여도 그 이름이 가리키는 IP가 사설/루프백/링크로컬이면 차단된다.
 * - 리다이렉트는 Jsoup의 자동 추적을 끄고 직접 순회하며, 매 홉마다 scheme/포트/IP 검증을
 *   다시 수행한다 — "1차 URL은 공인 IP인데 302로 내부망 URL로 리다이렉트"하는 우회를 막는다.
 * - [알려진 한계] 이 계층은 "검증 시점의 DNS 해석 결과"로 판단한 뒤 Jsoup이 같은 호스트명으로
 *   다시 연결하므로, 검증과 연결 사이의 DNS 재해석 결과가 바뀌는 진짜 DNS-rebinding
 *   TOCTOU 공격까지는 막지 못한다. 완전히 막으려면 검증에 쓴 IP로 직접 연결(주소 pinning)해야
 *   하는데, Jsoup의 커넥션 계층을 우회해야 해서 별도 HTTP 클라이언트 도입이 필요하다.
 *   이 프로젝트는 로컬 단일 사용자 앱이라 위협 모델상 우선순위를 낮췄다 — 인터넷에 노출하거나
 *   다중 사용자 신뢰 경계가 커지면 이 부분을 먼저 강화해야 한다.
 */
@Slf4j
@Component
public class SafeUrlFetcher {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");
    private static final Set<Integer> ALLOWED_PORTS = Set.of(80, 443);
    private static final int MAX_REDIRECTS = 5;

    public record FetchOptions(String userAgent, int timeoutMs, int maxBodyBytes) {
        public static FetchOptions of(String userAgent, int timeoutMs, int maxBodyBytes) {
            return new FetchOptions(userAgent, timeoutMs, maxBodyBytes);
        }
    }

    public Document fetch(String rawUrl, FetchOptions options) throws IOException {
        String currentUrl = rawUrl;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            URI uri = validate(currentUrl);

            Connection.Response response = Jsoup.connect(uri.toString())
                .userAgent(options.userAgent())
                .timeout(options.timeoutMs())
                .maxBodySize(options.maxBodyBytes())
                .followRedirects(false)   // [설계] 자동 추적 대신 매 홉을 직접 재검증한다
                .execute();

            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                String location = response.header("Location");
                if (location == null || location.isBlank()) {
                    throw new SafeUrlException("리다이렉트 응답(" + status + ")에 Location 헤더가 없습니다.");
                }
                currentUrl = resolveRedirect(uri, location);
                log.debug("SafeUrlFetcher 리다이렉트 추적 — hop: {}, next: {}", hop + 1, currentUrl);
                continue;
            }
            return response.parse();
        }
        throw new SafeUrlException("리다이렉트 한도(" + MAX_REDIRECTS + "회)를 초과했습니다: " + rawUrl);
    }

    // ── 검증 ──────────────────────────────────────────────────

    // [설계] private 대신 package-private — SafeUrlFetcherTest가 실 네트워크/DNS 없이
    //   scheme·포트·IP 판정 로직을 직접 검증할 수 있게 한다(fetch() 전체를 타면 실제 Jsoup
    //   연결이 필요해 테스트가 무거워지고 네트워크에 의존하게 된다).
    URI validate(String rawUrl) throws SafeUrlException {
        URI uri = parse(rawUrl);

        String scheme = uri.getScheme();
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase())) {
            throw new SafeUrlException("허용되지 않은 scheme입니다(http/https만 허용): " + rawUrl);
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new SafeUrlException("URL에 호스트가 없습니다: " + rawUrl);
        }

        int port = uri.getPort();
        int effectivePort = port != -1 ? port : ("https".equalsIgnoreCase(scheme) ? 443 : 80);
        if (!ALLOWED_PORTS.contains(effectivePort)) {
            throw new SafeUrlException("허용되지 않은 포트입니다(80/443만 허용): " + rawUrl);
        }

        InetAddress[] resolved;
        try {
            resolved = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new SafeUrlException("호스트를 확인할 수 없습니다: " + host);
        }
        if (resolved.length == 0) {
            throw new SafeUrlException("호스트가 어떤 IP로도 해석되지 않습니다: " + host);
        }
        for (InetAddress address : resolved) {
            if (isBlockedAddress(address)) {
                log.warn("SafeUrlFetcher 차단 — host: {}, blockedIp: {}", host, address.getHostAddress());
                throw new SafeUrlException(
                    "사설/루프백/링크로컬 등 접근이 차단된 IP를 가리키는 호스트입니다: " + host);
            }
        }

        return uri;
    }

    private URI parse(String rawUrl) throws SafeUrlException {
        try {
            return new URI(rawUrl);
        } catch (URISyntaxException e) {
            throw new SafeUrlException("URL 형식이 올바르지 않습니다: " + rawUrl);
        }
    }

    String resolveRedirect(URI base, String location) throws SafeUrlException {
        try {
            return base.resolve(location).toString();
        } catch (IllegalArgumentException e) {
            throw new SafeUrlException("리다이렉트 대상 URL 형식이 올바르지 않습니다: " + location);
        }
    }

    /**
     * [범위] JDK의 InetAddress 플래그(loopback/link-local/site-local/multicast/any-local)로
     * IPv4 RFC1918·169.254.0.0/16(클라우드 메타데이터 포함)·루프백·멀티캐스트를 우선 차단하고,
     * JDK가 놓치는 구간(IPv4 0.0.0.0/8·100.64.0.0/10 CGNAT·240.0.0.0/4 예약대역,
     * IPv6 fc00::/7 unique-local — isSiteLocalAddress()는 폐기된 fec0::/10만 인식한다)을
     * 바이트 단위로 보충 검사한다. 모든 사설/예약 대역을 완전히 망라하진 않는다 — 실사용
     * SSRF 페이로드(내부망, localhost, 클라우드 메타데이터)를 막는 데 초점을 맞췄다.
     */
    boolean isBlockedAddress(InetAddress address) {
        if (address.isLoopbackAddress() || address.isLinkLocalAddress()
            || address.isSiteLocalAddress() || address.isMulticastAddress()
            || address.isAnyLocalAddress()) {
            return true;
        }

        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 0xFF;
            int second = bytes[1] & 0xFF;
            if (first == 0) return true;                                  // 0.0.0.0/8
            if (first == 100 && second >= 64 && second <= 127) return true; // 100.64.0.0/10 (CGNAT)
            return first >= 240;                                          // 240.0.0.0/4 + 255.255.255.255
        }
        if (bytes.length == 16) {
            int first = bytes[0] & 0xFF;
            return (first & 0xFE) == 0xFC;                                // fc00::/7 (unique-local)
        }
        return false;
    }
}
