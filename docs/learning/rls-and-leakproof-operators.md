# Row-Level Security and Index Use (LEAKPROOF)

**Problem.** A query that is fast as superuser is 10–100× slower as the application role, on the same data, with the same indexes.

**Why we need it.** Every tenant table is under forced RLS (ADR-002/009). Any query whose selective condition uses a non-leakproof operator is affected: full text, trigram, JSON operators, many functions.

**Alternatives.**
- **Accept it:** fine while tables are small.
- **Disable RLS on the table:** loses the safety net for every query.
- **Mark operators LEAKPROOF:** superuser only, and a lie unless the function truly can't reveal its arguments (e.g. through error messages).
- **A SECURITY DEFINER function for the matching step**, returning only ids: what we did.

**Decision.** A narrow definer function, scoped by the tenant setting, returning ids and scores only ([ADR-025](../decisions/ADR-025-search-in-postgresql-and-no-cache.md)).

**Mental model.**
- RLS adds the policy (`store_id = current_store_id()`) as a **security barrier**: user conditions may only run before it if they can't leak information about rows the policy would hide.
- A function can leak by **throwing an error that contains its input**, or through timing. Only functions marked `LEAKPROOF` are trusted. Equality on `uuid`, `int`, `text` is; `@@`, `%>`, `ILIKE`, `->>`… mostly aren't.
- Consequence: a non-leakproof condition is evaluated **after** the policy, as a filter, so it **can't be an index condition**. With a composite GIN `(store_id, search_vector)`, only `store_id` drives the scan, then every row of the store is filtered.
- A definer function runs as its owner. If the owner bypasses RLS, the planner can use the index; the function must then enforce tenancy itself. Never take the tenant as a parameter: read it from the same setting the policies use.

**Project usage.** `search_product_matches` (V16). The older definer functions (ADR-011) return store ids only.

**Failure cases.**
- `EXPLAIN` as superuser looks perfect (superuser bypasses RLS).
- A definer function with a `store_id` parameter: any caller of the app role could ask about another tenant.
- Returning row data from the function: it bypasses RLS for everything returned.

**Testing.** `RowLevelSecurityIT.theSearchFunctionOnlyMatchesTheTransactionsOwnStore` (two stores with the same word; outside a tenant transaction, nothing), mutation-checked.

**Observability.** `EXPLAIN` after `SET ROLE storeforge_app` and `set_config('app.current_store_id', …, true)`; look for `Rows Removed by Filter` next to an index scan on `store_id` alone. `SELECT proleakproof FROM pg_proc …` tells you which operators are trusted.

**When NOT to use it.** If the slow condition is leakproof (equality, ranges on plain types), the problem is elsewhere: statistics, missing index, or a generic plan.
