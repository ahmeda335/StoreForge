#!/usr/bin/env bash
# Downloads async-profiler (pinned version) into load/tools/async-profiler (gitignored) and checks
# the archive against the checksum recorded here when the version was chosen.
set -euo pipefail
cd "$(dirname "$0")"
VERSION=4.5
SHA256="${ASYNC_PROFILER_SHA256:-89546fbb9ee0fc5496c7edd4099b0709489bc78b0d8057ccbb4b801f6b032b62}"   # recorded 2026-10-09
ARCHIVE="async-profiler-${VERSION}-linux-x64.tar.gz"
curl -sSfL -o "$ARCHIVE" "https://github.com/async-profiler/async-profiler/releases/download/v${VERSION}/${ARCHIVE}"
if [ -n "$SHA256" ]; then echo "$SHA256  $ARCHIVE" | sha256sum -c -; else sha256sum "$ARCHIVE"; fi
rm -rf async-profiler && mkdir async-profiler && tar -xzf "$ARCHIVE" -C async-profiler --strip-components=1 && rm "$ARCHIVE"
echo "async-profiler $VERSION in load/tools/async-profiler (agent: lib/libasyncProfiler.so)"
