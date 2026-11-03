# OrderOps operations

Use Compose ps/logs to inspect services. Local process health: ports8080/8081 /internal/health. Fulfillment aggregate /internal/metrics reports ready/running/failed jobs, pending result events and shipments; historical failed generations remain counted. Private carrier8090 /internal/stats reports shipment/request counts. Internal endpoints are not proxied through nginx.

Health proves HTTP availability, not business progress. If an order remains CONFIRMED, inspect its outgoing event, broker/topics, consumer and durable job. Duplicate publication after a publish-before-mark crash is expected; consumers deduplicate. Do not manually mark backlog records published to silence them. Invalid data goes to DLT only after acknowledged publication; infrastructure errors retry.

Use the operator recovery API after an exhausted carrier failure. It starts a new generation, preserving stock and carrier identity. Old failures cannot overwrite a new success. Expired worker leases are reclaimed; do not reset attempt counts to bypass bounds.

Published outbox rows, prior generations and consumed IDs are retained for investigation/recovery. Local topics retain seven days; a persistent environment needs explicit storage/retention planning. Keep deduplication identifiers at least as long as replayable sources. Simulator state is atomically replaced and persists across restart. Deleting volumes destroys correctness/recovery evidence; reset only a deliberately disposable environment.

Backups, rollback and restore are described in DEPLOYMENT.md. Sequential database dumps are not a distributed snapshot; restore requires reconciliation and safe replay. Infrastructure exercises remain pending.

Measure read/create latency, oldest job/outbox age, consumer lag and carrier latency under bounded load. No throughput or percentile values were measured here; no synthetic scaling claim is made.
