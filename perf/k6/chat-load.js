import http from 'k6/http';
import ws from 'k6/ws';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';

// Load profile: SESSIONS authenticated WebSocket connections held for the warm-up plus the
// measured plateau, and a constant RATE of direct messages. Every measurement is read from the
// receiving socket, so delivery latency includes persistence, outbox fan-out and the send.
//
// Phases: sockets ramp over RAMP, then WARMUP seconds of traffic at RATE that only feeds
// chat_delivery_warmup_ms, then the measured plateau of PLATEAU (HOLD) seconds that feeds
// chat_delivery_ms. Warm-up traffic exists because the first load on a fresh JVM pays for class
// loading and JIT compilation; a baseline taken without it reports a much worse p99.
const BASE = __ENV.BASE_URL || 'http://127.0.0.1:8080';
const WS_BASE = __ENV.WS_URL || BASE.replace('http', 'ws');
const SESSIONS = parseInt(__ENV.SESSIONS || '10000', 10);
const USERS = parseInt(__ENV.USERS || String(SESSIONS), 10);
const RATE = parseInt(__ENV.RATE || '100', 10);
const RAMP = __ENV.RAMP || '60s';
const WARMUP = __ENV.WARMUP || '60s';
const HOLD = __ENV.HOLD || '60s';
const PADDING = parseInt(__ENV.MSG_BYTES || '80', 10);
const CONVERSATIONS = Math.floor(USERS / 2);
const RAMP_MS = durationMs(RAMP);
const WARMUP_MS = durationMs(WARMUP);
const HOLD_MS = durationMs(HOLD);
const SOCKET_LIFE = seconds(RAMP_MS + WARMUP_MS + HOLD_MS);
const MEASURE_START = __ENV.SEND_DELAY || seconds(RAMP_MS + WARMUP_MS);

function durationMs(value) {
  const match = String(value).trim().match(/^(\d+)(ms|s|m)$/);
  if (!match) throw new Error(`Unsupported duration: ${value}`);
  const amount = Number(match[1]);
  return match[2] === 'ms' ? amount : match[2] === 's' ? amount * 1000 : amount * 60000;
}

function seconds(millis) {
  return `${millis / 1000}s`;
}

const delivery = new Trend('chat_delivery_ms', true);
const warmupDelivery = new Trend('chat_delivery_warmup_ms', true);
const received = new Counter('chat_messages_received');
const sent = new Counter('chat_messages_sent');
const warmupSent = new Counter('chat_warmup_sent');
const invalid = new Counter('chat_messages_invalid');

function uuid(block, index) {
  return `00000000-0000-4000-${block}-${String(index).padStart(12, '0')}`;
}

function token(user) {
  return `loadtoken-${String(user).padStart(6, '0')}-0123456789abcdef`;
}

// The sender stamps the phase into the content, so the receiving socket can attribute the sample
// without any shared clock between VUs: "t=<epoch>;p=w|m;<padding>".
function consumeEvent(body) {
  let envelope;
  try {
    envelope = JSON.parse(body);
  } catch (error) {
    invalid.add(1);
    return;
  }
  const payload = envelope.payload || envelope;
  if (payload.type !== 'MessageCreated' || !payload.message) return;
  const content = payload.message.content || '';
  const marker = content.indexOf('t=');
  const stale = content.indexOf(';');
  if (marker !== 0 || stale < 0) return;
  const created = Number(content.slice(2, stale));
  if (!Number.isFinite(created)) return;
  const phase = content.slice(stale + 1, stale + 4);
  if (phase === 'p=w') {
    warmupDelivery.add(Date.now() - created);
    return;
  }
  delivery.add(Date.now() - created);
  received.add(1);
}

export const options = {
  scenarios: {
    sockets: {
      executor: 'ramping-vus',
      exec: 'holdSocket',
      startVUs: 0,
      stages: [
        { duration: RAMP, target: SESSIONS },
        { duration: seconds(WARMUP_MS + HOLD_MS), target: SESSIONS },
        { duration: '10s', target: 0 },
      ],
      gracefulRampDown: '10s',
    },
    warmup: {
      executor: 'constant-arrival-rate',
      exec: 'sendWarmup',
      rate: RATE,
      timeUnit: '1s',
      duration: WARMUP,
      preAllocatedVUs: Math.max(4, Math.min(100, Math.ceil(RATE / 4))),
      maxVUs: Math.max(8, Math.min(200, RATE)),
      startTime: RAMP,
    },
    senders: {
      executor: 'constant-arrival-rate',
      exec: 'sendMessage',
      rate: RATE,
      timeUnit: '1s',
      duration: __ENV.SEND_DURATION || HOLD,
      preAllocatedVUs: Math.max(4, Math.min(100, Math.ceil(RATE / 4))),
      maxVUs: Math.max(8, Math.min(200, RATE)),
      startTime: MEASURE_START,
    },
  },
  thresholds: {
    chat_delivery_ms: ['p(95)<1000', 'p(99)<2000'],
    http_req_failed: ['rate<0.001'],
    checks: ['rate>0.999'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
};

export function holdSocket() {
  const user = ((__VU - 1) % USERS) + 1;
  const url = `${WS_BASE}/ws/v1`;
  const response = ws.connect(url, { headers: { Cookie: `CHAT_SESSION=${token(user)}` } }, (socket) => {
    socket.on('message', consumeEvent);
    socket.setTimeout(() => socket.close(), durationMs(SOCKET_LIFE));
  });
  check(response, { 'socket upgraded': (r) => r && r.status === 101 });
}

const senderState = {};

function sender(phase) {
  const key = phase === 'w' ? 'warmup' : 'measured';
  if (senderState[key]) return senderState[key];
  const pair = ((__VU - 1) % CONVERSATIONS) + 1;
  const user = pair * 2 - 1;
  const csrf = http.get(`${BASE}/api/v1/auth/csrf`, {
    headers: { Cookie: `CHAT_SESSION=${token(user)}` },
    tags: { name: 'csrf' },
  });
  const xsrf = csrf.cookies['XSRF-TOKEN'] ? csrf.cookies['XSRF-TOKEN'][0].value : '';
  senderState[key] = {
    conversationId: uuid('9000', pair),
    cookie: `CHAT_SESSION=${token(user)}; XSRF-TOKEN=${xsrf}`,
    xsrf,
  };
  return senderState[key];
}

function postMessage(phase) {
  const state = sender(phase);
  const body = JSON.stringify({
    content: `t=${Date.now()};p=${phase};${'x'.repeat(PADDING)}`,
  });
  const response = http.post(`${BASE}/api/v1/conversations/${state.conversationId}/messages`, body, {
    headers: {
      'Content-Type': 'application/json',
      Cookie: state.cookie,
      'X-XSRF-TOKEN': state.xsrf,
      'Idempotency-Key': `load-${phase}-${__VU}-${__ITER}-${Date.now()}`,
    },
    tags: { name: phase === 'w' ? 'warmup-message' : 'send-message' },
  });
  check(response, { 'message accepted': (r) => r.status === 201 });
  if (response.status !== 201) return;
  if (phase === 'w') warmupSent.add(1);
  else sent.add(1);
}

export function sendMessage() {
  postMessage('m');
}

export function sendWarmup() {
  postMessage('w');
}
