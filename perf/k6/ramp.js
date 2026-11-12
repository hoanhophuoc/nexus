import http from 'k6/http';
import ws from 'k6/ws';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';

// Staged ramp used to find the load ceiling rather than to prove a target.
//
// PLAN is a comma-separated list of "targetVUs:rampSeconds:holdSeconds". Every stage ramps to
// its target and holds, so each stage holds a steady session count long enough to report a
// per-stage p95/p99. Delivery latency is read on the receiving socket, and the stage is derived
// from the shared wall clock (k6 and the server run on one host), so no per-vu clock is needed.
//
//   PLAN=10000:90:120,14000:60:90,18000:60:90,22000:60:90
//   RUN_START_MS=<epoch ms at k6 launch> USERS=22000 RATE=100
const BASE = __ENV.BASE_URL || 'http://127.0.0.1:8080';
const WS_BASE = __ENV.WS_URL || BASE.replace('http', 'ws');
const RATE = parseInt(__ENV.RATE || '100', 10);
const USERS = parseInt(__ENV.USERS || '22000', 10);
const PADDING = parseInt(__ENV.MSG_BYTES || '80', 10);
const RUN_START = parseInt(__ENV.RUN_START_MS || '0', 10);
const PLAN = __ENV.PLAN || '10000:90:120,14000:60:90,18000:60:90,22000:60:90';
const CONVERSATIONS = Math.floor(USERS / 2);

const stages = PLAN.split(',').map((entry) => {
  const [target, rampSeconds, holdSeconds] = entry.split(':').map(Number);
  return { target, rampMs: rampSeconds * 1000, holdMs: holdSeconds * 1000 };
});

// Window per stage, in milliseconds from RUN_START, and the k6 stages that produce them.
const windows = [];
let cursorMs = 0;
const k6Stages = [];
for (const stage of stages) {
  windows.push({ start: cursorMs, end: cursorMs + stage.rampMs + stage.holdMs, target: stage.target });
  cursorMs += stage.rampMs + stage.holdMs;
  k6Stages.push({ duration: `${stage.rampMs / 1000}s`, target: stage.target });
  k6Stages.push({ duration: `${stage.holdMs / 1000}s`, target: stage.target });
}
const TOTAL_MS = cursorMs;
const SOCKET_LIFE_MS = TOTAL_MS + 60000;

const deliveryByStage = stages.map((_, index) => new Trend(`nexus_delivery_stage${index}_ms`, true));
const sentByStage = stages.map((_, index) => new Counter(`nexus_sent_stage${index}`));
const delivery = new Trend('nexus_delivery_ms', true);
const received = new Counter('nexus_messages_received');
const sent = new Counter('nexus_messages_sent');
const rejected = new Counter('nexus_sockets_rejected');
const invalid = new Counter('nexus_messages_invalid');

function stageIndex(epochMs) {
  const elapsed = epochMs - RUN_START;
  for (let i = 0; i < windows.length; i += 1) {
    if (elapsed < windows[i].end) return i;
  }
  return windows.length - 1;
}

function uuid(block, index) {
  return `00000000-0000-4000-${block}-${String(index).padStart(12, '0')}`;
}

function token(user) {
  return `loadtoken-${String(user).padStart(6, '0')}-0123456789abcdef`;
}

// Sender stamp: "t=<epoch ms>;x<padding>". The stage is derived at receive time from the shared
// clock, because a VU's own iteration counter says nothing about how many sessions are open.
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
  const separator = content.indexOf(';');
  if (marker !== 0 || separator < 0) return;
  const created = Number(content.slice(2, separator));
  if (!Number.isFinite(created)) return;
  const now = Date.now();
  const latency = now - created;
  delivery.add(latency);
  deliveryByStage[stageIndex(now)].add(latency);
  received.add(1);
}

export const options = {
  discardResponseBodies: true,
  scenarios: {
    sockets: {
      executor: 'ramping-vus',
      exec: 'holdSocket',
      startVUs: 0,
      stages: k6Stages.concat([{ duration: '20s', target: 0 }]),
      gracefulRampDown: '20s',
    },
    senders: {
      executor: 'constant-arrival-rate',
      exec: 'sendMessage',
      rate: RATE,
      timeUnit: '1s',
      duration: `${Math.ceil(TOTAL_MS / 1000)}s`,
      preAllocatedVUs: Math.max(8, Math.min(200, Math.ceil(RATE / 4))),
      maxVUs: Math.max(16, Math.min(400, RATE * 2)),
    },
  },
  // No thresholds: this run is looking for the ceiling, so a bad stage must not abort the run.
  thresholds: {},
  summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max', 'count'],
};

export function holdSocket() {
  const user = ((__VU - 1) % USERS) + 1;
  const response = ws.connect(`${WS_BASE}/ws/v1`, { headers: { Cookie: `NEXUS_SESSION=${token(user)}` } }, (socket) => {
    socket.on('message', consumeEvent);
    socket.setTimeout(() => socket.close(), SOCKET_LIFE_MS);
  });
  const upgraded = response && response.status === 101;
  if (!upgraded) rejected.add(1);
  check(response, { 'socket upgraded': () => upgraded });
}

const senderState = {};

function sender() {
  if (senderState.ready) return senderState.ready;
  const pair = ((__VU - 1) % CONVERSATIONS) + 1;
  const user = pair * 2 - 1;
  const csrf = http.get(`${BASE}/api/v1/auth/csrf`, {
    headers: { Cookie: `NEXUS_SESSION=${token(user)}` },
    tags: { name: 'csrf' },
  });
  const xsrf = csrf.cookies['XSRF-TOKEN'] ? csrf.cookies['XSRF-TOKEN'][0].value : '';
  senderState.ready = {
    conversationId: uuid('9000', pair),
    cookie: `NEXUS_SESSION=${token(user)}; XSRF-TOKEN=${xsrf}`,
    xsrf,
  };
  return senderState.ready;
}

export function sendMessage() {
  const state = sender();
  const now = Date.now();
  const stage = stageIndex(now);
  const body = JSON.stringify({ content: `t=${now};x${'x'.repeat(PADDING)}` });
  const response = http.post(`${BASE}/api/v1/conversations/${state.conversationId}/messages`, body, {
    headers: {
      'Content-Type': 'application/json',
      Cookie: state.cookie,
      'X-XSRF-TOKEN': state.xsrf,
      'Idempotency-Key': `ramp-${stage}-${__VU}-${__ITER}-${now}`,
    },
    tags: { name: 'send-message' },
  });
  check(response, { 'message accepted': (r) => r.status === 201 });
  if (response.status !== 201) return;
  sent.add(1);
  sentByStage[stage].add(1);
}
