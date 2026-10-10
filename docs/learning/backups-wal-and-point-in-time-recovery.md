# Backups: WAL Archiving, Point-in-Time Recovery and Restore Drills

**Problem.** Disks fail, operators run the wrong `DELETE`, ransomware encrypts volumes. A backup nobody has restored is a hope, not a backup.

**Why we need it.** P16: production data (orders, payments) must survive the host.

**Alternatives.**
- **Nightly `pg_dump`:** simple, but loses up to a day and restores slowly for large databases.
- **Volume snapshots:** fast, but crash-consistent and tied to the host or cloud.
- **Continuous archiving (WAL + base backups):** any point in time.
- **Managed PITR:** the same, operated for you.

**Decision.** wal-g: base backups plus every WAL segment to object storage, and a restore drill script ([ADR-031](../decisions/ADR-031-single-host-production-and-delivery.md)).

**Mental model.**
- PostgreSQL writes every change to the **WAL** before the data files. A **base backup** (a consistent copy of the data files) plus all WAL after it can rebuild the database at any moment covered by the WAL: **point-in-time recovery** (`recovery_target_time`).
- **RPO** (data you can lose) is about the WAL segments not yet archived: `archive_timeout` 60 s bounds it. **RTO** (time to be back) is fetching the base backup plus replaying WAL: base backups more often mean less WAL to replay.
- **Store backups somewhere else** (another bucket, account or region) with **separate credentials** that the application doesn't have. Here a storage identity can only touch the backups bucket.
- **Drill:** restore next to production, compare (counts, a recent known row), time it. Schedule it, because restores break silently (credentials rotated, format changed, bucket policy changed).

**Project usage.** `deploy/postgres` (wal-g), `archive_command`, `deploy/backup.sh`, `deploy/restore-drill.sh`, [runbook](../operations/runbooks/deploy.md).

**Failure cases.**
- Backups on the same disk;
- `archive_command` failing silently (watch `pg_stat_archiver.failed_count`);
- no base backup for weeks (replay takes hours);
- never-tested restores;
- application credentials able to delete backups.

**Testing.** The drill wrote data *after* the base backup and found it restored (so WAL replay works), with identical counts, in 10 s ([EXP-024](../experiments/EXP-024-production-like-delivery.md)).

**Observability.**
- `pg_stat_archiver` (archived and failed counts, last archived time);
- the age of the newest base backup (open item: a metric and alert);
- drill results recorded each time.

**When NOT to use it.** On a managed database, use its PITR, and still run the drill: the restore procedure and the credentials are yours.
