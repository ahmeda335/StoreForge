// StoreForge mixed workload (P15). Open model: requests ARRIVE at a fixed rate whatever the response
// times (constant-arrival-rate), like real traffic, so slowness shows up as latency and dropped
// iterations instead of silently lowering the load (coordinated omission).
//
//   k6 run -e SCALE=1 -e DURATION=3m -e OUT=load/results/<run> load/k6/mixed.js
//
// Base rates at SCALE=1 (iterations/s): browse 60, search 30, checkout 4 (≈6 requests each),
// admin 10, developer 10. Traffic is skewed like real tenants: the large store gets 40%.
import http from 'k6/http';
import { check } from 'k6';
import { SharedArray } from 'k6/data';
import exec from 'k6/execution';

const BASE = __ENV.BASE || 'http://localhost:8080';
const SCALE = Number(__ENV.SCALE || 1);
const DURATION = __ENV.DURATION || '3m';
const data = JSON.parse(open('../data/stores.json'));
const stores = new SharedArray('stores', () => data.stores);
const WORDS = ['lamp', 'chair', 'oak table', 'linen', 'vase', 'brass desk lamp', 'lampshde', 'walnut shelf', 'rug', 'velvet cushion'];

function rate(base) {
  return Math.max(1, Math.round(base * SCALE));
}

function scenario(fn, perSecond) {
  return {
    executor: 'constant-arrival-rate', exec: fn, rate: rate(perSecond), timeUnit: '1s', duration: DURATION,
    preAllocatedVUs: rate(perSecond) * 2, maxVUs: rate(perSecond) * 20,
  };
}

