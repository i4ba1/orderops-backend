# OrderOps implementation contract

User authorization: implement the agreed product through completion without milestone permission prompts. Live provisioning requires available account access. This contract fixes integration seams so Order Service, Fulfillment and React can be implemented independently.

## Ownership

- Orders implementer owns only `services/order-service/**` and Order-related tests.
- Fulfillment implementer owns only `services/fulfillment-service/**` and Fulfillment-related tests.
- Frontend implementer owns only `../frontend/**`; the former `web` directory is obsolete.
- Coordinator owns root POM, infrastructure, simulator, contracts, docs, CI and final integration.
- Do not spawn additional agents or commit shared files while other workers are running.

## Stack and restricted verification

Java 21, Spring Boot 3.5.15, Jackson 2, PostgreSQL/Flyway, Spring Kafka 3.3.16 (Boot managed). Root will add a default `messaging` Maven profile with Spring Kafka. A separate `offline` profile excludes files named `Kafka*.java` and disables the messaging dependency, allowing domain/MVC testing from the available cache. All broker-dependent adapter files/classes must start with Kafka. Do not refer to Kafka-prefixed types from core domain or MVC classes. Offline checks do not establish broker integration.

Windows Maven commands: set JAVA_HOME to installed Java21 and clear the malformed inherited MAVEN_OPTS in the child process; use `--settings .cache/maven-settings.xml`. Agents should run only their own Maven module, with `-Poffline`. Coordinator runs the final reactor. Do not skip or claim PostgreSQL/Kafka integration checks passed if infrastructure is unavailable.

## HTTP contract

Same-origin sessions; GET `/api/csrf` before login and again after successful login. POST `/login` form fields username/password plus CSRF header -> 204; bad credentials 401. POST `/logout` with CSRF -> 204. GET `/api/session` -> `{username,role}` where role is `OPERATOR` or `VIEWER`.

GET `/api/inventory` -> existing array of `{sku,name,availableQuantity}`.

POST `/api/orders` requires `Idempotency-Key` (8-100 ASCII letters/digits/underscore/hyphen). Body:

```json
{"customerReference":"DEMO-1","shippingAddress":{"recipient":"Ada Lovelace","line1":"10 Example Street","city":"London","postalCode":"SW1A 1AA","country":"GB"},"demoScenario":"NORMAL","items":[{"sku":"COFFEE","quantity":2}]}
```

- Address fields required: recipient max120, line1 max200, city max100, postalCode max32, country exactly two uppercase letters. Nonblank strings; SKU/quantity existing rules retained.
- demoScenario defaults NORMAL and accepts NORMAL, FAIL_THEN_RECOVER, TIMEOUT_AFTER_CREATE.
- New order -> 201; identical replay -> 200; changed payload under the same actor/key -> 409. Canonicalize SKU order; compare all address/customer/scenario fields. Key scope includes actor and operation. Key claim and order/reservation/outbox must be one transaction; serialize same-key concurrent requests with PostgreSQL transaction advisory locks or equivalent sound mechanism.
- Response Order: `{id,customerReference,status,createdAt,shippingAddress,demoScenario,attemptGeneration,shipmentId,trackingNumber,items,timeline}`. Status enum CONFIRMED, SHIPMENT_CREATED, FULFILLMENT_FAILED. Initial generation 1. Nullable shipment fields. Timeline entries: `{id,type,message,occurredAt}`.
- GET `/api/orders/{id}` -> Order; GET `/api/orders?page=0&size=20` -> `{content:Order[],number,size,totalElements,totalPages}` newest first. Optional status filter can be implemented if useful, but web must not require it.
- POST `/api/orders/{id}/retry`, required Idempotency-Key and CSRF, operator only -> 202 for new retry, 200 for replay. Only FULFILLMENT_FAILED can transition to CONFIRMED; increment generation and append RetryRequested event atomically. Other state -> 409.
- Viewer cannot mutate; invalid input400, missing resources404, conflicts409, unauthenticated401, forbidden403. Keep existing strict JSON integer parsing.

## Event wire contract

Topics: `orderops.orders.v1` (OrderConfirmed, RetryRequested), `orderops.fulfillment.v1` (ShipmentCreated, FulfillmentFailed); poison records -> `<source-topic>.DLT`.

