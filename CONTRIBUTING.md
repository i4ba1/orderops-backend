# Contributing

Use CONTEXT.md vocabulary and docs/agents tracker conventions. Each change should deliver observable behavior with tests at its public seam. Keep adapters outside domain rules and database ownership inside the application.

For orders, cover validation, authorization and transactional effects. For fulfillment, consider timeout-after-create, lease expiry, duplicate delivery, restart and stale generations. Mock-only tests cannot establish PostgreSQL locks or broker commits; use disposable integration tests for those claims.

Before release, run default Maven verification, simulator tests, frontend build/client tests, isolated browser journeys, real-stack E2E and the high-severity audit gate. Report blocked checks. Offline artifacts are not Kafka release substitutes.

Frontend source and its CI live in the independent `../frontend` repository, alongside this `backend` repository in the `orderops` workspace. Backend CI verifies Java and the simulator without that checkout. Coordinate HTTP contract changes with the frontend and use the manual integration workflow to verify an explicit pair of commits. The optional `infra/compose.frontend-build.yaml` builds both sibling checkouts locally.

PR descriptions should explain trigger, resulting behavior, tradeoff and actual verification. Preserve migration compatibility for rollback or document restore/reconciliation. Record architecture decisions for material tradeoffs; routine choices belong with code.
