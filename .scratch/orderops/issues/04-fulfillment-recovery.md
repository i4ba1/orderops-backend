# 04 - Recover a failed shipment without duplication

Status: resolved
Progress: product behavior implemented; current release acceptance tracked separately
Blocked by: 03

**What to build:** An operator can understand a carrier failure and safely request recovery after automated retries are exhausted.

- [x] Persist attempt counts, next retry times and failure summaries.
- [x] Retry transient failures with bounded backoff, outside database transactions.
- [x] Reuse the carrier key after timeout-after-creation and prove only one shipment exists.
- [x] Duplicate events and service restarts do not duplicate jobs or shipments.
- [x] Terminal failure retains the stock reservation and becomes visible in the timeline.
- [x] Operator retry increments the attempt generation; old failures cannot overwrite a newer success.
- [x] Poison events enter a dead-letter topic with an inspectable reason.
- [x] Recovery and stale-event scenarios have integration coverage.

## Reconciled evidence — 2026-11-03

Implementation and automated coverage are present. The 2026-11-02 record documents real PostgreSQL/Kafka and full-stack success before the layout/logging changes. Current Java core/HTTP tests and frontend client/browser checks pass; a fresh distributed release run remains required. See `../release-completion.md` for current counts and blockers. Checkmarks describe delivered behavior and coverage, not live AWS acceptance.
