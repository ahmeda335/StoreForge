// Steady customer traffic for failure experiments (P17), through the proxy:
// browse 3/s and complete ~6 checkouts/min (within one address's limits). Failures are counted by kind.
//   k6 run -e HOST=... -e PORT=18443 -e VARIANT=... -e DURATION=5m traffic.js
import http from 'k6/http';
import { Counter, Trend } from 'k6/metrics';

const HOST = __ENV.HOST;
const BASE = `https://${HOST}:${__ENV.PORT}`;
const failures = new Counter('failures');            // tagged with kind
const orders = new Counter('orders_placed');
const checkoutLatency = new Trend('checkout_total_ms', true);

export const options = {
  scenarios: {
    browse: { executor: 'constant-arrival-rate', exec: 'browse', rate: 3, timeUnit: '1s', duration: __ENV.DURATION || '5m', preAllocatedVUs: 10, maxVUs: 200 },
    checkout: { executor: 'constant-arrival-rate', exec: 'checkout', rate: 6, timeUnit: '1m', duration: __ENV.DURATION || '5m', preAllocatedVUs: 4, maxVUs: 50 },
  },
  hosts: { [HOST]: '127.0.0.1' },
  insecureSkipTLSVerify: true,
  summaryTrendStats: ['p(50)', 'p(95)', 'p(99)', 'max'],
};

function kind(status) {
  if (status === 0) return 'connection';
  if (status === 503) return 'unavailable_503';
  if (status === 429) return 'rate_limited_429';
  if (status >= 500) return `server_${status}`;
  return `client_${status}`;
}

function ok(r, expected, step) {
  if (r.status === expected) return true;
  failures.add(1, { kind: kind(r.status), step });
  return false;
}

export function browse() {
  ok(http.get(`${BASE}/storefront/v1/products?limit=5`, { timeout: '15s', tags: { name: 'browse' } }), 200, 'browse');
}

export function checkout() {
  const started = Date.now();
  const json = { headers: { 'Content-Type': 'application/json' }, timeout: '15s' };
  const cart = http.post(`${BASE}/storefront/v1/carts`, '', json);
  if (!ok(cart, 201, 'cart')) return;
  const auth = { headers: { 'Content-Type': 'application/json', 'X-Cart-Token': cart.json('cartToken') }, timeout: '15s' };
  if (!ok(http.post(`${BASE}/storefront/v1/carts/${cart.json('cartId')}/items`, JSON.stringify({ variantId: __ENV.VARIANT, quantity: 1 }), auth), 200, 'add')) return;
  const co = http.post(`${BASE}/storefront/v1/checkouts`, JSON.stringify({ cartId: cart.json('cartId'), email: 'chaos-buyer@chaos.test',
    shippingAddress: { name: 'Chaos', line1: '1 Test St', city: 'Testville', postalCode: '1000', country: 'US' } }), auth);
  if (!ok(co, 201, 'checkout')) return;
  const done = http.post(`${BASE}/storefront/v1/checkouts/${co.json('id')}/completion`, JSON.stringify({ expectedTotal: co.json('total.amount') }),
    { headers: Object.assign({ 'Idempotency-Key': `${co.json('id')}-k` }, auth.headers), timeout: '15s' });
  if (ok(done, 200, 'complete')) {
    orders.add(1);
    checkoutLatency.add(Date.now() - started);
  }
}

export function handleSummary(data) {
  const out = __ENV.OUT || '/load/results/chaos-latest.json';
  return { [out]: JSON.stringify(data) };
}
