# 01 - Create and inspect a stock-backed order

Status: resolved
Blocked by: None
Progress: implementation written; runtime verification complete

**What to build:** An authenticated operator can inspect inventory, create a confirmed order, and retrieve it. The reservation and outgoing event persist together. A viewer can inspect but cannot write.

- [x] Two Java applications have independent executable packaging.
- [x] Domain rules and authenticated HTTP contracts have passing tests.
- [x] Stock reservation uses sorted row locks and a single order transaction.
- [x] Migrations and PostgreSQL-backed rollback/concurrency tests exist.
- [x] PostgreSQL integration tests pass against the disposable test instance.
- [x] Both application images are built and smoke-tested.

## Comments

The initial Windows sandbox cannot start Docker or initialize native PostgreSQL. This blocked database/image runtime verification, not the domain/MVC test suite.

## Verification (2026-11-02)

Docker and Maven Central restored. `mvn verify` passes 83 tests including real PostgreSQL/Kafka integration; full Compose stack builds and both applications are healthy. Runtime verification is now complete.
