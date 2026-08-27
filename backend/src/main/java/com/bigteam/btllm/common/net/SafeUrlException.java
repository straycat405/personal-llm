package com.bigteam.btllm.common.net;

import java.io.IOException;

/**
 * [역할] {@link SafeUrlFetcher}가 SSRF 방어 규칙(scheme/포트/사설망 IP/리다이렉트 한도)에
 * 걸려 요청을 거부했을 때 던지는 예외.
 *
 * [설계] IOException을 상속한 이유: 기존 크롤링 코드(EtlPipelineService, LlmTools)가
 * 이미 "네트워크 요청 실패"를 IOException/Exception으로 흡수해 사용자에게 안내 메시지를
 * 돌려주는 구조였다. 이 예외도 같은 경로로 흡수되게 해서 호출부의 예외 처리 골격을
 * 바꾸지 않고 차단 사유만 명확한 메시지로 전달한다.
 */
public class SafeUrlException extends IOException {
    public SafeUrlException(String message) {
        super(message);
    }
}