export const options = {
  scenarios: {
    browse: scenario('browse', 60),
    search: scenario('search', 30),
    checkout: scenario('checkout', 4),
    admin: scenario('admin', 10),
    developer: scenario('developer', 10),
  },
  // The SLOs (docs/performance/slos.md), as pass/fail thresholds of the run.
  thresholds: {
    'http_req_duration{api:storefront-read}': ['p(95)<200', 'p(99)<500'],
    'http_req_duration{name:checkout-complete}': ['p(95)<500'],
    'http_req_duration{api:admin}': ['p(95)<300'],
    'http_req_duration{api:developer}': ['p(95)<300'],
    'http_req_failed': ['rate<0.01'],
    'dropped_iterations': ['count<100'],
  },
  summaryTrendStats: ['avg', 'p(50)', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

// Owners log in once (each from its own address: login limits are per IP). Tokens last 15 min.
export function setup() {
  return stores.map((s, i) => {
    const r = http.post(`${BASE}/auth/v1/token`, JSON.stringify({ email: s.ownerEmail, password: data.password }),
      { headers: { 'Content-Type': 'application/json', 'X-Forwarded-For': `10.200.${i}.1` } });
    return r.json('access_token');
  });
}

function pickStore() {
  const r = Math.random();
  if (r < 0.4) return 0;                                   // the large store
  if (r < 0.8) return 1 + Math.floor(Math.random() * 9);   // medium
  return 10 + Math.floor(Math.random() * 40);              // small
}

function productCount(store) {
  return store.size === 'large' ? 20000 : store.size === 'medium' ? 2000 : 100;
}

function activeHandle(store) {
  let g;
  do { g = 1 + Math.floor(Math.random() * productCount(store)); } while (g % 20 === 0);   // every 20th is a draft
  return `p-${g}`;
}

// Every ITERATION is one shopper with its own address (believed from loopback, like a proxy would send).
// k6 reuses virtual users: an address per VU would make one "shopper" complete dozens of checkouts a
// minute and hit the per-address limits (seen in the first smoke run), which real shoppers don't.
const SCENARIO_OCTET = { browse: 1, search: 2, checkout: 3 };
function shopper(store, extra) {
  const n = exec.scenario.iterationInTest;
  const address = `10.${SCENARIO_OCTET[exec.scenario.name] || 9}${(n >> 16) % 10}.${(n >> 8) & 255}.${n & 255}`;
  return { headers: Object.assign({ 'X-Forwarded-Host': store.host, 'X-Forwarded-For': address, 'Content-Type': 'application/json' }, extra || {}) };
}

function tagged(params, api, name) {
  params.tags = { api, name };
  return params;
}

export function browse() {
  const store = stores[pickStore()];
  const list = http.get(`${BASE}/storefront/v1/products?limit=20`, tagged(shopper(store), 'storefront-read', 'product-list'));
  check(list, { 'list 200': (r) => r.status === 200 });
  if (Math.random() < 0.6) {
    const one = http.get(`${BASE}/storefront/v1/products/${activeHandle(store)}`, tagged(shopper(store), 'storefront-read', 'product-detail'));
    check(one, { 'detail 200': (r) => r.status === 200 });
  }
}

export function search() {
  const store = stores[pickStore()];
  let query = `q=${encodeURIComponent(WORDS[Math.floor(Math.random() * WORDS.length)])}`;
  if (Math.random() < 0.3) query += '&maxPrice=150';
  if (Math.random() < 0.15) query = 'sort=newest';                 // browsing without words
  const r = http.get(`${BASE}/storefront/v1/search?${query}`, tagged(shopper(store), 'storefront-read', 'search'));
  check(r, { 'search 200': (res) => res.status === 200 });
}

export function checkout() {
  const store = stores[pickStore()];
  const product = http.get(`${BASE}/storefront/v1/products/${activeHandle(store)}`, tagged(shopper(store), 'storefront-read', 'product-detail'));
  if (product.status !== 200) return;
  const cart = http.post(`${BASE}/storefront/v1/carts`, '', tagged(shopper(store), 'storefront-write', 'cart-create'));
  if (!check(cart, { 'cart 201': (r) => r.status === 201 })) return;
  const auth = { 'X-Cart-Token': cart.json('cartToken') };
  const cartId = cart.json('cartId');
  http.post(`${BASE}/storefront/v1/carts/${cartId}/items`, JSON.stringify({ variantId: product.json('variants.0.id'), quantity: 1 + Math.floor(Math.random() * 2) }),
    tagged(shopper(store, auth), 'storefront-write', 'cart-add'));
  const started = http.post(`${BASE}/storefront/v1/checkouts`, JSON.stringify({ cartId, email: `buyer-${exec.vu.idInTest}-${exec.scenario.iterationInTest}@load.test`,
    shippingAddress: { name: 'Load Buyer', line1: '1 Test St', city: 'Testville', postalCode: '1000', country: 'US' } }),
  tagged(shopper(store, auth), 'storefront-write', 'checkout-start'));
  if (!check(started, { 'checkout 201': (r) => r.status === 201 })) return;
  const id = started.json('id');
  const rate = started.json('shippingRates.0.id');
  const chosen = http.put(`${BASE}/storefront/v1/checkouts/${id}/shipping-rate`, JSON.stringify({ rateId: rate }),
    tagged(shopper(store, auth), 'storefront-write', 'checkout-shipping'));
  const done = http.post(`${BASE}/storefront/v1/checkouts/${id}/completion`, JSON.stringify({ expectedTotal: chosen.json('total.amount') }),
    tagged(shopper(store, Object.assign({ 'Idempotency-Key': `${id}-k` }, auth)), 'storefront-write', 'checkout-complete'));
  check(done, { 'completed 200': (r) => r.status === 200 });
}

export function admin(tokens) {
  const index = pickStore();
  const store = stores[index];
  const params = { headers: { Authorization: `Bearer ${tokens[index]}` } };
  const roll = Math.random();
  if (roll < 0.5) {
    const filter = Math.random() < 0.3 ? '&financialStatus=PAID' : Math.random() < 0.2 ? '&q=customer-1' : '';
    const list = http.get(`${BASE}/admin/v1/stores/${store.id}/orders?limit=50${filter}`, tagged(params, 'admin', 'admin-orders'));
    check(list, { 'orders 200': (r) => r.status === 200 });
    const first = list.status === 200 ? list.json('data.0.id') : null;
    if (first) http.get(`${BASE}/admin/v1/stores/${store.id}/orders/${first}`, tagged(params, 'admin', 'admin-order'));
  } else if (roll < 0.8) {
    const r = http.get(`${BASE}/admin/v1/stores/${store.id}/products?limit=50${Math.random() < 0.5 ? '&q=lamp' : ''}`, tagged(params, 'admin', 'admin-products'));
    check(r, { 'products 200': (res) => res.status === 200 });
  } else {
    const r = http.get(`${BASE}/admin/v1/stores/${store.id}/customers?limit=50`, tagged(params, 'admin', 'admin-customers'));
    check(r, { 'customers 200': (res) => res.status === 200 });
  }
}

// An integration polling the orders feed. Spread over all 50 keys: each key's budget is 2/s.
export function developer() {
  const store = stores[Math.floor(Math.random() * stores.length)];
  const since = new Date(Date.now() - 7 * 24 * 3600 * 1000).toISOString();
  const r = http.get(`${BASE}/api/2026-10/stores/${store.id}/orders?updatedAfter=${since}&limit=50`,
    tagged({ headers: { Authorization: `Bearer ${store.apiKey}` } }, 'developer', 'developer-orders'));
  check(r, { 'feed 200': (res) => res.status === 200 });
}

export function handleSummary(summary) {
  const out = __ENV.OUT || 'load/results/latest';
  return { [`${out}/summary.json`]: JSON.stringify(summary, null, 1), stdout: textSummary(summary) };
}

function textSummary(summary) {
  const rows = [];
  for (const [name, metric] of Object.entries(summary.metrics)) {
    if (name.startsWith('http_req_duration{') || name === 'http_req_duration') {
      const v = metric.values;
      rows.push(`${name.padEnd(48)} p50 ${v['p(50)'].toFixed(1).padStart(7)}  p95 ${v['p(95)'].toFixed(1).padStart(7)}  p99 ${v['p(99)'].toFixed(1).padStart(7)}  max ${v.max.toFixed(0).padStart(6)} ms`);
    }
  }
  const failed = summary.metrics.http_req_failed.values.rate;
  const reqs = summary.metrics.http_reqs.values;
  const dropped = summary.metrics.dropped_iterations ? summary.metrics.dropped_iterations.values.count : 0;
  const breached = Object.entries(summary.metrics).filter(([, m]) => m.thresholds && Object.values(m.thresholds).some((t) => !t.ok)).map(([n]) => n);
  return `\nrequests ${reqs.count} (${reqs.rate.toFixed(1)}/s), failed ${(failed * 100).toFixed(2)}%, dropped iterations ${dropped}\n`
    + rows.sort().join('\n') + `\nSLO thresholds breached: ${breached.length ? breached.join(', ') : 'none'}\n`;
}
