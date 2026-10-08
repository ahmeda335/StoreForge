-- EXP-018 seed: run with  psql -v store=<store uuid> -f exp-018-seed.sql  (as superuser, against a disposable database)
\timing on
-- 200k products in the measured store, 100k spread over the "noise" of the other tenants' rows (store ids reused from existing stores).
WITH words AS (SELECT ARRAY['Brass','Oak','Linen','Velvet','Steel','Ceramic','Walnut','Glass','Wool','Rattan','Copper','Marble','Cotton','Leather','Bamboo','Concrete','Matte','Glossy','Vintage','Modern'] AS adj,
                      ARRAY['Desk','Floor','Table','Wall','Pendant','Garden','Kitchen','Office','Travel','Kids','Studio','Outdoor','Bedside','Reading','Accent'] AS kind,
                      ARRAY['Lamp','Lampshade','Chair','Table','Rug','Vase','Shelf','Mirror','Clock','Bench','Stool','Sofa','Cushion','Throw','Basket','Planter','Candle','Tray','Bowl','Frame',
                            'Cabinet','Dresser','Desk','Ottoman','Lantern','Sconce','Curtain','Blanket','Mug','Teapot','Kettle','Jar','Bin','Hook','Rack','Stand','Cart','Crate','Box','Bottle'] AS noun,
                      ARRAY['Lumen','Nordwood','Atelier','Hearth','Kiln','Loom','Forge','Grove','Harbor','Summit'] AS vendor,
                      ARRAY['Lighting','Furniture','Decor','Textiles','Kitchen','Storage'] AS ptype)
INSERT INTO storeforge.products (id, store_id, handle, title, description, vendor, product_type, status, option_names, created_at, updated_at, version)
SELECT gen_random_uuid(), :'store'::uuid, 'p-' || g, adj[1 + g % 20] || ' ' || kind[1 + (g / 20) % 15] || ' ' || noun[1 + (g / 300) % 40] || ' ' || g,
       'Handmade ' || lower(noun[1 + (g / 7) % 40]) || ' in ' || lower(adj[1 + (g / 3) % 20]) || ' finish. Ships in 2-3 days.',
       vendor[1 + g % 10], ptype[1 + g % 6], CASE WHEN g % 10 = 0 THEN 'DRAFT' ELSE 'ACTIVE' END, '{}', now() - (g || ' seconds')::interval, now(), 0
  FROM words, generate_series(1, 200000) g;
INSERT INTO storeforge.product_variants (id, store_id, product_id, sku, price_minor, position, created_at, updated_at)
SELECT gen_random_uuid(), p.store_id, p.id, NULL, 500 + (abs(hashtext(p.handle)) % 50000), 0, now(), now()
  FROM storeforge.products p WHERE p.store_id = :'store'::uuid;
-- Other tenants: 100k more products in 50 other stores (the index serves everyone).
INSERT INTO storeforge.stores (id, handle, name, currency, status, created_by, created_at, updated_at, version)
SELECT gen_random_uuid(), 'noise-' || g || '-' || substr(md5(random()::text), 1, 6), 'Noise ' || g, 'USD', 'ACTIVE',
       (SELECT created_by FROM storeforge.stores WHERE id = :'store'::uuid), now(), now(), 0 FROM generate_series(1, 50) g;
INSERT INTO storeforge.products (id, store_id, handle, title, description, vendor, product_type, status, option_names, created_at, updated_at, version)
SELECT gen_random_uuid(), s.id, 'n-' || g, 'Noise Lamp Chair ' || g, 'noise', 'Other', 'Decor', 'ACTIVE', '{}', now(), now(), 0
  FROM (SELECT id, row_number() OVER () AS n FROM storeforge.stores WHERE handle LIKE 'noise-%') s, generate_series(1, 2000) g;
ANALYZE storeforge.products; ANALYZE storeforge.product_variants;
SELECT count(*) AS products_total, count(*) FILTER (WHERE store_id = :'store'::uuid) AS in_measured_store FROM storeforge.products;
