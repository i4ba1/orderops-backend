# OrderOps release completion — 2026-11-03

Status: source work delivered; external release acceptance pending.

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

1. `mvn verify` was attempted with fail-at-end. Core tests/package passed; Failsafe 3.5.4 transitive artifacts were unavailable in the configured local mirror. Current Docker API access is also denied. No latest PostgreSQL/Kafka test success is claimed; prior integration evidence predates this release.
2. No application endpoint is reachable on 8080/8081/8088/8090. Demo recording fails preflight with ECONNREFUSED; there is no completed video. Restore checker runtime needs Docker. The CI workflow contains both operations for a usable runner.
3. Both Git projects have no remote. Connected GitHub search found no `orderops` repository under the configured account; local `gh` authentication is invalid. Backend/frontend repository URLs are still needed to publish and run hosted CI.
4. No AWS CLI/profile/configuration is available. Profile, region, VPC/public subnet, domain and an existing secret parameter must be supplied/configured before provisioning. No credentials should be pasted into chat. No live deployment, rollback or cloud recovery has occurred.

## Acceptance commands when prerequisites exist

From backend: `mvn verify`, then `docker compose --env-file .env -f infra/compose.yaml -f infra/compose.frontend-build.yaml --profile apps up -d --build`.

From frontend: `npm audit --audit-level=high`; set `ORDEROPS_E2E_URL=http://127.0.0.1:8088` and run `npm run test:e2e`; set `ORDEROPS_DEMO_URL` to the same isolated stack and run `npm run demo:record`. These journeys consume six inventory units together.

Run the documented restore checker against one complete backup set, then follow `docs/DEPLOYMENT.md` for the AWS deployment and a real rollback/restore exercise. These remaining gates must pass before calling the whole portfolio release complete.
