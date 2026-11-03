# 03 - Turn a confirmed order into a shipment

Status: resolved
Progress: product behavior implemented; current release acceptance tracked separately
Blocked by: 01

**What to build:** A confirmed order reaches Fulfillment through Kafka and produces a simulated carrier shipment whose result appears on the order.

- [x] Collect and validate shipping details before accepting shipment-ready orders.
- [x] Version event contracts and explicitly handle foundation records that lack shipping details.
- [x] Publish outgoing events from durable outboxes using the order ID as partition key.
- [x] Persist consumed-event deduplication and a durable shipping job before acknowledging an incoming event.
- [x] Carrier simulator accepts a stable idempotency key and returns a shipment identifier.
- [x] Shipment creation and the result outbox record commit together.
- [x] Order Service consumes the result and records SHIPMENT_CREATED plus a timeline entry.
- [x] Real Kafka/PostgreSQL tests and an API demonstration prove the complete journey.

## Reconciled evidence — 2026-11-03

Implementation and automated coverage are present. The 2026-11-02 record documents real PostgreSQL/Kafka and full-stack success before the layout/logging changes. Current Java core/HTTP tests and frontend client/browser checks pass; a fresh distributed release run remains required. See `../release-completion.md` for current counts and blockers. Checkmarks describe delivered behavior and coverage, not live AWS acceptance.
