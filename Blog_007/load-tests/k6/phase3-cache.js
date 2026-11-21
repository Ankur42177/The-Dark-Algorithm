// Phase 3 load test: hot reads (lists + posts), the traffic a cache is for.
//   k6 run load-tests/k6/phase3-cache.js
//   k6 run -e BASE=http://localhost:8080 load-tests/k6/phase3-cache.js   (via the gateway, Phase 5+)
import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE || 'http://localhost:8081';
const SLUGS = ['rag-in-one-picture', 'kafka-consumer-groups-explained', 'arsenal-pressing-numbers',
  'expected-goals-for-beginners', 'what-is-an-ai-agent', 'why-keyset-pagination-beats-offset'];
const SECTIONS = ['TECH', 'FOOTBALL', 'AI'];

export const options = {
  vus: 50,                 // 50 virtual users hammering in parallel
  duration: '20s',
  thresholds: {            // the test FAILS if these aren't met (performance as a test, PLAN §14.4)
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<150'],
  },
};

export default function () {
  const r1 = http.get(`${BASE}/api/v1/posts?section=${SECTIONS[Math.floor(Math.random() * 3)]}&limit=10`, { tags: { name: 'list' } });
  const r2 = http.get(`${BASE}/api/v1/posts/${SLUGS[Math.floor(Math.random() * SLUGS.length)]}`, { tags: { name: 'post' } });
  check(r1, { 'list 200': (r) => r.status === 200 });
  check(r2, { 'post 200': (r) => r.status === 200 });
}
