#!/usr/bin/env bash
# Starts the app jar against the load database with a FIXED JVM configuration, so runs are comparable.
# Extra JVM flags (e.g. the async-profiler agent) can be passed in JAVA_EXTRA.
set -euo pipefail
cd "$(dirname "$0")/.."
export DB_URL=jdbc:postgresql://localhost:5432/storeforge_load
export DB_POOL_SIZE=${DB_POOL_SIZE:-10}
export ORDER_PAYMENT_WINDOW=${ORDER_PAYMENT_WINDOW:-2h}       # unpaid load-test orders don't expire mid-run
export FAKE_PROVIDER_WEBHOOK_URL=                             # no webhook loop-back during load
exec java -Xms1g -Xmx1g -XX:+UseG1GC ${JAVA_EXTRA:-} -jar target/storeforge-0.1.0-SNAPSHOT.jar
