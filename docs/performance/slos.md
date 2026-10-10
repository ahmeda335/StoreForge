# Service level objectives (P15)

What "fast enough" and "reliable enough" mean for StoreForge, per kind of traffic. The k6 mix
(`load/k6/mixed.js`) enforces them as pass/fail thresholds; the alert rules
([alerting](alerting.md)) watch them in production as error-budget burn rates.

| SLI (what we measure) | Objective | Why this number |
|---|---|---|
| Storefront reads (`/storefront/v1/products`, `/products/{handle}`, `/search`): latency | p95 < 200 ms, p99 < 500 ms | Shoppers abandon slow pages; reads are the bulk of traffic and must stay well under the browser's own rendering time |
| Checkout completion (`POST …/completion`): latency | p95 < 500 ms | One request per order, does the most work (locks, reservations, order, email, events); still under a second at p95 |
| Admin API (`/admin/v1/**`): latency | p95 < 300 ms | Merchants work in lists and detail pages all day |
| Developer API (`/api/**`): latency | p95 < 300 ms | Integrations poll feeds; slow responses multiply into backlogs |
| All APIs: availability | 99.9 % of requests not 5xx (rate limiting 429 and client errors excluded) over 30 days | ~43 min of errors per month: one bad deploy, not a bad week |

**Error budget:** 0.1 % of requests per 30 days. Spending it fast (burn rate) pages; spending it slowly opens a ticket.

**Load model (what the objectives must hold under):** the k6 mix at `SCALE=1`:
- **storefront reads:** 60 browse/s, of which 60 % also open a product detail; 30 search/s;
- **checkouts:** 4 complete checkouts/s (~6 requests each);
- **admin:** 10 requests/s;
- **developer:** 10 polls/s.

That's roughly 160 requests/s against 50 tenants and 400,000 historical orders, with 40 % of traffic on the largest store ([seed](../../load/README.md)). Capacity is the highest `SCALE` that still meets every objective ([EXP-023](../experiments/EXP-023-load-baseline.md)).
