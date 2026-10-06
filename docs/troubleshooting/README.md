# Troubleshooting

| Problem | Cause | Fix |
|---|---|---|
| `./mvnw verify` fails with `Could not find a valid Docker environment` | Docker daemon not running / no permission | Start Docker; ensure your user can run `docker ps` |
| App fails at startup: `FATAL: password authentication failed for user "storeforge_owner"` | Compose volume created with other passwords, or a `.env` mismatch | Align `.env` with the volume, or `docker compose down -v` to re-provision (deletes data) |
| App fails at startup: `role "storeforge_app" does not exist` | The volume existed before the init script was added; init scripts only run on an empty data dir | `docker compose down -v && docker compose up -d` |
| `bind: address already in use` on 5432 | Another PostgreSQL on the host | `POSTGRES_PORT=5433 docker compose up -d` and `DB_URL=jdbc:postgresql://localhost:5433/storeforge` |
| `permission denied for schema storeforge` from the app | Expected for DDL: the app role cannot change the schema | Put schema changes in a Flyway migration |
| Logs are unreadable JSON | Structured logging is on by design (same format in every environment) | Pipe through `jq`, see [local-development.md](../infrastructure/local-development.md#reading-logs) |
| All access tokens return 401 after restarting the app | Ephemeral signing key (no `AUTH_SIGNING_JWK`): a new key per start | Log in again, or configure a persistent key (local-development.md) |
| `403 cross_site_request_rejected` on refresh/logout | Missing `X-Requested-With: XMLHttpRequest`, or `Origin` not in `AUTH_ALLOWED_ORIGINS` | Send the header; add the origin |
| `429 rate_limited` while testing by hand | 10 attempts/min per email, 30/min per IP | Wait (see `Retry-After`) or raise `AUTH_RATE_LIMIT_*` locally |
| Login always `401` though the password is right | Account temporarily locked after 5 failures | Wait (1 min, doubling) or reset the password |
| No verification email | Mailpit not running, or SMTP failure (WARN log "Identity email could not be delivered") | `docker compose up -d mailpit`; request a new link via `POST /auth/v1/email-verifications` |
| A query on a tenant table returns no rows although data exists | Running outside `TenantTransactions.inStore` (no tenant context → RLS hides everything) | Wrap the work in `tenant.inStore(storeId, ...)` |
| `IllegalStateException: Transaction is already bound to another store` | Nested `inStore` for a different store inside one transaction | Use separate transactions per store |
| `new row violates row-level security policy` | Writing a row whose `store_id` differs from the transaction's tenant | Bug: the row belongs to another store, or the context is wrong |
| `404 store_not_found` for a store you just created | Using another user's token, or the store id of another environment | Check `GET /admin/v1/stores` with the same token |
| Storefront returns `404 store_not_found` locally | The hostname doesn't map to a store (`localhost` alone is not a store) | Send `X-Forwarded-Host: <handle>.storeforge.localhost`, or browse `http://<handle>.storeforge.localhost:8080` |
| `412 precondition_failed` on a product write | Your `If-Match` ETag is stale (someone changed the product) | `GET` the product again, re-apply your change, send the new ETag |
| `invalid_product: Invalid amount` | More decimal places than the store currency allows, or not a number | Send `"19.99"` for USD, `"1500"` for JPY |
| Flyway fails on V5: `role "storeforge_tenant_scanner" does not exist` | Local volume provisioned before P5 (init scripts only run on an empty volume) | `docker compose down -v && docker compose up -d` (deletes local data) |
| `409 stock_below_reserved` | Trying to reduce on-hand below units reserved by open checkouts | Wait for checkouts to complete/expire, or adjust by less |
| Storefront shows `available: false` for a new product | Inventory never stocked (missing items count as 0) | Adjust stock (`RECEIVED`) or set `tracked: false` |
| Compile fails on a warning | `-Xlint:all -Werror` | Fix the warning, or suppress it narrowly with a justification comment |
