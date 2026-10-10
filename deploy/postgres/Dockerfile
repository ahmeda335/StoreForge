# PostgreSQL 17 + wal-g (continuous archiving to S3-compatible storage, P16 / ADR-031).
FROM postgres:17.10
ARG WALG_VERSION=v3.0.9
# Checksum from the release's .sha256 asset (verified at build)
ARG WALG_SHA256=444ce216230c254edef803e098a59a1746806da3e0d55671d7b0f0082db14ef4
ADD https://github.com/wal-g/wal-g/releases/download/${WALG_VERSION}/wal-g-pg-24.04-amd64.tar.gz /tmp/wal-g.tar.gz
RUN echo "${WALG_SHA256}  /tmp/wal-g.tar.gz" | sha256sum -c - \
 && tar -xzf /tmp/wal-g.tar.gz -C /tmp && install -m 0755 /tmp/wal-g-pg-24.04-amd64 /usr/local/bin/wal-g && rm -f /tmp/wal-g*
COPY --chmod=0755 walg-env.sh /usr/local/bin/walg-env
