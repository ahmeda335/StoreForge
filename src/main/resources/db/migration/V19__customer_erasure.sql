-- Phase 14: customer data erasure (privacy). The customer row stays (orders reference it) but its
-- personal data is replaced; erased_at records when.
ALTER TABLE customers ADD COLUMN erased_at timestamptz;
