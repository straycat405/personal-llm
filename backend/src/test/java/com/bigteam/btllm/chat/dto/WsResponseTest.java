package com.bigteam.btllm.chat.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("WebSocket 상태 응답 테스트")
class WsResponseTest {

    @Test
    @DisplayName("연결 완료는 내용 없는 READY 타입이다")
    void createsReadyResponse() {
        WsResponse response = WsResponse.ready();

        assertThat(response.getType()).isEqualTo(WsResponse.Type.READY);
        assertThat(response.getContent()).isNull();
        assertThat(response.getMessage()).isNull();
    }

    @Test
    @DisplayName("요청 접수는 안내 메시지를 가진 QUEUED 타입이다")
    void createsQueuedResponse() {
        WsResponse response = WsResponse.queued("답변 준비 중");

        assertThat(response.getType()).isEqualTo(WsResponse.Type.QUEUED);
        assertThat(response.getMessage()).isEqualTo("답변 준비 중");
        assertThat(response.getContent()).isNull();
    }
}
