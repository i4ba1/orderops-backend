# OrderOps Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver the agreed project structure and a working, authenticated create/read order flow with atomic stock reservations and durable outgoing events.

**Architecture:** Two Maven modules with feature-oriented packages. The Order Service implements the first vertical slice on PostgreSQL. Fulfillment boots and reports health; its processing arrives in subsequent tickets.

**Tech Stack:** Java 21, Spring Boot 3.5.15, Maven, Spring MVC/Data JPA/Security, Flyway, PostgreSQL, JUnit, Testcontainers.

**Spec:** `.scratch/orderops/spec.md`; architecture: `docs/superpowers/specs/2026-11-02-orderops-design.md`.

## Global Constraints

- Java 21 source and runtime target.
- One week available; this plan covers the foundation milestone only.
- Preserve Postman instructions and the selected local Markdown tracker.
- Separate service ownership; no shared JPA entities or database reads.
- Keep secrets out of source; local profile alone provides demo defaults.
- Integration tests require real PostgreSQL and fail rather than silently skip when requested without Docker.

## Review Focus

- Duplicate SKU lines must be rejected before changing stock.
- Failure on the last line must roll back earlier reservations and the order/outbox.
- Concurrent buyers of the final unit must yield one accepted order and one conflict.
- Viewer writes and missing CSRF tokens must be rejected.
- Unknown IDs and malformed input must return useful HTTP errors without leaking internal exceptions.

## File structure

- Root `pom.xml`: parent and reactor; `.gitignore`, `.editorconfig`: workspace conventions.
- `services/order-service`: boot application; `order`, `inventory`, `messaging`, `config`, `error` packages; Flyway resources.
- `services/fulfillment-service`: boot application and health configuration only.
- `infra/compose.yaml`: independent local PostgreSQL databases and service credentials.
- `services/order-service/src/test`: domain tests and PostgreSQL-backed HTTP integration tests.
- `README.md`, `contracts/openapi.yaml`, `.github/workflows/verify.yml`: reproducibility, API contract and CI.

## Task 1: Build and executable domain rules

**Files:** root/module POMs; `inventory/InventoryItem.java`; `order/Order.java`, `OrderItem.java`, `OrderStatus.java`; matching domain tests.

**Interfaces:** InventoryItem.reserve(int) validates and decrements availability; Order.confirm(String, List<OrderItem>) creates a confirmed order and rejects duplicate SKUs.

- [x] Write domain tests proving invalid quantities, insufficient stock, exact stock depletion, and duplicate lines are rejected correctly.
- [x] Run Maven tests and observe missing domain implementation failures.
- [x] Implement only the domain rules and JPA mapping required by these tests.
- [x] Run the reactor unit tests; expect zero failures.

## Task 2: Authenticated transactional order API

**Files:** OrderController, OrderApplicationService, OrderRepository, request/response DTOs; InventoryService, InventoryRepository, InventoryController; OutboxEvent/Repository; SecurityConfig; GlobalExceptionHandler; resources and migrations; `OrderApiIT.java`.

**Interfaces:** POST `/api/orders` accepts customerReference and items; GET `/api/orders/{id}` returns the saved order; GET `/api/inventory` lists stock; GET `/api/csrf` returns the session CSRF token. Create returns 201 and Location; validation returns 400, unknown entities 404, insufficient stock 409, unauthorized writes 403.

- [x] Write PostgreSQL-backed HTTP tests for successful creation/retrieval and inventory depletion, rollback, invalid/duplicate lines, unknown IDs, CSRF and viewer restrictions, and concurrent final-unit contention.
- [ ] Run integration tests and observe missing application behavior; infrastructure errors are recorded separately and are not a behavioral RED result.
- [x] Implement the HTTP-to-database path, sorted row locks and outbox insert in one transaction.
- [ ] Run unit and integration tests; expect zero failures when Docker is available.

## Task 3: Runnable second application and developer environment

**Files:** FulfillmentServiceApplication and application properties; `infra/compose.yaml`; `.env.example`; root launcher scripts if local Maven settings require isolation.

**Interfaces:** Fulfillment health on port 8081; Order Service on 8080; PostgreSQL local port 5433. Database credentials remain service-specific.

- [x] Package both executable jars and validate Compose configuration.
- [ ] Start dependencies and check Order Service endpoints and Fulfillment health if Docker is usable.
- [x] Record any infrastructure limitation without claiming unexecuted checks passed.

## Task 4: Reviewer-facing contracts and verification

**Files:** README, OpenAPI contract, CI workflow, local tracker issues and progress ledger.

**Interfaces:** `mvn verify` is the full verification command; CI provides Java 21 and Docker. Unit tests run with `mvn test`.

- [x] Document run commands, local credentials, CSRF usage, limitations, implemented versus planned features, and verification evidence.
- [x] Define remaining vertical-slice tickets for idempotency, Kafka fulfillment, recovery, UI and cloud deployment.
- [x] Review all code and run the full feasible verification commands once more after fixes.
- [x] Leave files reviewable in the user's chosen project directory; no remote publishing or cloud deployment.
