// Steady traffic THROUGH the proxy while a deploy runs (P16): every non-200 is a user-visible error.
// One machine = one client address behind the proxy: keep RATE under the storefront's per-address budget
// (300/min), or the rate limiter answers 429 (a first run did exactly that, and its smoke test failed too).
//   k6 run -e HOST=<store>.staging.storeforge.localhost -e PORT=18443 -e DURATION=3m load/k6/during-deploy.js
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const errors = new Counter('user_visible_errors');
const rateLimited = new Counter('rate_limited_429');
const HOST = __ENV.HOST;
export const options = {
  scenarios: { steady: { executor: 'constant-arrival-rate', rate: Number(__ENV.RATE || 4), timeUnit: '1s', duration: __ENV.DURATION || '3m',
    preAllocatedVUs: 20, maxVUs: 100 } },
  hosts: { [HOST]: '127.0.0.1' },
  insecureSkipTLSVerify: true,   // Caddy's local CA; the smoke test verifies TLS properly
  thresholds: { user_visible_errors: ['count==0'] },
};

export default function () {
  const r = http.get(`https://${HOST}:${__ENV.PORT}/storefront/v1/products?limit=5`, { timeout: '10s' });
  if (!check(r, { '200': (res) => res.status === 200 })) {
    errors.add(1, { status: String(r.status) });
    if (r.status === 429) rateLimited.add(1);
  }
}
