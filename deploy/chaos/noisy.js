// One client hammering one store (P17, experiment noisy-tenant): RATE requests/s from a single address.
import http from 'k6/http';
import { Counter } from 'k6/metrics';
const statuses = new Counter('noisy_status');
export const options = {
  scenarios: { flood: { executor: 'constant-arrival-rate', rate: Number(__ENV.RATE || 200), timeUnit: '1s', duration: __ENV.DURATION || '2m',
    preAllocatedVUs: 50, maxVUs: 400 } },
  hosts: { [__ENV.HOST]: __ENV.PROXY },
  insecureSkipTLSVerify: true,
};
export default function () {
  const r = http.get(`https://${__ENV.HOST}/storefront/v1/search?q=lamp`, { timeout: '10s' });
  statuses.add(1, { status: String(r.status) });
}