JSON envelope:

```json
{"eventId":"uuid","orderId":"uuid","eventType":"OrderConfirmed","schemaVersion":1,"occurredAt":"2026-11-02T00:00:00Z","payload":{"attemptGeneration":1,"customerReference":"DEMO-1","shippingAddress":{"recipient":"Ada Lovelace","line1":"10 Example Street","city":"London","postalCode":"SW1A 1AA","country":"GB"},"demoScenario":"NORMAL","items":[{"sku":"COFFEE","quantity":2}]}}
```

RetryRequested includes the same complete payload with the incremented generation. Result event payload: `{attemptGeneration,shipmentId,trackingNumber,message}`; shipment fields may be null for FulfillmentFailed. Serialize values as strings/numbers/null, not Java class metadata. Envelope ID and schema version mandatory; validate payload shape before consuming. Order ID is Kafka key.

Outbox publishers retain the row until Kafka acknowledges. Publish under a short bounded lock/batch; duplicates allowed after publish-before-mark crash. Consumers commit database effects and event deduplication before offset acknowledgment. Use manual immediate acknowledgments outside transaction completion if listener delegates to a transactional service. Poison events must be durably published to DLT before source offset is committed; infrastructure failures must be retried rather than discarded.

Order result consumer: lock the order, validate generation, reject/ignore stale results without losing deduplication; SHIPMENT_CREATED must not be downgraded by a late failure. Preserve history; log identifiers only.

Foundation outbox records without shipping details must be quarantined/marked legacy by migration or explicit validation; never send an addressless order to the carrier.

## Fulfillment processing

Fulfillment owns database tables jobs, consumed events, shipments, outbox. Incoming command deduplicates event ID and upserts one job per order/generation in a transaction. Duplicate/reordered old commands cannot create a second shipment. Keep a unique shipment per order across generations.

Worker claims due jobs with an expiring lease; external HTTP happens outside database transactions. Persist attempts, next attempt time and status. Defaults: 3 attempts per generation; configurable backoff. Exhaustion emits FulfillmentFailed and retains stock in Order Service. Manual retry is a new generation, but the carrier idempotency key remains the order UUID. Restart/lease expiry safely retries that key. Stale worker completions must not overwrite newer generations; if an earlier attempt created a shipment, reconcile success for the current order without duplicate carrier side effects.

Carrier HTTP: POST `${orderops.carrier.base-url}/shipments`, header `Idempotency-Key: <order UUID>`, body `{orderId,shippingAddress,demoScenario}`. Success 200 or201 -> `{shipmentId,trackingNumber,createdAt}`. Client timeout default1 second. Retry timeout/503/429; terminal invalid4xx emits clear failure. Stable simulator result for repeated key. No changing scenario/address on retry.

Carrier simulator is a separate Node built-in HTTP process, internal only. NORMAL succeeds. FAIL_THEN_RECOVER returns503 for first3 calls per key then succeeds (manual recovery). TIMEOUT_AFTER_CREATE creates once, delays first response beyond client timeout, then subsequent retry returns stored shipment. Data survives simulator restart. Coordinator owns simulator implementation.

## Web

React + TypeScript + esbuild, same-origin fetch with session cookies, loopback Node dev proxy to localhost8080. Responsive operations dashboard: login, inventory, paginated orders, create order with address/scenario, detail/timeline, retry recovery, readable loading/error/empty states. Viewer sees read-only controls. Retain the same request idempotency key after an uncertain network result; create a new key only for a new draft or a definitive changed-payload decision. Poll order list/detail while preserving drafts. No mock transport in the production app. Playwright may mock HTTP in isolated UI tests, plus write a separate real-stack E2E scenario for CI.

## Deployment

Docker Compose contains postgres, Kafka (single-node KRaft), carrier simulator, both Java apps and nginx-served web. Use private service networking, loopback bindings for local infrastructure, env-managed credentials. Local profile can seed demo inventory/users; production must not use demo defaults. Single Linux EC2 deployment files with HTTPS, health checks, backup/rollback guidance and CI image build. No invented AWS credentials or claims of live deployment.
