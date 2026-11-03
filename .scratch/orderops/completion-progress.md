# OrderOps completion progress

Plan: docs/superpowers/plans/2026-11-02-orderops-complete.md

The user explicitly authorized implementation until the end. Continue all independently executable work. Live AWS access is requested asynchronously; absent access does not block source, IaC or runbook completion.

## Preflight

- Existing foundation: 25 passing unit/MVC tests, packaged applications and successful Fulfillment HTTP smoke check.
- Docker engine still unavailable; shell Maven Central connection still unavailable.
- JAVA_HOME must select Java21, inherited MAVEN_OPTS must be cleared in child builds, and local mirror settings live in ignored .cache.
- Work split by disjoint directories with an explicit shared HTTP/event contract.
- Ruling: add an offline build profile solely for domain/MVC verification without unavailable Kafka artifacts; production builds keep Kafka adapters enabled by default. Cost: offline results cannot verify broker integration and will be labeled accordingly.

## Implementation evidence

- Carrier simulator TDD: normal create/restart replay, fail-three-then-recover, timeout-after-create and empty-state backup tests observed RED then GREEN. Seven simulator HTTP/storage/concurrency tests pass.
- Full local/production Compose configuration parses. CloudFormation JSON and Linux script syntax checks pass; AWS-side validation and provisioning require an account and have not run.
- Production demo seeding is explicitly enabled for fictional inventory while passwords remain required environment values; local credential defaults are not used.
- Child Order Service report currently 44 passing core/MVC tests, with real PostgreSQL/Kafka integration authored separately. Fulfillment storage/lease/transport tests are being completed.
- Ruling: offline artifacts carry a distinct filename and manifest build-mode. The deployment script rejects an offline/stale artifact; offline checks cannot silently produce a deployable Kafka release.
- Carrier simulator backup initialization fixed through RED/GREEN: an unused carrier now has a durable empty state, so pre-deployment backups do not fail simply because no shipment has been requested.

## Real-stack verification (2026-11-02, network restored)

Docker Desktop started and Maven Central reachable again, so the previously blocked real verification ran end-to-end:

- `mvn verify` (default messaging profile) **BUILD SUCCESS**. All suites ran against real PostgreSQL and Kafka Testcontainers:
  - Order Service: 48 core/MVC + 14 integration (OrderApiIT 11, LegacyMigrationIT 1, KafkaOrderMessagingIT 2).
  - Fulfillment: 9 core + 12 integration (FulfillmentPostgresIT 11, KafkaTransportIT 1).
  - 83 tests, 0 failures, 0 errors, 0 skipped.
- `docker compose --env-file .env -f infra/compose.yaml --profile apps up -d --build`: all six services built and healthy (postgres, kafka, carrier-simulator, order-service, fulfillment-service, web).
- Health smoke: order-service and fulfillment-service `/internal/health` both return 200 UP; nginx web root 200.
- `node --test tools/carrier-simulator/test/server.test.mjs`: 7 pass.
- Full-stack E2E `npm run test:e2e` against http://127.0.0.1:8088: **passed**. One test-locator bug fixed (`getByLabel('Scenario')` -> `getByRole('combobox', { name: 'Scenario' })`) because the scenario field is a `<select>` rendered as a combobox role.
- AWS provisioning remains unperformed (no account); CI image build/release not yet run on a hosted runner.
