# OrderOps specification

Status: implementation complete locally; release verification and cloud delivery pending

## Problem Statement

Retail operators need to confirm orders without overselling stock, see shipment creation progress, and recover carrier failures without creating duplicate shipments. The portfolio must make these behaviors inspectable by Jagaad reviewers.

## Solution

A responsive operator dashboard backed by two independently deployable Java applications: Order Service and Fulfillment Service. Order Service owns orders, inventory, reservations and the customer-facing timeline. Fulfillment owns shipping jobs, carrier interactions and shipment records. Kafka carries commands and facts between the applications.

## User Stories

1. As an operator, I want to inspect available stock before placing an order.
2. As an operator, I want to create an order with one or more SKUs and quantities.
3. As an operator, I want confirmation only when all requested stock can be reserved.
4. As an operator, I want invalid quantities and unknown SKUs rejected with actionable errors.
5. As an operator, I want a failed multi-item order to leave all stock unchanged.
6. As an operator, I want concurrent orders to avoid selling the same final item twice.
7. As an operator, I want repeated submissions with the same request key to return the same order.
8. As an operator, I want conflicting reuse of a request key rejected.
9. As an operator, I want to inspect an order and its fulfillment timeline.
10. As an operator, I want confirmed orders processed even after a service restart.
11. As an operator, I want carrier timeouts retried without duplicate shipments.
12. As an operator, I want exhausted retries visible as fulfillment failures.
13. As an operator, I want to request another attempt after a recoverable failure.
14. As a viewer, I want read access without permission to create orders or request retries.
15. As a reviewer, I want reproducible tests, documented endpoints and a one-command local environment.
16. As a reviewer, I want an explanation of design tradeoffs and deployment limitations.

## Implementation Decisions

- Workspace with independent `backend` and `frontend` Git projects. Backend Maven modules contain Order Service and Fulfillment Service; frontend contains React/TypeScript and esbuild tooling.
- Java 21 source and runtime target; Spring Boot 3.5.15, a locally cached stable patch suitable for this initial foundation. Dependency upgrades remain a separate checked change.
- Feature-oriented packages keep controllers, application services, repositories and models close together.
- PostgreSQL with Flyway migrations; separate service databases and credentials may share one server locally.
- Inventory stays with orders so reservations and order creation share one transaction. Lock SKU rows in sorted order for multi-item requests.
- The initial order contract accepts a customer reference and 1-20 unique SKU lines, with quantities between 1 and 1000. Shipping details will be added with the carrier integration before fulfillment goes live.
- Initial HTTP endpoints: create an order, retrieve an order, list inventory, obtain a CSRF token, and inspect health.
- Stock availability never becomes negative. A database constraint backs application checks.
- Order statuses are CONFIRMED, SHIPMENT_CREATED and FULFILLMENT_FAILED. Retry transitions return failed fulfillment to CONFIRMED. Shipment creation does not imply dispatch.
- Use a transactional outbox for outgoing events; publishers run inside their owning applications. Event envelopes carry event ID, order ID, event type, schema version, occurrence time and payload.
- Consumers persist deduplication and business effects together before committing offsets. Fulfillment first persists a durable job; the worker calls the carrier outside database transactions.
- A stable carrier idempotency key protects shipment creation across ambiguous timeouts. A simulator implements that contract.
- Retry delays and attempt counts live in durable jobs. Poison events use a dead-letter topic. Manual retries carry a distinct attempt generation so old failure events cannot overwrite newer results.
- Viewer/operator authentication uses server-side sessions, CSRF protection and same-origin deployment. Initial demo users are configured through environment variables with defaults limited to the local profile.
- The first milestone implements creation/retrieval, transactional stock reservation, outbox persistence and authentication. Kafka publishing, fulfillment processing, API idempotency, UI and AWS deployment are explicitly subsequent tickets.

## Testing Decisions

- Test observable behavior at the HTTP boundary with real Spring services, PostgreSQL and migrations. Do not substitute an in-memory database for PostgreSQL concurrency tests.
- Unit-test domain rules with JUnit; introduce mocks only at external boundaries.
- Testcontainers integration tests run under Maven's integration-test/verify phases and require Docker. They must fail, rather than silently skip, when requested without a usable engine.
- Cover invalid/duplicate SKUs, boundary quantities, authorization, CSRF, rollback and concurrent stock contention.
- Later tests exercise Kafka restarts, duplicate delivery, poison events, carrier timeout-after-creation, and stale result events.
- Browser E2E tests cover the eventual operator recovery journey. No application tests exist before this work.

## Out of Scope

Payments, shopping carts, multiple retailers, multiple warehouses, cancellation, stock release, physical dispatch, delivery tracking, Kubernetes and high availability are excluded from the initial release.

## Further Notes

The user has one week and up to 14 hours per day available. Each daily milestone should remain runnable. A single Linux EC2 host is the initial AWS demo target and is explicitly a single point of failure. CI verifies code and packages images; cloud deployment requires credentials and is not performed by scaffolding.
