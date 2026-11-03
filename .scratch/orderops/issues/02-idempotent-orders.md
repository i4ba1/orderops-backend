# 02 - Safely repeat an order submission

Status: resolved
Progress: product behavior implemented; current release acceptance tracked separately
Blocked by: 01

**What to build:** An operator can retry the same order request after losing a response without creating another order or reserving stock twice.

- [x] Require an idempotency key on order creation and scope keys to the authenticated identity.
- [x] Identical retries return the original order; conflicting reuse returns 409.
- [x] Concurrent submissions using the same key create one order, reservation set and outbox event.
- [x] Define canonical request comparison so line ordering cannot bypass the intended identity rules.
- [x] HTTP and PostgreSQL concurrency tests demonstrate the behavior.
- [x] Update the API contract and executable demo.

## Reconciled evidence — 2026-11-03

Implementation and automated coverage are present. The 2026-11-02 record documents real PostgreSQL/Kafka and full-stack success before the layout/logging changes. Current Java core/HTTP tests and frontend client/browser checks pass; a fresh distributed release run remains required. See `../release-completion.md` for current counts and blockers. Checkmarks describe delivered behavior and coverage, not live AWS acceptance.
