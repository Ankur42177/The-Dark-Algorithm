// Phase 5: what does the extra hop cost? Same hot reads, direct vs through the gateway.
//   k6 run -e BASE=http://localhost:8081 load-tests/k6/phase5-latency.js   (direct to content-service)
//   k6 run -e BASE=http://localhost:8080 load-tests/k6/phase5-latency.js   (via the gateway; start it with
//        PULSE_RATE_LIMIT_MULTIPLIER=100000 so one test IP isn't throttled but Redis is still called)
import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE || 'http://localhost:8080';
export const options = { vus: 50, duration: '15s' };

export default function () {
  const r = http.get(`${BASE}/api/v1/posts?section=TECH&limit=10`);
  check(r, { 'status 200': (x) => x.status === 200 });
}
