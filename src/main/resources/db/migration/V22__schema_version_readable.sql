-- P16: API and worker containers run WITHOUT the owner role and without running Flyway. They check at
-- startup that the schema is at least as new as their code (SchemaVersionGuard), which needs to read
-- the history table. It was created before V1's default privileges existed, hence this explicit grant.
GRANT SELECT ON flyway_schema_history TO ${app_role};
