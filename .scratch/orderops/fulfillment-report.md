# Fulfillment implementation report

Implemented only `services/fulfillment-service/**`, plus this explicitly authorized report. No commits or subagents.

## Implementation

- Service-owned Flyway PostgreSQL tables: generation jobs, consumed event IDs, one shipment per order, transactional result outbox. No cross-service database access.
- Strict versioned event validation, full shipping details, valid item quantities/SKUs and immutable retry identity. UUID/order-key matching is enforced at Kafka ingress. Legacy/addressless events are rejected for durable DLT handling.
- Transactional command deduplication and per-order PostgreSQL advisory locks. Reordered older generations cannot enqueue additional work. Existing shipments reconcile a newer command without HTTP.
- Worker claims persistent expiring leases; HTTP occurs after claim commit. Persisted attempts are bounded to three per generation with configurable exponential backoff. Lease expiry can reclaim work; exhausting a third expired lease emits failure without a fourth attempt.
- Stable order UUID carrier key across generations/restarts; 200/201 accepted; timeout, 429 and 5xx retry; invalid 4xx terminal. Responses are validated and error messages do not expose shipping details.
- Stale failed tokens/generations cannot change current state. A late carrier success persists the unique order shipment and emits success for the latest generation.
- Kafka adapters confined to `Kafka*.java`. Manual immediate acknowledgment follows committed DB effects. Invalid data is durably published to source `.DLT` with send-result checking; infrastructure failures retry indefinitely. Result publication retains outbox rows until acknowledged, allowing safe duplicates after publish-before-mark crashes.
- Internal aggregate `/internal/metrics`, unchanged health endpoint, two scheduler threads so a blocked publisher does not monopolize the worker. README documents configuration and metrics semantics.

## Verification observed

Java21 was selected with `$env:JAVA_HOME='C:/Program Files/Java/jdk-21'`; malformed inherited options cleared with `$env:MAVEN_OPTS=''`.

Final core command:

`mvn.cmd --settings .cache/maven-settings.xml -Poffline -pl services/fulfillment-service test`

**PASS: 9 tests, 0 failures, 0 errors, 0 skipped.** Includes existing health, strict envelope validation/API limits, bounded retry policy, aggregate metrics MVC, actual HTTP success, retryable 429/503 vs terminal400, timeout-after-create replay with exactly the same order UUID and stored shipment response.

TDD evidence: initial tests failed because requested types were missing. Expanded API-limit/quantity/SKU tests then produced two actual assertion failures before validation fixes; final suite passes. Metrics contract tests initially failed compilation before implementation. Storage behavior assertions could not execute because Docker is unavailable.

11 real `FulfillmentPostgresIT` scenarios were authored and compile under the offline profile: duplicates/reordered commands; store reconstruction between attempts and manual retry; concurrent command and lease claims; HTTP outside DB transactions; immutable retry rollback; stale claim failure/late-generation success; failed outbox publication/replay; newer command before initial command; expired third lease exhaustion; actual carrier400 terminal failure; persisted aggregate counts.

The explicit storage attempt:

`mvn.cmd --settings .cache/maven-settings.xml -Poffline -pl services/fulfillment-service -Dtest=FulfillmentPostgresIT test`

**BLOCKED/FAILED: Testcontainers 2.0.5 reports `Could not find a valid Docker environment`; 1 container-startup error, 0 skipped.** PostgreSQL assertions were not run or claimed passing. Subsequent extra integration cases compile but cannot run for the same blocker.

Failsafe attempt:

`mvn.cmd --settings .cache/maven-settings.xml -Poffline -pl services/fulfillment-service -Dit.test=FulfillmentPostgresIT failsafe:integration-test failsafe:verify`

**BLOCKED/FAILED:** failsafe3.5.4 cannot resolve missing cached surefire-api/logger/booter/extensions/shared-utils/maven-surefire-common3.5.4 dependencies from the configured local file mirror.

Messaging compile attempt:

`mvn.cmd --settings .cache/maven-settings.xml -Pmessaging -pl services/fulfillment-service -DskipTests compile`

**BLOCKED/FAILED:** `spring-kafka:3.3.16` is unavailable in the configured `file:///C:/Users/user/.m2/repository/` mirror. `testcontainers-kafka:2.0.5` metadata is also absent. Therefore Kafka adapter compilation and `KafkaTransportIT` are unverified locally. No dependency stubs or fake transport were introduced.

`KafkaTransportIT` is authored using disposable KafkaContainer (`apache/kafka:3.9.1`) and PostgreSQLContainer (`postgres:17-alpine`) and covers duplicate source commands, committed effects, real outbox result transport and malformed command DLT publication. It does not silently skip without Docker.

## Integration concerns

- CI with network and Docker must run the default messaging `verify` lifecycle; this is still necessary to validate SQL and Spring Kafka API/runtime integration.
- Production uses required `FULFILLMENT_DATABASE_URL`, `FULFILLMENT_DATABASE_USERNAME`, `FULFILLMENT_DATABASE_PASSWORD`, plus `ORDEROPS_KAFKA_BOOTSTRAP_SERVERS`, `CARRIER_BASE_URL`. Database localhost/user/password defaults exist only in `application-local.yml` with `SPRING_PROFILES_ACTIVE=local`. Local broker defaults to localhost19092; PostgreSQL localhost5433/fulfillment; carrier localhost8090.
- Default topics are single-partition, replication-factor1 for the local single-node broker. DLT publication uses producer partition selection and does not assume source/DLT partition counts match.
- Old failed job generations and published outbox rows are intentionally retained. Operational failed-job counts include retained failed generations; outstanding outbox count includes only unpublished rows.
- There is no claim of a running full stack, successful broker/storage integration, or live deployment in this environment.
