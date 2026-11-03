# 05 - Operate orders and recovery from a phone or desktop

Status: resolved
Progress: product behavior implemented; current release acceptance tracked separately
Blocked by: 02, 04

**What to build:** A responsive React interface lets an operator submit orders, inspect their progress and recover failures, while viewers have read-only access.

- [x] Session login and CSRF handling follow the API contract.
- [x] Order creation sends an idempotency key and handles uncertain responses safely.
- [x] Inventory, paginated order listing, order detail and timeline screens handle loading, empty and error states.
- [x] Operator-only recovery controls reflect allowed server transitions.
- [x] Polling updates fulfillment progress without discarding operator input.
- [x] Mobile and desktop browser E2E tests cover creation and recovery.

## Reconciled evidence — 2026-11-03

Implementation and automated coverage are present. The 2026-11-02 record documents real PostgreSQL/Kafka and full-stack success before the layout/logging changes. Current Java core/HTTP tests and frontend client/browser checks pass; a fresh distributed release run remains required. See `../release-completion.md` for current counts and blockers. Checkmarks describe delivered behavior and coverage, not live AWS acceptance.
