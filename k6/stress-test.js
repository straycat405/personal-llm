import http from 'k6/http';
import { sleep, check, group } from 'k6';
import { Trend, Rate } from 'k6/metrics';

// ── 커스텀 지표 ──────────────────────────────────────────────
const chatRoomCreateDuration = new Trend('chat_room_create_duration'); // 채팅방 생성 응답시간
const chatRoomListDuration   = new Trend('chat_room_list_duration');   // 채팅방 목록 조회 응답시간
const historyFetchDuration   = new Trend('chat_history_fetch_duration'); // 대화 이력 조회 응답시간
const errorRate              = new Rate('error_rate');                 // 전체 에러율

// ── 스트레스 패턴: 단계적으로 200 VU까지 증가 ────────────────────
// 목적: HikariCP 기본 max-pool-size=10 → 커넥션 고갈 구간 수치 기록
// load-test.js(30 VU) 대비 ~7배 부하 → 병목 임계점 탐색
export const options = {
  stages: [
    { duration: '30s', target: 50  }, // 워밍업
    { duration: '1m',  target: 100 }, // 부하 증가
    { duration: '1m',  target: 200 }, // 스트레스 구간 진입
    { duration: '2m',  target: 200 }, // 200 VU 유지 — 병목 관찰 핵심 구간
    { duration: '30s', target: 0   }, // 쿨다운
  ],
  thresholds: {
    // 기준 완화 — 병목 수치 확인이 목적 (통과/실패보다 수치 자체가 중요)
    'http_req_duration': ['p(95)<5000'], // 5초 이내면 계속 진행
    'error_rate':        ['rate<0.30'],  // 에러율 30% 미만이면 계속 진행
  },
};

const BASE_URL = 'http://localhost:8080';

// ── setup: 테스트 시작 시 1회 — 공용 JWT 발급 ───────────────────
export function setup() {
  const email    = `stress_${Date.now()}@test.com`; // 이전 테스트와 계정 충돌 방지
  const password = 'stress1234';

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

// ── default: VU 1명의 반복 시나리오 ──────────────────────────────
export default function (data) {
  const headers = {
    'Content-Type': 'application/json',
    'Authorization': `Bearer ${data.token}`,
  };

  // ── 채팅방 생성 — INSERT 트랜잭션, 커넥션 점유 시간 측정 ─────────
  let roomId;
  group('채팅방 생성', () => {
    const res = http.post(
      `${BASE_URL}/api/v1/chat-rooms`,
      JSON.stringify({ title: `stress ${__VU}-${__ITER}` }),
      { headers },
    );
    const ok = check(res, { '생성 201': (r) => r.status === 201 });
    errorRate.add(!ok);
    chatRoomCreateDuration.add(res.timings.duration);
    if (ok) roomId = res.json('data.id');
  });

  sleep(0.3); // load-test.js(0.5s) 대비 단축 — 더 많은 동시 요청 유도

  // ── 채팅방 목록 조회 — SELECT + 유저별 필터링 ────────────────────
  group('채팅방 목록 조회', () => {
    const res = http.get(`${BASE_URL}/api/v1/chat-rooms`, { headers });
    const ok = check(res, { '조회 200': (r) => r.status === 200 });
    errorRate.add(!ok);
    chatRoomListDuration.add(res.timings.duration);
  });

  sleep(0.3);

  if (roomId) {
    // ── 대화 이력 조회 — SELECT WHERE chat_room_id ────────────────
    group('대화 이력 조회', () => {
      const res = http.get(
        `${BASE_URL}/api/v1/chat-rooms/${roomId}/histories`,
        { headers },
      );
      const ok = check(res, { '이력 200': (r) => r.status === 200 });
      errorRate.add(!ok);
      historyFetchDuration.add(res.timings.duration);
    });

    sleep(0.3);

    // ── 채팅방 삭제 — DELETE 트랜잭션, DB 데이터 누적 방지 ──────────
    group('채팅방 삭제', () => {
      http.del(`${BASE_URL}/api/v1/chat-rooms/${roomId}`, null, { headers });
    });
  }

  sleep(0.5); // load-test.js(1s) 대비 단축
}
