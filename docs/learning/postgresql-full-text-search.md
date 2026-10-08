# PostgreSQL Full-Text Search and Trigrams

**Problem.** `WHERE title ILIKE '%lamp%'` scans every row, can't rank, misses "lamps", "Lámp" and "lampshde", and gets slower with every product.

**Why we need it.** Storefront search (P13): words in any order, accents ignored, typos tolerated, ranked, filtered, with facet counts.

**Alternatives.**
- **`LIKE`/`ILIKE`:** no ranking; index only with trigram help.
- **Elasticsearch/OpenSearch:** best relevance features, but a second system, a sync pipeline and eventual consistency.
- **PostgreSQL `tsvector` + `pg_trgm`:** good enough for catalogs of thousands to low hundreds of thousands, transactional.

**Decision.** PostgreSQL ([ADR-025](../decisions/ADR-025-search-in-postgresql-and-no-cache.md)).

**Mental model.**
- **`tsvector`** is a sorted list of normalized words (lexemes) with positions; **`tsquery`** is a boolean expression of lexemes; `@@` matches them.
- **Text search configuration** decides normalization: parser → dictionaries. `english` stems ("lamps" → "lamp") and drops stop words; `simple` only lower-cases. Ours: `simple` + `unaccent` (product names aren't prose; stores are multilingual).
- **Weights A–D** label where a word came from; `ts_rank_cd` scores matches higher for A. Title A, vendor/type B, description C.
- **Generated stored column**: the vector can never drift from the row, and it's updated in the same transaction (no indexing lag).
- **`websearch_to_tsquery`** accepts what users type: `"brass lamp"`, `lamp or light`, `-shade`. Never build `to_tsquery` from raw input (syntax errors).
- **Trigrams** (`pg_trgm`) split strings into 3-letter pieces; similarity = shared pieces. `word_similarity` / `%>` finds "lampshde" in "Linen Lampshade". Full text can't: it matches whole lexemes.
- **GIN** indexes map each lexeme/trigram to rows. `btree_gin` lets one GIN index start with `store_id` too.
- **Immutability:** index expressions must be IMMUTABLE; `unaccent()` is only STABLE, hence a wrapper with an explicit dictionary.
- **OR of two GIN conditions** often becomes a bitmap OR or a scan; a UNION of two index scans is predictable.
- **Facets** = `GROUP BY` counts over the match set. *Disjunctive*: a facet's counts ignore its own filter, so other choices stay visible.

**Project usage.** V15 (`product_search` configuration, `search_normalize`, `search_vector`, indexes), V16 (`search_product_matches`), `ProductSearch`, `GET /storefront/v1/search`.

**Failure cases.**
- **RLS hides index use:** `@@` and `%>` aren't leakproof ([rls-and-leakproof-operators](rls-and-leakproof-operators.md), [EXP-018](../experiments/EXP-018-search-at-scale-under-rls.md)).
- **Generic plans** after 5 prepared executions can be far worse than custom ones.
- **Ranking everything:** a common word matches 13k rows, each ranked. Cost grows with matches, not with the page size.
- **Stemming the wrong language** merges unrelated words or misses obvious ones.

**Testing.** `ProductSearchIT`: weights (title before description), drafts hidden, accents both ways, a typo, facets and filters, tenant scoping, a rename found immediately. Scale: EXP-018's seed and load scripts.

**Observability.** `EXPLAIN (ANALYZE, BUFFERS)` **as the app role inside a tenant transaction**; `pg_stat_statements` for mean/max per statement; HTTP latency per route.

**When NOT to use it.** Synonyms, per-language analysis, relevance tuning, very large catalogs, search across tenants: use a search engine fed from the outbox.
