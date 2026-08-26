import ws from 'k6/ws';
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Rate } from 'k6/metrics';

// ── 커스텀 지표 ──────────────────────────────────────────────
// [배경] 기존 load-test.js/medium-test.js/stress-test.js는 채팅방 CRUD(REST)만 측정했다.
//        정작 사용자가 매번 체감하는 "메시지 보내고 첫 글자 오기까지"(TTFT)와
//        "끝까지 다 오기까지"(총 응답시간)는 한 번도 측정된 적이 없어 이 스크립트로 채운다.
const ttft               = new Trend('chat_ttft_ms');          // 전송 → 첫 TOKEN까지
const totalResponseTime  = new Trend('chat_total_response_ms'); // 전송 → DONE까지
const wsConnectDuration  = new Trend('ws_connect_ms');          // WS 핸드셰이크 소요
const streamErrorRate    = new Rate('chat_stream_error_rate');  // ERROR 타입 수신 비율

// ── 부하 패턴: 1 → 5 → 10명 동시 채팅으로 단계적 증가 ────────────
// [설계] Ollama 로컬 인스턴스 1개가 모델 추론을 처리 — 동시 요청 시
//        직렬화/대기가 발생하는지가 이 실험의 핵심 관심사 (개선안 #2)
export const options = {
  scenarios: {
    solo: {
      executor: 'constant-vus',
      vus: 1,
      duration: '40s',
      startTime: '0s',
      exec: 'chatOnce',
    },
    five: {
      executor: 'constant-vus',
      vus: 5,
      duration: '40s',
      startTime: '45s',
      exec: 'chatOnce',
    },
    ten: {
      executor: 'constant-vus',
      vus: 10,
      duration: '40s',
      startTime: '90s',
      exec: 'chatOnce',
    },
  },
  // [설계] 베이스라인 측정이 목적 — 통과/실패 게이트가 아니라 수치 확보가 우선이라
  //        threshold는 관찰용으로만 느슨하게 둔다.
  thresholds: {
    'chat_stream_error_rate': ['rate<0.20'],
  },
};

const BASE_URL = 'http://localhost:8080';
const WS_URL = 'ws://localhost:8080/ws/chat';
const PROVIDER = 'ollama';
const MODEL = 'qwen3:8b';
// [설계] RAG 매턴 실행(개선안 #4) 현재 상태를 그대로 반영하는 일반 대화 문구
const PROMPT = '스프링 부트에서 트랜잭션 격리 수준은 어떻게 설정해?';

// ── setup: 계정 1개로 로그인 후 토큰 공유 (다른 k6 스크립트와 동일 패턴) ──
export function setup() {
  const email = `chatstream_${Date.now()}@test.com`;
  const password = 'chatstream1234';

  const signupRes = http.post(
    `${BASE_URL}/api/v1/auth/signup`,
    JSON.stringify({ email, password }),
    { headers: { 'Content-Type': 'application/json' } },
  );
  check(signupRes, { 'signup 201': (r) => r.status === 201 });

  const loginRes = http.post(
    `${BASE_URL}/api/v1/auth/login`,
    JSON.stringify({ email, password }),
    { headers: { 'Content-Type': 'application/json' } },
  );
  const token = loginRes.json('data.accessToken');
  if (!token) throw new Error(`로그인 실패: ${loginRes.status} ${loginRes.body}`);
  return { token };
}

// ── VU 1회 반복: 방 생성 → WS 연결 → 메시지 1건 전송 → TTFT/총시간 측정 → 방 삭제 ──
export function chatOnce(data) {
  const headers = {
    'Content-Type': 'application/json',
    'Authorization': `Bearer ${data.token}`,
  };

  // 매 iteration마다 독립된 방 — conversationId 충돌·메모리 오염 방지
  const roomRes = http.post(
    `${BASE_URL}/api/v1/chat-rooms`,
    JSON.stringify({ title: `chat-stream-${__VU}-${__ITER}` }),
    { headers },
  );
  const roomOk = check(roomRes, { '방 생성 201': (r) => r.status === 201 });
  if (!roomOk) return;
  const room = roomRes.json('data');
  const conversationId = room.conversationId;
  const roomId = room.id;

  const wsUrl = `${WS_URL}?token=${data.token}&provider=${PROVIDER}&model=${encodeURIComponent(MODEL)}`;

  const connectStart = Date.now();
  let sendTime = null;
  let firstTokenRecorded = false;

  const res = ws.connect(wsUrl, {}, function (socket) {
    socket.on('open', () => {
      wsConnectDuration.add(Date.now() - connectStart);
      sendTime = Date.now();
      socket.send(JSON.stringify({ conversationId, content: PROMPT }));
    });

    socket.on('message', (raw) => {
      let msg;
      try {
        msg = JSON.parse(raw);
      } catch {
        return;
      }

      if (msg.type === 'TOKEN' && !firstTokenRecorded && sendTime) {
        // 연결 직후 서버가 보내는 빈 TOKEN(afterConnectionEstablished)은 content='' — 실제 응답 토큰만 카운트
        if (msg.content && msg.content.length > 0) {
          firstTokenRecorded = true;
          ttft.add(Date.now() - sendTime);
        }
      } else if (msg.type === 'DONE') {
        if (sendTime) totalResponseTime.add(Date.now() - sendTime);
        streamErrorRate.add(false);
        socket.close();
      } else if (msg.type === 'ERROR') {
        streamErrorRate.add(true);
        socket.close();
      }
    });

    socket.on('error', () => {
      streamErrorRate.add(true);
    });

    // 안전장치 — 모델 응답이 30초 넘게 안 끝나면 강제 종료 (VU 무한 대기 방지)
    socket.setTimeout(() => socket.close(), 30_000);
  });

  check(res, { 'WS 정상 종료': (r) => r && r.status === 101 });

  // 방 정리 — DB 오염 방지 (load-test.js와 동일 패턴)
  http.del(`${BASE_URL}/api/v1/chat-rooms/${roomId}`, null, { headers });

  sleep(1);
}
