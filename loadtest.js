import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  scenarios: {
    realistic_spike: {
      executor: 'per-vu-iterations',
      vus: 1200,
      iterations: 1,
      maxDuration: '2m',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'], // 에러율 1% 미만
    'http_req_duration{endpoint:submission}': ['p(95)<1500'], // 1,200명 동시 폭발 대기열 지연 감안
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
  const adminName = 'adm_' + Math.floor(Math.random() * 1000000);
  const signupRes = http.post(
    `${BASE_URL}/auth/signup`,
    JSON.stringify({ username: adminName, password: 'adminPassword123!' }),
    { headers: HEADERS }
  );
  check(signupRes, { 'Admin signed up': (r) => r.status === 200 });
  if (signupRes.status !== 200) {
    console.error(`Admin signup failed (${signupRes.status}): ${signupRes.body}`);
  }

  const sessionCookie = signupRes.cookies && signupRes.cookies['JSESSIONID']
    ? `JSESSIONID=${signupRes.cookies['JSESSIONID'][0].value}`
    : '';
  const formHeaders = Object.assign({}, HEADERS, sessionCookie ? { Cookie: sessionCookie } : {});

  // 서버 시간과 로컬 머신 간의 클럭 오차(Clock Skew) 동기화 측정
  const t0 = Date.now();
  const timeRes = http.get(`${BASE_URL}/forms`, { headers: HEADERS });
  const t1 = Date.now();
  const serverTimeMs = Date.parse(JSON.parse(timeRes.body).serverTime);
  const rtt = t1 - t0;
  const serverClockOffset = serverTimeMs - (t0 + rtt / 2); // 서버 시각 = 로컬 시각 + serverClockOffset

  // 서버 시각 기준으로 정확히 60초 뒤 오픈
  const serverNowMs = Date.now() + serverClockOffset;
  const startsAt = new Date(serverNowMs + 60000).toISOString();
  const expiresAt = new Date(serverNowMs + 75000).toISOString(); // 시작 15초 후 즉시 결과 공개

  const formRes = http.post(
    `${BASE_URL}/forms`,
    JSON.stringify({
      title: 'k6 기반 1,200명 실전 인간군상 부하 테스트 폼 (시계 동기화 완료)',
      content: '1,200 VUs 선입력단/칼타이밍단/정각단/지각단 동시 제출 테스트',
      startsAt: startsAt,
      expiresAt: expiresAt,
      hasBubble: true,
    }),
    { headers: formHeaders }
  );
  check(formRes, { 'Form created': (r) => r.status === 200 });
  if (formRes.status !== 200) {
    console.error(`Form create failed (${formRes.status}): ${formRes.body}`);
  }
  const form = JSON.parse(formRes.body);

  return {
    formId: form.id,
    startsAtMs: Date.parse(startsAt),
    serverClockOffset: serverClockOffset,
  };
}

export default function (data) {
  // 1,200명이 0~30초 사이에 서서히 가입하고 들어옴
  // BCrypt 해시 계산이 고르게 분산되며, 시작 20초 전(30초~40초경) 모든 사전 작업 완료
  const rampDelay = ((__VU - 1) / 1200) * 30; // 0초 ~ 30초에 걸쳐 고르게 분산
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

  // 3. 실제 인간 군상별 클릭 타이밍 분산 (선입력 도박단 200명, 초고수 400명, 정각단 400명, 느린 사람 200명)
  let groupName;
  let humanOffsetMs;
  if (__VU <= 200) {
    // [선입력단 200명]: 59.85초 ~ 59.94초 (-150ms ~ -60ms 과감한 선입력 배팅)
    groupName = `선입력단_${__VU}`;
    humanOffsetMs = -150 + Math.random() * 90;
  } else if (__VU <= 600) {
    // [칼타이밍단 400명]: 59.94초 ~ 00.02초 (-60ms ~ +20ms 정각 칼타이밍)
    groupName = `칼타이밍_${__VU}`;
    humanOffsetMs = -60 + Math.random() * 80;
  } else if (__VU <= 1000) {
    // [정각단 400명]: 00.02초 ~ 00.12초 (+20ms ~ +120ms 정각 확인 후 클릭)
    groupName = `정각단_${__VU}`;
    humanOffsetMs = 20 + Math.random() * 100;
  } else {
    // [지각단 200명]: 00.12초 ~ 00.42초 (+120ms ~ +300ms 반응 지연)
    groupName = `지각단_${__VU}`;
    humanOffsetMs = 120 + Math.random() * 300;
  }

  // 서버 시각 기준 목표 클릭 시점
  const targetServerTime = data.startsAtMs + humanOffsetMs;
  // 로컬 머신 시각을 서버 시각으로 환산하여 남은 대기 시간 계산
  const currentEstimatedServerTime = Date.now() + data.serverClockOffset;
  const waitMs = targetServerTime - currentEstimatedServerTime;
  if (waitMs > 0) {
    sleep(waitMs / 1000);
  }

  // 4. ★ 정각(0초)이 되는 순간 1,200명이 동시에 한꺼번에 발사! ★
  const submitRes = http.post(
    `${BASE_URL}/forms/${data.formId}/submissions`,
    JSON.stringify({
      name: groupName,
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
