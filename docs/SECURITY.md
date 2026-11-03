# Security boundaries and outstanding work

OrderOps is a single-retailer portfolio demonstration. Operator/viewer are fixed accounts with environment-supplied passwords outside the local profile. Spring Security uses password hashes, sessions and CSRF protection. Production cookies are secure/HTTP-only behind HTTPS. Server authorization enforces viewer restrictions.

Inputs enforce address lengths, unique SKUs, integer quantities and bounded pagination. Idempotency includes actor and operation. Stable carrier keys, uniqueness and generation checks protect business effects after retries; Kafka delivery alone cannot guarantee an external HTTP effect occurs once.

Only the web edge is public in the deployment template. Database, Kafka, worker metrics and simulator remain private/loopback-bound. Kafka plaintext stays inside the single-host Docker network. Cross-host deployment requires authenticated/encrypted transport and a different network design.

Use fictional shipping details in this demo. Authorized responses, storage, events and private DLT records contain addresses. Application operational logs contain bounded request IDs, durable order/event identifiers, status, timing and outcome metadata; they omit request bodies, credentials and carrier response text. Do not enable HTTP wire logging or SQL parameter logging on real data. Secrets belong in SSM/environment files, never source or release artifacts.

## Frontend dependency changes

The separate `../frontend` project now uses esbuild and a loopback-only Node development server; Vite was removed. The latest successful audit on 2026-11-03 returned no high, critical or moderate findings and one low esbuild advisory. Its [Windows serving-path vulnerability](https://github.com/evanw/esbuild/security/advisories/GHSA-g7r4-m6w7-qqqr) is in esbuild's server, which this application does not invoke. Our development server serves a fixed asset map and rejects traversal and foreign Host/Origin headers. The library still appears in audit; the patched 0.28.2 download was denied by the environment. See frontend/SECURITY.md for details.

Frontend and integration CI retain the high-severity audit gate. Backend-only CI does not audit frontend dependencies. Clean installation, build and browser tests do not replace that gate.

The single host/broker/database have no high availability. Cloud provisioning, live rollback, backup reconciliation and the current full-stack release checks still require infrastructure execution.
