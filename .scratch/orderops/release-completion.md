# OrderOps release completion — 2026-11-03

Status: local implementation and real-stack verification complete; external release acceptance pending.

## Real-stack verification (this pass)

Docker Desktop and Maven Central were restored, so the previously blocked gates were run end-to-end:

- `mvn verify` (default messaging profile, Failsafe 3.5.4 now resolvable): **BUILD SUCCESS**. Order Service 58 core + 14 integration (OrderApiIT 11, LegacyMigrationIT 1, KafkaOrderMessagingIT 2); Fulfillment 21 core + 12 integration (FulfillmentPostgresIT 11, KafkaTransportIT 1). 105 tests, 0 failures/errors/skips, against real PostgreSQL and Kafka Testcontainers.
- `docker compose --env-file .env -f infra/compose.yaml -f infra/compose.frontend-build.yaml --profile apps up -d --build`: all six services built and healthy; order-service and fulfillment-service `/internal/health` return UP, nginx web root 200.
- Frontend `npm run test:e2e` (real-stack) against http://127.0.0.1:8088: **passed**.
- Frontend `npm run demo:record`: produced `.cache/demo/orderops-demo.webm` (300s, 3 real orders, all 4 claims asserted: normal shipment, identical replay reserves once, manual recovery reaches generation 2, carrier timeout reconciles). Fixed a bug where `video.saveAs` ran after `browser.close()`; video is now saved after `context.close()` and before `browser.close()`.

Projects: `orderops/backend` and `orderops/frontend`, each with its own Git repository.

## Implemented in this pass

- Removed Vite and its vulnerable dependency chain; esbuild bundles the app and a loopback Node proxy preserves API/session behavior. Added real HTTP proxy/access-control tests.
- Both Java applications emit ECS JSON, return bounded `X-Correlation-ID`, and clean MDC after requests and async work. Logs join HTTP requests to durable order/event IDs without business payloads.
- Fixed the carrier/result identifier length mismatch with an observed RED/GREEN regression. Added bounded reason/source-offset metadata to Order Service dead letters and assertions in the broker integration test.
- Added checksummed backup artifacts and a disposable network-isolated PostgreSQL restore checker; wired a restore drill into integration CI.
- Added a roughly five-minute captioned real-stack video recorder and successful-video CI artifact. It creates three real orders and asserts success, safe replay, failure recovery and timeout reconciliation.
- Updated active architecture/security/operations documents and reconciled implementation tickets. Independent reviewer startup failed due to a usage limit; coordinator self-review is recorded in `release-review.md`.

## Verified now

- Default messaging-profile `mvn package`: **79 tests passed**, 0 failures/errors/skips; both applications and Kafka adapters compile and executable JARs package. Logs: ignored `.cache/release-package.log`.
- Observability test subset: **21 passed** before the final full core run. MVC output confirms actual ECS JSON with correlation fields.
- Carrier simulator: **7 passed**.
- Frontend: clean cached `npm ci`, **8 client/proxy tests**, production typecheck/build, **6 isolated Chromium journeys** passed. Windows browser-runner teardown required stopping only the test server PID printed by that run.
- Latest successful `npm audit --audit-level=high`: exit 0; **0 high/critical/moderate, 1 low** esbuild finding. Audit access was intermittent; an unsuccessful attempt is not counted as a clean audit. The low issue concerns esbuild's serving API, which is not used. Its patched 0.28.2 tarball fetch failed EACCES, so the tested 0.27.7 lock was retained.
- Local/production Compose configuration, all three CI workflow YAML files, CloudFormation JSON and Bash syntax pass. These are configuration/syntax checks, not cloud/container execution.

## Remaining gates and concrete blockers

1. ~~`mvn verify`~~ — **resolved**: real PostgreSQL/Kafka integration passes (105 tests).
2. ~~No application endpoint reachable / demo recording fails~~ — **resolved**: full stack runs; E2E and demo recording both pass.
3. Both Git projects have no remote. Connected GitHub search found no `orderops` repository under the configured account; local `gh` authentication is invalid. Backend/frontend repository URLs are still needed to publish and run hosted CI.
4. No AWS CLI/profile/configuration is available. Profile, region, VPC/public subnet, domain and an existing secret parameter must be supplied/configured before provisioning. No credentials should be pasted into chat. No live deployment, rollback or cloud recovery has occurred.

## Acceptance commands when prerequisites exist

From backend: `mvn verify`, then `docker compose --env-file .env -f infra/compose.yaml -f infra/compose.frontend-build.yaml --profile apps up -d --build`.

From frontend: `npm audit --audit-level=high`; set `ORDEROPS_E2E_URL=http://127.0.0.1:8088` and run `npm run test:e2e`; set `ORDEROPS_DEMO_URL` to the same isolated stack and run `npm run demo:record`. These journeys consume six inventory units together.

Run the documented restore checker against one complete backup set, then follow `docs/DEPLOYMENT.md` for the AWS deployment and a real rollback/restore exercise. These remaining gates must pass before calling the whole portfolio release complete.
