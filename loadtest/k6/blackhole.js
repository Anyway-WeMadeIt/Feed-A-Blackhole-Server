// 부하 테스트 시나리오(k6). 실행 방법과 해석은 loadtest/README.md를 본다.
//
// 가상 사용자 한 명이 계정 하나를 맡고, 골든 캠페인(클라이언트가 실제로 판을 돌려 만든 검증 통과 로그)을 순서대로 보낸다.
// 서버는 로그를 검증하므로 지어낸 값이 아니라 이 로그를 써야 실제 처리 경로(판 시작, 결산, 노드 구매)가 실행된다.
// 캠페인 끝까지 가면 RESET부터 다시 시작하고, 판 id(runId)는 바퀴마다 바꿔서 중복을 피한다.
//
// SCENARIO: smoke(스크립트 확인), realistic(실제 사용 흐름), stress(요청률을 단계로 올려 한계 찾기), abuse(악의적 대량 동기화)
import http from 'k6/http';
import { check, sleep } from 'k6';
import { SharedArray } from 'k6/data';
import { Counter, Rate } from 'k6/metrics';
import exec from 'k6/execution';

const BASE = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/$/, '');
const SCENARIO = __ENV.SCENARIO || 'smoke';
// 계정 수와 가상 사용자 수의 상한. 사용자 하나가 계정 하나를 쓰므로 최대 동시 사용자 수와 같거나 커야 한다.
const VUS = parseInt(__ENV.VUS || '20', 10);
// realistic에서 사용자 한 명이 보내는 동기화 요청 수(분당). 정상 플레이는 많아야 분당 20건 안팎으로 본다.
const SYNC_PER_MIN = parseFloat(__ENV.SYNC_PER_MIN || '20');
// 대기 시간 배율. 1이면 실제 간격, 0.05면 20배 빠르게(스크립트 확인용).
const THINK_SCALE = parseFloat(__ENV.THINK_SCALE || '1');
const CAMPAIGN_FILE = __ENV.CAMPAIGN_FILE || '/work/src/test/resources/fixtures/golden-campaign.json';

// 로그 하나씩 읽도록 SharedArray의 원소를 로그로 둔다(모든 사용자가 메모리를 나눠 쓴다).
const logs = new SharedArray('campaign-logs', () => JSON.parse(open(CAMPAIGN_FILE)).logs);

const syncErrors = new Rate('sync_errors');
const syncRejected = new Counter('sync_rejected');
const rateLimited = new Counter('rate_limited');
const loginCount = new Counter('login_requests');

const JSON_HEADERS = { 'Content-Type': 'application/json' };

function stepsFromEnv(listName, fallback) {
  return (__ENV[listName] || fallback).split(',').map((s) => parseInt(s.trim(), 10));
}

function buildScenario() {
  const step = __ENV.STEP || '1m';
  switch (SCENARIO) {
    case 'smoke':
      return { executor: 'shared-iterations', vus: 1, iterations: 3, maxDuration: '5m', exec: 'session' };
    case 'realistic':
      // 동시 사용자를 천천히 올렸다가 유지하고 내린다. 사용자마다 로그인 한 번 뒤 분당 SYNC_PER_MIN건 속도로 동기화한다.
      return {
        executor: 'ramping-vus',
        exec: 'session',
        startVUs: 1,
        gracefulRampDown: '1m',
        stages: [
          { duration: __ENV.RAMP || '2m', target: VUS },
          { duration: __ENV.HOLD || '5m', target: VUS },
          { duration: '1m', target: 0 },
        ],
      };
    case 'stress':
      // 초당 동기화 요청 수를 단계로 올린다. 어느 단계에서 응답이 느려지거나 오류가 나는지 본다.
      return {
        executor: 'ramping-arrival-rate',
        exec: 'syncOnce',
        startRate: 1,
        timeUnit: '1s',
        preAllocatedVUs: VUS,
        maxVUs: VUS,
        stages: stepsFromEnv('RATES', '5,10,20,40,80').map((target) => ({ target, duration: step })),
      };
    case 'abuse':
      // 계정을 얻은 공격자가 유효한 로그를 큰 묶음으로 계속 보내는 경우.
      return {
        executor: 'constant-arrival-rate',
        exec: 'abuseSync',
        rate: parseInt(__ENV.ABUSE_RATE || '10', 10),
        timeUnit: '1s',
        duration: __ENV.DURATION || '3m',
        preAllocatedVUs: VUS,
        maxVUs: VUS,
      };
    default:
      throw new Error(`알 수 없는 SCENARIO: ${SCENARIO}`);
  }
}

export const options = {
  scenarios: { [SCENARIO]: buildScenario() },
  setupTimeout: '10m',
  thresholds: {
    // 한계를 찾는 시험이라 합격선은 느슨하게 둔다. stress는 오류가 5%를 넘으면 중단한다.
    'http_req_duration{name:sync}': ['p(95)<1500'],
    sync_errors: [{ threshold: 'rate<0.05', abortOnFail: true, delayAbortEval: '30s' }],
  },
};

