-- Baseline: runs as the schema OWNER role (see infra/postgres/init/01-create-roles.sh).
--
-- The application connects at runtime as a different, non-owner role (${app_role}). That role may
-- read and write rows but cannot create, alter or drop objects. Two reasons:
--   1. Least privilege: an SQL injection or bug cannot DROP TABLE or ALTER schema.
--   2. Row-Level Security (Phase 3) is bypassed by table owners; tenant isolation only works if the
--      runtime role does not own the tables.
--
-- Default privileges make every table/sequence created by future migrations usable by the app
-- role automatically, so no migration can "forget" a GRANT.

ALTER DEFAULT PRIVILEGES IN SCHEMA storeforge
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO ${app_role};

ALTER DEFAULT PRIVILEGES IN SCHEMA storeforge
    GRANT USAGE, SELECT ON SEQUENCES TO ${app_role};

ALTER DEFAULT PRIVILEGES IN SCHEMA storeforge
    GRANT EXECUTE ON FUNCTIONS TO ${app_role};
