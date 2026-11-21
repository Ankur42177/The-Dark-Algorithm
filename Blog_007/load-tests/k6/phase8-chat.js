// Phase 8: chat under load. LLM-bound, so use FEW virtual users — this measures time-to-first-token and the
// answer cache / rate limits, not raw throughput.
//   k6 run load-tests/k6/phase8-chat.js                       (assistant-service directly, :8083)
//   k6 run -e BASE=http://localhost:8080 load-tests/k6/phase8-chat.js   (through the gateway: 10 msgs/min/IP!)
import http from 'k6/http';
import { check } from 'k6';
import { Trend } from 'k6/metrics';

const BASE = __ENV.BASE || 'http://localhost:8083';
const QUESTIONS = ["What's new in AI this week?", 'Explain Kafka consumer groups', 'What is expected goals?'];
const answerTime = new Trend('chat_full_answer_ms', true);

export const options = { vus: 3, iterations: 12, thresholds: { http_req_failed: ['rate<0.05'] } };

export default function () {
  const q = QUESTIONS[Math.floor(Math.random() * QUESTIONS.length)];
  const res = http.post(`${BASE}/api/v1/assistant/chat`, JSON.stringify({ message: q }),
    { headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' }, timeout: '60s' });
  answerTime.add(res.timings.duration);
  check(res, {
    'status 200': (r) => r.status === 200,
    'has done event': (r) => r.body.includes('event:done'),
    'cache or llm or search': (r) => /"mode":"(cache|llm|search)"/.test(r.body),
  });
}