// ---------- 준비: 계정을 미리 만든다 ----------

export function setup() {
  const pool = [];
  for (let i = 0; i < VUS; i++) {
    const res = http.post(`${BASE}/api/v1/auth/guest/register`, null, { tags: { name: 'register' } });
    if (res.status !== 201) {
      throw new Error(`계정 준비 실패(${i + 1}/${VUS}): HTTP ${res.status}. 운영 서버라면 Nginx 요청 제한이 걸렸는지 본다.`);
    }
    const body = res.json();
    pool.push({ guestId: body.guestId, guestSecret: body.guestSecret });
  }
  return { pool };
}

// ---------- 사용자(VU)별 상태 ----------

let me = null; // { cred, token, nextSeq, cursor, cycle, needResync }

function attach(data) {
  if (me === null) {
    const cred = data.pool[(exec.vu.idInTest - 1) % data.pool.length];
    me = { cred, token: null, nextSeq: 1, cursor: 0, cycle: 0, needResync: false };
  }
}

function login() {
  loginCount.add(1);
  const res = http.post(`${BASE}/api/v1/auth/guest/login`, JSON.stringify(me.cred), {
    headers: JSON_HEADERS,
    tags: { name: 'login' },
  });
  if (res.status === 429) rateLimited.add(1);
  check(res, { 'login 200': (r) => r.status === 200 });
  me.token = res.status === 200 ? res.json('accessToken') : null;
}

function authHeaders() {
  return { headers: { ...JSON_HEADERS, Authorization: `Bearer ${me.token}` } };
}

function nextLogs(count) {
  const out = [];
  for (let k = 0; k < count; k++) {
    if (me.cursor >= logs.length) {
      me.cursor = 0;
      me.cycle += 1;
    }
    const src = logs[me.cursor++];
    const log = Object.assign({}, src, { seq: me.nextSeq++ });
    if (me.cycle > 0 && src.payload && src.payload.runId) {
      // 바퀴마다 판 id의 앞 8자를 바꿔 계정 안에서 유일하게 한다.
      const runId = me.cycle.toString(16).padStart(8, '0') + src.payload.runId.slice(8);
      log.payload = Object.assign({}, src.payload, { runId });
    }
    out.push(log);
  }
  return out;
}

// 동기화 한 번. count가 0이면 로그 없이 서버 진행도만 받는다(앱 시작 때의 동기화).
function sync(count) {
  if (me.token === null) login();
  let batch = [];
  if (me.needResync) {
    count = 0; // 어긋났으면 로그 없이 서버 순번부터 다시 맞춘다
  }
  if (count > 0) batch = nextLogs(count);

  let res = http.post(`${BASE}/api/v1/me/sync`, JSON.stringify({ logs: batch }), { ...authHeaders(), tags: { name: 'sync' } });
  if (res.status === 401) {
    // 액세스 토큰 만료(15분). 앱은 갱신 후 같은 요청을 한 번 다시 보내므로 여기서는 다시 로그인한다.
    login();
    res = http.post(`${BASE}/api/v1/me/sync`, JSON.stringify({ logs: batch }), { ...authHeaders(), tags: { name: 'sync' } });
  }
  if (res.status === 429) rateLimited.add(1);

  const ok = res.status === 200;
  let rejected = false;
  if (ok) {
    const body = res.json();
    rejected = body.rejected !== null && body.rejected !== undefined;
    me.nextSeq = body.appliedThroughSeq + 1;
    if (rejected || me.needResync) {
      // 서버 순번에 맞추고 캠페인을 처음(RESET)부터 다시 보낸다.
      me.cursor = 0;
      me.cycle += 1;
      me.needResync = false;
    }
  } else {
    me.needResync = true;
  }
  if (rejected) syncRejected.add(1);
  syncErrors.add(!ok || rejected);
  check(res, { 'sync 200': (r) => r.status === 200 });
}

function between(min, max) {
  return min + Math.random() * (max - min);
}

// ---------- 시나리오 함수 ----------

// 앱 한 번 실행: 로그인 → 시작 동기화 → 사용자당 분당 SYNC_PER_MIN건 속도로 동기화(보통 로그 1~3개).
// 간격은 평균 60/SYNC_PER_MIN초이고, 그 0.5~1.5배로 흩어 놓는다. 동시 사용자 N명의 요청률은 N × SYNC_PER_MIN / 60 건/초다.
export function session(data) {
  attach(data);
  login();
  sync(0);
  const meanInterval = 60 / SYNC_PER_MIN;
  const actions = Math.max(4, Math.round(120 / meanInterval)); // 한 번 실행에 약 2분 동안 사용
  for (let i = 0; i < actions; i++) {
    sleep(between(0.5, 1.5) * meanInterval * THINK_SCALE);
    sync(1 + Math.floor(Math.random() * 3));
  }
}

export function syncOnce(data) {
  attach(data);
  sync(1 + Math.floor(Math.random() * 3));
}

export function abuseSync(data) {
  attach(data);
  sync(Math.min(500, parseInt(__ENV.BATCH || '50', 10)));
}
