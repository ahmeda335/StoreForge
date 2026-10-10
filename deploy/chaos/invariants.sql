-- Data invariants after a failure experiment (P17). Every row returned is a violation.
-- Run as the superuser against the environment's database (bypasses RLS: sees all tenants).
SET search_path = storeforge;
SELECT 'order without PLACED event' AS violation, o.id::text AS detail FROM orders o
 WHERE NOT EXISTS (SELECT 1 FROM order_events e WHERE e.order_id = o.id AND e.type = 'PLACED')
UNION ALL
SELECT 'order without OrderPlaced outbox event (still unpublished or published)', o.id::text FROM orders o
 WHERE o.created_at > now() - interval '7 days'
   AND NOT EXISTS (SELECT 1 FROM outbox_events x WHERE x.aggregate_id = o.id::text AND x.event_type = 'OrderPlaced')
UNION ALL
SELECT 'completed checkout without order', c.id::text FROM checkouts c WHERE c.status = 'COMPLETED' AND c.order_id IS NULL
UNION ALL
SELECT 'order total identity broken', o.id::text FROM orders o WHERE o.total_minor <> o.subtotal_minor - o.discount_minor + o.shipping_minor + o.tax_minor
UNION ALL
SELECT 'inventory out of range', i.id::text FROM inventory_items i WHERE i.on_hand < 0 OR i.reserved < 0 OR i.reserved > i.on_hand
UNION ALL
SELECT 'reservation total differs from reserved', i.id::text FROM inventory_items i
 WHERE i.reserved <> coalesce((SELECT sum(r.quantity) FROM inventory_reservations r WHERE r.inventory_item_id = i.id AND r.status = 'ACTIVE'), 0)
UNION ALL
SELECT 'job stuck RUNNING past its lease (+5 min)', j.id::text FROM jobs j WHERE j.status = 'RUNNING' AND j.locked_until < now() - interval '5 minutes'
UNION ALL
SELECT 'outbox event unpublished for over 5 minutes', x.id::text FROM outbox_events x WHERE x.published_at IS NULL AND x.occurred_at < now() - interval '5 minutes';
