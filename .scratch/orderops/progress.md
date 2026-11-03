# OrderOps foundation progress

Plan: docs/superpowers/plans/2026-11-02-orderops-foundation.md

## Scope

The user approved the proposed architecture and asked to continue. Implement the first foundation milestone in the selected `orderops` directory. Later product milestones remain explicit tickets.

## Decisions and environment

- Work directly in the new, user-selected project directory; there is no existing Git repository or branch to isolate from.
- The installed Maven launcher fails with the inherited MAVEN_OPTS. Clearing that variable for the child build fixes `mvn -version`; global settings are untouched.
- Installed Java 21 is available even though PATH resolves Java 25. Use Java 21 for this project.
- Shell access to Maven Central is unavailable. Resolve dependencies from the existing user Maven cache through a task-local file mirror and writable local repository if needed.
- Docker Desktop was launched hidden, but the default engine pipe is not yet available. PostgreSQL integration verification remains conditional on a usable engine; tests will not silently skip.

## Progress

- Architecture, glossary, specification and foundation plan written from the agreed conversation.
- Independent builder check: no material unanswered questions block the first foundation milestone.
- Ruling: retain Spring Boot 3.5.15 and pin Failsafe 3.5.4, which is available locally. Testing an earlier Boot patch revealed more missing artifacts; do not mix or downgrade application framework patches to fit the cache.
- Ruling: publish a static OpenAPI contract and minimal health endpoints in the foundation; defer optional Swagger UI and Actuator dependencies because their required artifacts are unavailable in the restricted cache. Security tests will exercise real session login without an extra security-test library. This defers framework metrics until the observability milestone.
- Ruling: defer the unused Kafka dependency until the messaging ticket; the fulfillment foundation exposes health only, as specified.
- Native PostgreSQL initialization was also attempted inside `.cache`, but Windows sandbox token creation failed (error 87). No existing database was modified.
- Task 1: domain RED observed as missing implementation compilation errors; GREEN: 11 tests passed with no failures or skips.
- Task 2: initial HTTP RED observed as missing application types; first GREEN: 20 reactor tests passed. Follow-up login contract RED: expected 204/401 but received browser redirects, then implemented explicit API-friendly login handlers.
- Ruling: reserve and validate inventory before persisting order rows; this preserves clean unknown-SKU errors before foreign-key checks can mask them. All writes remain inside the order transaction.
- Ruling: integration tests use only a disposable Testcontainers database; no environment-selected database cleanup path is exposed.
- Task 3: Compose configuration validation passed without needing the Docker daemon.
- Remaining product work published as seven local vertical-slice tickets; ticket 01 remains pending database/image runtime verification.
- Session contract GREEN: 22 reactor tests passed, zero failures/errors/skips.
- Full `verify` attempted: Order Service unit/MVC tests passed and its executable JAR was packaged; Failsafe execution then failed to resolve its transitive artifacts from the restricted cache. Integration tests did not start. Docker startup is independently unavailable.
- Independent code review: one Important finding (fractional integer coercion), no Critical findings. Regression RED observed for 1.9, 1000.9 and string "2" returning 201 instead of 400; strict numeric parsing added.
- Final: minor (deferred): strengthen PostgreSQL contention test to explicitly hold the first transaction's inventory lock while a second transaction attempts reservation. Current start barrier can be serialized by scheduling.
- Final: minor (deferred): extend unknown-SKU rollback integration test to include an earlier valid SKU and assert unchanged stock/reservations. Existing insufficient-stock regression covers multi-line rollback.
- Ruling: retain the explicit PostgreSQL/runtime verification limitation; static review and MVC tests cannot establish actual migration or database lock behavior. Explicitly deferred Kafka, recovery, UI and AWS work remains in its tickets.
- Initialized a local Git repository on `feat/foundation` in the selected project directory. No commit, push or cloud action performed.
- Final: fixed fractional/string quantity coercion with `quantitiesMustBeJsonIntegersWithoutCoercion` RED (three accepted invalid requests) -> GREEN; complete reactor package run passed 25 tests, zero failures/errors/skips. Both executable JARs were produced.
- Final: Compose configuration and PostgreSQL initialization shell syntax validated. Fulfillment executable JAR was started on loopback port 18081 and returned the expected HTTP health response. Its task-owned JVM was stopped afterward.
- Final: background smoke-process launch was rejected by the environment's disabled approval category; a tool-managed foreground session completed the same check without escalation.
- Foundation is implemented but not fully runtime-verified: PostgreSQL migrations, actual transactional/concurrent behavior, container builds and CI execution remain unverified. These remain unchecked in the plan and first ticket.
