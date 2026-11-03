# OrderOps Complete Product Implementation Plan

> **For agentic workers:** Use the parallel-agent workflow for disjoint module ownership; execute each module with TDD and a final whole-project review. No repeated milestone permission prompts.

**Goal:** Finish the agreed OrderOps portfolio application, remaining implementation tickets and reproducible deployment materials.

**Architecture:** Two independent Spring Boot applications exchange durable events through Kafka. PostgreSQL transactions protect business state; a separate carrier simulator exercises idempotency and failure recovery; React provides the operator interface.

**Tech Stack:** Java21, SpringBoot3.5.15, Spring Kafka, PostgreSQL17, React/TypeScript/esbuild, Node built-ins for simulator, Docker, AWS deployment templates.

**Spec:** `.scratch/orderops/spec.md`; exact seams: `.scratch/orderops/implementation-contract.md`.

## Global constraints

- Preserve existing files and tests; build in the user-selected project on feat/foundation.
- Scope and implementation contracts are authoritative; no payments, cancellation or delivery tracking.
- Never claim skipped or blocked integration checks passed.
- Tests cover behavior: HTTP, real storage/transport integration, worker policy and browser interactions.
- Only Kafka-prefixed adapter source files depend on Kafka classes; offline verification explicitly excludes them when dependencies are unavailable.
- No actual cloud provisioning without account access; prepare everything independently first.

## Review focus

- Same-key parallel submissions cannot create duplicate orders or consume stock twice.
- Unknown SKUs and last-line failures roll back earlier stock changes.
- Publish-before-mark crashes and repeated delivery cannot duplicate shipments.
- Timeout-after-creation and lease expiry reuse the stable carrier key.
- Older generation failures cannot overwrite a newer success.
- UI uncertain responses retain the request key; role/CSRF restrictions are enforced server-side.

## Task 1: Order API and event projection

Owner: services/order-service only. Read the integration contract for exact HTTP/event shapes.

- [x] Extend order/shipping domain and migrations; verify validation and serialization at HTTP seam.
- [x] Test canonical request identity and persisted idempotency under concurrent submissions; implement transaction serialization and replay.
- [x] Add paginated order reads, timeline and authenticated session endpoint.
- [x] Test valid/invalid retry transitions, generation increments and retry idempotency; implement API.
- [x] Add transactional result deduplication, stale-generation protection and Kafka outbox/listener adapters.
- [x] Run module tests with offline profile if necessary; author real PostgreSQL/Kafka integration tests without false skips.

## Task 2: Durable fulfillment and carrier recovery

Owner: services/fulfillment-service only.

- [x] Test job transitions, bounded retries, stable carrier keys and stale result handling.
- [x] Implement service-owned PostgreSQL schema, transactional command consumption and deduplication.
- [x] Implement lease-based job claim/finish with HTTP outside transactions and durable backoff.
- [x] Emit durable success/failure results; implement Kafka adapters and dead-letter behavior.
- [x] Run domain/HTTP tests; author transport/storage restart/duplicate scenarios for CI.

## Task 3: Operator dashboard

Owner: ../frontend only.

- [x] Implement typed session/CSRF/API client and request-key retention; test these behaviors.
- [x] Build login, responsive overview, create order, inventory, order timeline and recovery controls.
- [x] Add errors/empty/loading states, viewer experience and polling that preserves input.
- [x] Add browser component-journey tests and real-stack E2E scenario.
- [x] Run typecheck/build/UI tests where tooling is available; provide nginx/Docker assets.

## Task 4: Simulator, integration and delivery

Owner: coordinator; root/infrastructure/docs/contracts only while other owners run.

- [x] Test a persistent carrier simulator for normal, fail-then-recover, timeout-after-creation and repeated-key conflicts.
- [x] Add root messaging/offline build profiles without altering production behavior to fake verification.
- [x] Complete Compose/Kafka provisioning, CI verification, application images and browser integration pipeline.
- [x] Add AWS infrastructure/deployment templates, HTTPS, rollback/backup runbook and prerequisites.
- [x] Update OpenAPI/events, Postman collection, requirement-to-evidence mapping and demo guide.
- [x] Integrate modules; run feasible local checks, self-review, fix material findings and update ticket evidence. Independent review and external release acceptance remain limited as recorded below.

## Execution record

Historical evidence lives in `.scratch/orderops/completion-progress.md`. Current source completion and external acceptance blockers are in `.scratch/orderops/release-completion.md`. Checkmarks above mean implementation/source tasks are delivered; hosted CI, live recording, cloud deployment and latest full-stack acceptance are still pending.
