import http from 'k6/http';
import { sleep, check } from 'k6';
import { Rate } from 'k6/metrics';

const errorRate = new Rate('error_rate');

// ── 중간 부하: 300~400 VU — HikariCP pool 튜닝 전/후 비교용
// pool=10 시 pending 발생 확인 → pool=30 후 pending 해소 확인이 목적
export const options = {
  stages: [
    { duration: '30s', target: 100 }, // 워밍업
    { duration: '30s', target: 300 }, // 증가
    { duration: '2m',  target: 400 }, // 400 VU 유지 — 비교 핵심 구간
    { duration: '30s', target: 0   }, // 쿨다운
  ],
  thresholds: {
    'http_req_duration': ['p(95)<10000'],
    'error_rate':        ['rate<0.50'],
  },
};

const BASE_URL = 'http://localhost:8080';

export function setup() {
  const email    = `medium_${Date.now()}@test.com`;
  const password = 'medium1234';

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
  if (!token) throw new Error(`로그인 실패: ${loginRes.status}`);
  return { token };
}

export default function (data) {
  const headers = {
    'Content-Type': 'application/json',
    'Authorization': `Bearer ${data.token}`,
  };

  // 채팅방 목록 조회 집중 — DB SELECT 커넥션 경합 유도
  const res = http.get(`${BASE_URL}/api/v1/chat-rooms`, { headers });

  const ok = check(res, { '조회 200': (r) => r.status === 200 });
  errorRate.add(!ok);

  sleep(0.1); // 대기 최소화 — 동시 요청 밀도 유지
}
