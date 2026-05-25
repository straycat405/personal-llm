import http from 'k6/http';
import { sleep, check, group } from 'k6';
import { Rate } from 'k6/metrics';

const errorRate = new Rate('error_rate'); // 에러율 추적

// ── 극한 부하: 800 VU — Tomcat maxThreads(200) + HikariCP(10) 한계 돌파 목적
// 목표: hikaricp_connections_pending > 0, p95 응답시간 급등 확인 (Before 수치)
export const options = {
  stages: [
    { duration: '30s', target: 100 }, // 워밍업
    { duration: '30s', target: 400 }, // 빠른 증가
    { duration: '1m',  target: 800 }, // 극한 증가
    { duration: '2m',  target: 800 }, // 800 VU 유지 — 병목 관찰 핵심 구간
    { duration: '30s', target: 0   }, // 쿨다운
  ],
  thresholds: {
    // 수치 기록이 목적 — 기준 완화
    'http_req_duration': ['p(95)<30000'], // 30초 이내면 계속
    'error_rate':        ['rate<0.80'],   // 80% 미만이면 계속
  },
};

const BASE_URL = 'http://localhost:8080';

export function setup() {
  const email    = `extreme_${Date.now()}@test.com`;
  const password = 'extreme1234';

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

  // 채팅방 목록 조회만 반복 — DB SELECT 집중 부하 (INSERT보다 커넥션 경합 명확)
  const res = http.get(`${BASE_URL}/api/v1/chat-rooms`, { headers });

  const ok = check(res, { '조회 200': (r) => r.status === 200 });
  errorRate.add(!ok);

  sleep(0.1); // 대기 최소화 — 최대한 많은 동시 요청 유도
}
