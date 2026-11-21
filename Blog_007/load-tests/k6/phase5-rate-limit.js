// Phase 5: prove the gateway's rate limit. One client (one IP) sends 80 quick requests to a public route.
// Expected with default limits (60/min, burst 60): ~60 × 200, then 429 Too Many Requests with Retry-After.
//   k6 run load-tests/k6/phase5-rate-limit.js
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const BASE = __ENV.BASE || 'http://localhost:8080';
const ok = new Counter('status_200');
const limited = new Counter('status_429');

export const options = { vus: 1, iterations: 80 };

export default function () {
  const r = http.get(`${BASE}/api/v1/sections`);
  if (r.status === 200) ok.add(1);
  if (r.status === 429) {
    limited.add(1);
    check(r, {
      '429 has Retry-After': (x) => !!x.headers['Retry-After'],
      '429 is Problem Details': (x) => (x.headers['Content-Type'] || '').includes('application/problem+json'),
    });
  }
}
