import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  scenarios: {
    realistic_spike: {
      executor: 'per-vu-iterations',
      vus: 1000,
      iterations: 1,
      maxDuration: '2m',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'], // 에러율 1% 미만
    'http_req_duration{endpoint:submission}': ['p(95)<150'], // 동시 제출의 95%가 150ms 이내
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://127.0.0.1:8080/api';
const HEADERS = {
  'Content-Type': 'application/json',
  'X-Formlimpic': 'formlimpic',
  'X-Formlimpic-Bypass': 'formlimpic-loadtest-pass',
  'X-Forwarded-For': '192.168.1.1',
};

export function setup() {
  const adminName = 'admin_' + Date.now();
  const signupRes = http.post(
    `${BASE_URL}/auth/signup`,
    JSON.stringify({ username: adminName, password: 'adminPassword123!' }),
    { headers: HEADERS }
  );
  check(signupRes, { 'Admin signed up': (r) => r.status === 200 });

  // 1,000명이 가입 및 티켓 발급을 여유 있게 마칠 수 있도록 25초 뒤 시작으로 설정
  const now = Date.now();
  const startsAt = new Date(now + 25000).toISOString();
  const expiresAt = new Date(now + 120000).toISOString();

  const formRes = http.post(
    `${BASE_URL}/forms`,
    JSON.stringify({
      title: 'k6 기반 1,000명 동시 제출 부하 테스트 폼',
      content: '1,000 VUs 정각 동시 제출 부하 테스트',
      startsAt: startsAt,
      expiresAt: expiresAt,
      hasBubble: true,
    }),
    { headers: HEADERS }
  );
  check(formRes, { 'Form created': (r) => r.status === 200 });
  const form = JSON.parse(formRes.body);

  return {
    formId: form.id,
    startsAtMs: Date.parse(startsAt),
  };
}

export default function (data) {
  // 실제 상황처럼 유저들이 0~15초 사이에 서서히 가입하고 들어옴
  // BCrypt 해시 계산이 18스레드에 자연스럽게 분산되도록 초기 지연
  const rampDelay = ((__VU - 1) / 1000) * 15; // 0초 ~ 15초에 걸쳐 고르게 분산
  sleep(rampDelay);

  const ip = `10.0.${Math.floor(__VU / 256)}.${__VU % 256}`;
  const username = `u_${Date.now()}_${__VU}`;
  const vuHeaders = {
    'Content-Type': 'application/json',
    'X-Formlimpic': 'formlimpic',
    'X-Formlimpic-Bypass': 'formlimpic-loadtest-pass',
    'X-Forwarded-For': ip,
  };

  // 1. 회원가입 (사전 준비)
  const signupRes = http.post(
    `${BASE_URL}/auth/signup`,
    JSON.stringify({ username: username, password: 'userPassword123!' }),
    { headers: vuHeaders, tags: { endpoint: 'signup' } }
  );

  check(signupRes, {
    'User registered': (r) => r.status === 200,
  });

  // 2. 티켓(멤버십 번호) 발급 (사전 준비)
  const ticketRes = http.post(
    `${BASE_URL}/forms/${data.formId}/ticket`,
    null,
    { headers: vuHeaders, tags: { endpoint: 'ticket' } }
  );

  check(ticketRes, {
    'Ticket obtained': (r) => r.status === 200,
  });

  // 3. 정각(startsAtMs)까지 모든 1,000명의 VU가 카운트다운하며 대기!
  const waitMs = data.startsAtMs - Date.now();
  if (waitMs > 0) {
    sleep(waitMs / 1000);
  }

  // 4. ★ 정각(0초)이 되는 순간 1,000명이 동시에 한꺼번에 발사! ★
  const submitRes = http.post(
    `${BASE_URL}/forms/${data.formId}/submissions`,
    JSON.stringify({
      name: `선착순_${__VU}`,
      birthDate: '000101',
      phone: `010-0000-${String(__VU).padStart(4, '0')}`,
      bubble: `버블_${__VU}`,
    }),
    { headers: vuHeaders, tags: { endpoint: 'submission' } }
  );

  check(submitRes, {
    'Submission success (200)': (r) => r.status === 200,
  });
}
