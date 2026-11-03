# Separate frontend project

Date: 2026-11-03

The frontend was initially extracted to a sibling `orderops-frontend` directory. The user clarified the desired layout: `orderops/backend` and `orderops/frontend`. Both remain independent projects and Git repositories inside a shared workspace. The frontend owns its package lock, client/browser tests, Dockerfile, nginx configuration, and CI; the backend owns Java services and stack orchestration.

The interface between projects is the Order Service HTTP contract in `contracts/openapi.yaml`. Session cookies, CSRF, and same-origin proxy behavior remain unchanged. Vite can use `ORDEROPS_API_TARGET` for a different local backend address.

The backend Compose stack consumes `ORDEROPS_FRONTEND_IMAGE`. An optional `infra/compose.frontend-build.yaml` builds the sibling checkout for development; production requires a separately verified image tag or digest. Backend CI and releases do not include frontend source. A manually dispatched integration workflow accepts a frontend repository and commit to verify both projects together.

This keeps independent frontend development, verification, and release artifacts inside the requested workspace layout. API changes must be coordinated across the two repositories. Historical implementation notes describe the former `web/` and external `orderops-frontend` locations; this decision supersedes those paths. Clone the repositories as `backend` and `frontend` under `orderops`.
