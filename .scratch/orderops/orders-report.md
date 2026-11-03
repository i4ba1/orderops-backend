# Order Service implementation report

Owner: `services/order-service/**`; no shared application, root build, contract, web or infrastructure files changed. No commits created. Java 21 and Spring Boot 3.5.15 retained.

## Implemented behavior

- Creation requires an ASCII `Idempotency-Key` of 8?100 letters/digits/underscore/hyphen. The controller uses the authenticated principal; clients cannot supply the actor. New creation returns 201, a matching replay returns 200, and changed request content returns 409.
- PostgreSQL transaction advisory locks serialize actor/operation/key claims before a claim exists. The persisted request hash covers customer reference, every address field, scenario and SKU-sorted quantities. Request claim, order, stock mutation, reservations, timeline and outgoing command share one transaction. Existing sorted stock row locking is preserved.
- Shipping address validation applies in the domain and HTTP DTO. Scenarios default to NORMAL and permit the three agreed names. Jackson rejects numeric enum ordinals (including numeric strings), numeric/boolean address strings, fractional quantities and string quantities.
- Order responses expose shipping/scenario, generation, nullable shipment identifiers, items and durable timeline entries. Paginated reads return the agreed `{content,number,size,totalElements,totalPages}` shape, sorted newest first with UUID tie breaking; page size is bounded 1?100.
- `GET /api/session` returns username and OPERATOR/VIEWER role. Logout returns 204. Viewer mutations remain forbidden, and CSRF remains required.
- Retry requires an operator, CSRF and request key. Only failed fulfillment transitions back to CONFIRMED; generation advances once, history is retained and a full RetryRequested command is written. Replays return 200, new retries 202; stock is not reserved again.
- Fulfillment result parsing validates envelope identifiers, supported schema/type, timestamp, integer generation and relevant payload fields. Invalid JSON/payloads become poison events. Identifier parsing rejects shortened/noncanonical UUID spellings, and oversized schema-version integers cannot wrap to 1.
- Transactional result projection locks the order, records event-ID deduplication and updates state atomically. Stale or future generations have no business effect but are deduplicated. Duplicate or late failures cannot downgrade SHIPMENT_CREATED. A success can reconcile a failed current generation.
- Outbox dispatch locks at most five unpublished rows with SKIP LOCKED and marks them published only after its synchronous publisher returns. Exceptions roll back publication marks, preserving stable envelope IDs for repeat delivery.
- Kafka adapters use string JSON values, UUID order keys, producer acks=all and idempotence. The listener acknowledges only after the projection transaction returns. Infrastructure errors retry indefinitely; poison recovery waits for the DLT broker acknowledgment before committing the source offset. A failed DLT publication throws and leaves the source available for retry.
- All Kafka-dependent Java source files and classes start with Kafka. Core code does not reference those types. Kafka defaults to localhost:19092 and supports ORDEROPS_KAFKA_BOOTSTRAP_SERVERS. Database-only IT disables messaging; production messaging is enabled by default.
- V2 migrates shipping/recovery state, idempotency claims, timeline and consumed result IDs. Legacy addressless/null-address outbox rows are quarantined, never marked published or sent to a carrier; legacy orders receive an explicit initial timeline entry and cannot be retried without shipping details.

## Verification evidence

Commands run from project root with `JAVA_HOME=C:/Program Files/Java/jdk-21` and an empty child `MAVEN_OPTS`.

1. `mvn.cmd --settings .cache/maven-settings.xml -Poffline -pl services/order-service test`
   - Latest completed run: **48 tests, 0 failures, 0 errors, 0 skipped**.
   - 26 MVC/security/validation tests; 7 fulfillment wire parsing tests; 10 order domain/lifecycle tests; 5 inventory tests.
   - Includes observed red-to-green regressions for missing request key (201 instead of 400), session endpoint (404), numeric scenario ordinals/numeric shipping fields (201 instead of 400), and schema-version integer overflow (invalid version accepted).
   - Kafka source and Kafka IT are explicitly excluded by the offline profile; this is not broker verification.
2. `mvn.cmd --settings .cache/maven-settings.xml -Poffline -pl services/order-service verify`
   - Core tests passed and the executable jar was built.
   - Verification then **failed**, before integration execution, because Failsafe 3.5.4 dependencies were unavailable in the local Maven cache: surefire-api, surefire-logger-api, surefire-booter, surefire-extensions-spi, surefire-extensions-api, surefire-shared-utils, maven-surefire-common (all 3.5.4).
3. `mvn.cmd --settings .cache/maven-settings.xml -Poffline -pl services/order-service -Dtest=OrderApiIT test`
   - Used the available Surefire 3.5.6 to exercise the actual integration setup.
   - **Failed with 1 setup error, 0 skipped**: `IllegalStateException: Could not find a valid Docker environment`. No PostgreSQL integration test body ran.
4. `mvn.cmd -o --settings .cache/maven-settings.xml -pl services/order-service test`
   - **Failed** resolving uncached `org.springframework.kafka:spring-kafka:3.3.16` and `org.testcontainers:testcontainers-kafka:2.0.5`.
   - Kafka adapters and Kafka IT therefore have not been compiled or executed locally. Adapter API usage was checked against the upstream Spring Kafka 3.3.16 and Testcontainers 2.0.5 source, which does not replace compilation or integration tests.

Existing Mockito self-attachment/JDK agent warnings remain in the MVC test output; they do not affect the observed test result.

## Real integration tests authored (no disabled/skip-if-Docker-unavailable annotations)

`OrderApiIT`: 11 PostgreSQL scenarios covering transactional create/outbox; last-line insufficient stock rollback; duplicate SKU rejection; an unknown last SKU after an earlier reservation with stock/reservation/order/outbox/claim rollback assertions; concurrent last-unit buyers; concurrent same-key creation and canonical SKU replays with changed customer/address/scenario conflicts; actor key scope and concurrent same-key retry; actual held-row contention observed through pg_stat_activity; deduplication and stale-generation projection; publish-before-mark rollback and replay; newest-first pagination.

`LegacyMigrationIT`: migrates a real V1 PostgreSQL database containing addressless/null-address commands to V2, verifies durable quarantine, unpublished state and preserved order history.

`KafkaOrderMessagingIT`: real PostgreSQL + Kafka Testcontainers scenarios for acknowledged outbox publication, a crash between publish and mark followed by stable-ID broker duplicates, poison DLT publication followed by a valid result, duplicate result delivery, persisted projection and history.

## Remaining verification limits

The complete default-profile build and real PostgreSQL/Kafka suite must run in CI or on a machine with Docker and the required Maven artifacts. This environment provides no evidence of live broker transport, database concurrency, schema migration execution, or real-stack deployment. The production code and test scenarios are delivered for that verification; no success was fabricated and no DB check was silently skipped.
