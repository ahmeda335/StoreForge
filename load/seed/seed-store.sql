-- Bulk history for ONE load-test store (psql -v store=<uuid> -v products=N -v customers=N -v orders=N).
-- Runs as the superuser (bypasses RLS) against storeforge_load ONLY. Every row satisfies the same
-- constraints the application relies on, so the app reads it like its own data.
\set ON_ERROR_STOP on
SET search_path = storeforge;
\timing on
BEGIN;

-- Catalog: realistic titles (the search experiments' vocabulary), one variant each, deep stock.
CREATE TEMP TABLE w AS SELECT
  ARRAY['Brass','Oak','Linen','Velvet','Steel','Ceramic','Walnut','Glass','Wool','Rattan','Copper','Marble','Cotton','Leather','Bamboo','Concrete','Matte','Glossy','Vintage','Modern'] AS adj,
  ARRAY['Desk','Floor','Table','Wall','Pendant','Garden','Kitchen','Office','Travel','Kids','Studio','Outdoor','Bedside','Reading','Accent'] AS kind,
  ARRAY['Lamp','Lampshade','Chair','Table','Rug','Vase','Shelf','Mirror','Clock','Bench','Stool','Sofa','Cushion','Throw','Basket','Planter','Candle','Tray','Bowl','Frame'] AS noun,
  ARRAY['Lumen','Nordwood','Atelier','Hearth','Kiln','Loom','Forge','Grove','Harbor','Summit'] AS vendor,
  ARRAY['Lighting','Furniture','Decor','Textiles','Kitchen','Storage'] AS ptype;

CREATE TEMP TABLE p AS
SELECT gen_random_uuid() AS id, g, w.adj[1 + g % 20] || ' ' || w.kind[1 + (g / 20) % 15] || ' ' || w.noun[1 + (g / 300) % 20] || ' ' || g AS title,
       w.vendor[1 + g % 10] AS vendor, w.ptype[1 + g % 6] AS ptype, 500 + (g * 7919) % 30000 AS price
  FROM w, generate_series(1, :products) g;
ANALYZE p;

INSERT INTO products (id, store_id, handle, title, description, vendor, product_type, status, option_names, created_at, updated_at, version)
SELECT id, CAST(:'store' AS uuid), 'p-' || g, title, '<p>Handmade in small batches. Ships in 2-3 days.</p>', vendor, ptype,
       CASE WHEN g % 20 = 0 THEN 'DRAFT' ELSE 'ACTIVE' END, '{}', now() - (g || ' minutes')::interval, now(), 0
  FROM p;
CREATE TEMP TABLE v AS SELECT gen_random_uuid() AS id, p.id AS product_id, p.g, p.title, p.price FROM p;
CREATE INDEX ON v (g);
ANALYZE v;
INSERT INTO product_variants (id, store_id, product_id, sku, price_minor, position, weight_grams, created_at, updated_at)
SELECT id, CAST(:'store' AS uuid), product_id, 'SKU-' || g, price, 0, 300 + g % 1500, now(), now() FROM v;
INSERT INTO inventory_items (id, store_id, variant_id, on_hand, reserved, tracked, created_at, updated_at)
SELECT gen_random_uuid(), CAST(:'store' AS uuid), id, 1000000, 0, true, now(), now() FROM v;

-- Customers.
CREATE TEMP TABLE c AS SELECT gen_random_uuid() AS id, g, 'customer-' || g || '-' || substr(md5(:'store' || g), 1, 6) || '@load.test' AS email
  FROM generate_series(1, :customers) g;
CREATE INDEX ON c (g);
ANALYZE c;
INSERT INTO customers (id, store_id, email, first_name, last_name, created_at, updated_at)
SELECT id, CAST(:'store' AS uuid), email, 'First' || g, 'Last' || g, now() - interval '400 days', now() FROM c;

-- Temp tables are never auto-analyzed: without ANALYZE the planner nested-loops 200k x 200k (seen in P15).
-- Order history over the last year: 1-3 lines, 95% paid + fulfilled + closed, 5% cancelled unpaid.
CREATE TEMP TABLE o AS
SELECT gen_random_uuid() AS id, gen_random_uuid() AS checkout_id, gen_random_uuid() AS cart_id, n,
       (SELECT id FROM c WHERE c.g = 1 + (n * 7) % :customers) AS customer_id,
       now() - ((n::bigint * 31536000 / :orders) || ' seconds')::interval AS at, n % 20 = 0 AS cancelled, 1 + n % 3 AS line_count
  FROM generate_series(1, :orders) n;
ANALYZE o;
CREATE TEMP TABLE l AS
SELECT o.id AS order_id, o.checkout_id, v.id AS variant_id, v.title, v.price, 1 + (o.n + k) % 2 AS qty
  FROM o CROSS JOIN LATERAL generate_series(1, o.line_count) k
  JOIN v ON v.g = 1 + (o.n * 13 + k * 101) % :products;
ANALYZE l;
CREATE TEMP TABLE t AS SELECT order_id, sum(price * qty) AS subtotal FROM l GROUP BY order_id;
ANALYZE t;

INSERT INTO carts (id, store_id, token_hash, status, expires_at, created_at, updated_at)
SELECT cart_id, CAST(:'store' AS uuid), md5(cart_id::text) || md5(id::text), 'CONVERTED', at + interval '30 days', at, at FROM o;
INSERT INTO checkouts (id, store_id, cart_id, status, email, ship_name, ship_line1, ship_city, ship_postal_code, ship_country, customer_id,
                       currency, subtotal_minor, shipping_minor, tax_minor, total_minor, discount_minor, completed_at, created_at, updated_at)
SELECT o.checkout_id, CAST(:'store' AS uuid), o.cart_id, 'COMPLETED', c.email, 'Customer ' || c.g, '1 Load St', 'Testville', '1000', 'US', o.customer_id,
       'USD', t.subtotal, 490, 0, t.subtotal + 490, 0, o.at, o.at, o.at
  FROM o JOIN t ON t.order_id = o.id JOIN c ON c.id = o.customer_id;
INSERT INTO checkout_lines (store_id, checkout_id, variant_id, product_title, variant_title, sku, unit_price_minor, quantity, line_total_minor)
SELECT CAST(:'store' AS uuid), checkout_id, variant_id, title, 'Default', NULL, price, qty, price * qty FROM l;
INSERT INTO orders (id, store_id, number, checkout_id, customer_id, email, currency, subtotal_minor, shipping_minor, tax_minor, total_minor,
                    discount_minor, status, financial_status, fulfillment_status, ship_name, ship_line1, ship_city, ship_postal_code,
                    ship_country, shipping_rate_name, payment_due_at, paid_at, fulfilled_at, cancelled_at, cancel_reason, created_at, updated_at, version)
SELECT o.id, CAST(:'store' AS uuid), 1000 + o.n, o.checkout_id, o.customer_id, c.email, 'USD', t.subtotal, 490, 0, t.subtotal + 490, 0,
       CASE WHEN o.cancelled THEN 'CANCELLED' ELSE 'CLOSED' END, CASE WHEN o.cancelled THEN 'VOIDED' ELSE 'PAID' END,
       CASE WHEN o.cancelled THEN 'UNFULFILLED' ELSE 'FULFILLED' END, 'Customer ' || c.g, '1 Load St', 'Testville', '1000', 'US', 'Standard',
       o.at + interval '15 minutes', CASE WHEN o.cancelled THEN NULL ELSE o.at + interval '2 minutes' END,
       CASE WHEN o.cancelled THEN NULL ELSE o.at + interval '1 day' END, CASE WHEN o.cancelled THEN o.at + interval '15 minutes' END,
       CASE WHEN o.cancelled THEN 'PAYMENT_TIMEOUT' END, o.at, o.at + interval '1 day', 2
  FROM o JOIN t ON t.order_id = o.id JOIN c ON c.id = o.customer_id;
UPDATE checkouts SET order_id = o.id FROM o WHERE checkouts.store_id = CAST(:'store' AS uuid) AND checkouts.id = o.checkout_id;
INSERT INTO order_items (store_id, order_id, variant_id, product_title, variant_title, sku, unit_price_minor, quantity, line_total_minor)
SELECT CAST(:'store' AS uuid), order_id, variant_id, title, 'Default', NULL, price, qty, price * qty FROM l;
INSERT INTO order_events (id, store_id, order_id, type, created_at)
SELECT gen_random_uuid(), CAST(:'store' AS uuid), id, 'PLACED', at FROM o
UNION ALL SELECT gen_random_uuid(), CAST(:'store' AS uuid), id, 'PAID', at + interval '2 minutes' FROM o WHERE NOT cancelled
UNION ALL SELECT gen_random_uuid(), CAST(:'store' AS uuid), id, 'FULFILLED', at + interval '1 day' FROM o WHERE NOT cancelled
UNION ALL SELECT gen_random_uuid(), CAST(:'store' AS uuid), id, 'CANCELLED', at + interval '15 minutes' FROM o WHERE cancelled;
UPDATE customers SET orders_count = s.n, total_spent_minor = s.spent
  FROM (SELECT o.customer_id, count(*) AS n, sum(t.subtotal + 490) AS spent FROM o JOIN t ON t.order_id = o.id WHERE NOT o.cancelled GROUP BY 1) s
 WHERE customers.store_id = CAST(:'store' AS uuid) AND customers.id = s.customer_id;
INSERT INTO store_counters (store_id, next_order_number) VALUES (CAST(:'store' AS uuid), 1001 + :orders)
ON CONFLICT (store_id) DO UPDATE SET next_order_number = excluded.next_order_number;
COMMIT;
