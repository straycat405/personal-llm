import http from 'k6/http';
import { sleep, check, group } from 'k6';
import { Trend, Rate } from 'k6/metrics';

// ── 커스텀 지표: Grafana에서 독립 패널로 조회 가능 ──────────────
const chatRoomCreateDuration = new Trend('chat_room_create_duration'); // 채팅방 생성 응답시간
const chatRoomListDuration   = new Trend('chat_room_list_duration');   // 채팅방 목록 조회 응답시간
const historyFetchDuration   = new Trend('chat_history_fetch_duration'); // 대화 이력 조회 응답시간
const errorRate              = new Rate('error_rate');                 // 전체 에러율

// ── 부하 패턴: 점진 증가 → 유지(병목 관찰) → 감소 ───────────────
export const options = {
  stages: [
    { duration: '30s', target: 10 },  // 워밍업: 0 → 10 VU
    { duration: '1m',  target: 30 },  // 증가: 10 → 30 VU
    { duration: '2m',  target: 30 },  // 유지: 30 VU 고정 (병목 관찰 핵심 구간)
    { duration: '30s', target: 0  },  // 쿨다운: 30 → 0 VU
  ],
  thresholds: {
    // 성능 목표 — 초과 시 테스트 실패(비zero exit code) 처리
    'http_req_duration':           ['p(95)<500'],  // 전체 API p95 500ms 이내
    'chat_room_create_duration':   ['p(95)<300'],  // 채팅방 생성 p95 300ms 이내
    'chat_room_list_duration':     ['p(95)<200'],  // 목록 조회 p95 200ms 이내
    'chat_history_fetch_duration': ['p(95)<200'],  // 이력 조회 p95 200ms 이내
    'error_rate':                  ['rate<0.05'],  // 에러율 5% 미만
  },
};

const BASE_URL = 'http://localhost:8080'; // IntelliJ 로컬 백엔드

// ── setup: 테스트 시작 시 1회 실행 — 공용 JWT 발급 ───────────────
// 반환값(data)은 모든 VU의 default() 함수에 파라미터로 전달됨
export function setup() {
  const email    = `loadtest_${Date.now()}@test.com`; // 타임스탬프로 중복 방지
  const password = 'loadtest1234';

  // 회원가입
  const signupRes = http.post(
    `${BASE_URL}/api/v1/auth/signup`,
    JSON.stringify({ email, password }),
    { headers: { 'Content-Type': 'application/json' } },
  );
  check(signupRes, { 'signup 201': (r) => r.status === 201 });

  // 로그인 → accessToken 획득
  const loginRes = http.post(
    `${BASE_URL}/api/v1/auth/login`,
    JSON.stringify({ email, password }),
    { headers: { 'Content-Type': 'application/json' } },
  );
  check(loginRes, { 'login 200': (r) => r.status === 200 });

  const token = loginRes.json('data.accessToken'); // ApiResponse<LoginResponse> 구조
  if (!token) {
    throw new Error(`로그인 실패: ${loginRes.status} ${loginRes.body}`);
  }
  return { token };
}

// ── default: VU 1명이 반복 실행하는 시나리오 ─────────────────────
export default function (data) {
  const headers = {
    'Content-Type': 'application/json',
    'Authorization': `Bearer ${data.token}`, // setup()에서 공유된 JWT
  };

  // ── 시나리오 1: 채팅방 생성 ──────────────────────────────────
  let roomId;
  group('채팅방 생성', () => {
    const res = http.post(
      `${BASE_URL}/api/v1/chat-rooms`,
      JSON.stringify({ title: `부하테스트 ${__VU}-${__ITER}` }), // VU번호-반복번호로 유니크 제목
      { headers },
    );

    const ok = check(res, {
      '생성 201': (r) => r.status === 201,
      'roomId 있음': (r) => r.json('data.id') !== undefined,
    });
    errorRate.add(!ok);                        // 실패 → 에러율 카운트
    chatRoomCreateDuration.add(res.timings.duration); // 커스텀 지표 기록

    if (ok) {
      roomId = res.json('data.id'); // 이후 시나리오에서 사용
    }
  });

  sleep(0.5); // 실사용 패턴 모방 — 요청 간 간격

  // ── 시나리오 2: 채팅방 목록 조회 ─────────────────────────────
  group('채팅방 목록 조회', () => {
    const res = http.get(`${BASE_URL}/api/v1/chat-rooms`, { headers });

    const ok = check(res, {
      '조회 200': (r) => r.status === 200,
      '배열 반환': (r) => Array.isArray(r.json('data')),
    });
    errorRate.add(!ok);
    chatRoomListDuration.add(res.timings.duration);
  });

  sleep(0.5);

  // ── 시나리오 3: 대화 이력 조회 ───────────────────────────────
  if (roomId) {
    group('대화 이력 조회', () => {
      const res = http.get(
        `${BASE_URL}/api/v1/chat-rooms/${roomId}/histories`,
        { headers },
      );

      const ok = check(res, { '이력 200': (r) => r.status === 200 });
      errorRate.add(!ok);
      historyFetchDuration.add(res.timings.duration);
    });

    sleep(0.5);

    // ── 시나리오 4: 채팅방 삭제 — DB 오염 방지 ───────────────────
    group('채팅방 삭제', () => {
      const res = http.del(
        `${BASE_URL}/api/v1/chat-rooms/${roomId}`,
        null,
        { headers },
      );
      check(res, { '삭제 204': (r) => r.status === 204 });
    });
  }

  sleep(1); // 다음 iteration 전 대기
}
