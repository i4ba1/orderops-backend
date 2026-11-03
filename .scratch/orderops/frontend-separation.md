# Frontend separation — 2026-11-03

## Layout correction

The user clarified that both projects should live under `orderops`, in `backend/` and `frontend/`. Both projects, including their independent Git metadata and dependencies, have been moved to those directories. Active Compose paths, integration CI checkout paths, README links and local Maven cache settings now match. The earlier sibling directory no longer exists.

Fresh verification after this correction: 6 frontend client tests pass; typecheck/production build pass; Compose resolves both new build contexts; all three workflow YAML files parse. Browser and real-stack tests were not repeated for this directory-only correction.

The user explicitly requested deletion of `orderops/web`. The exact-path, cache-only recursive deletion was rejected before execution because sandbox approval is required and disabled. The remaining folder is still only the legacy dependency cache. This is an execution-policy blocker, not a request for further user authorization.

## Earlier extraction record

Requested outcome: frontend is a separate project outside `orderops`.

Implemented:
- Moved application source, tests, npm lock/dependencies, Docker/nginx configuration and frontend README to `D:/java-development/orderops-frontend`.
- Initialized an independent local Git repository; no remote or publication created.
- Preserved agent workflow instructions and domain vocabulary in the new project.
- Backend Compose consumes a frontend image. Optional local override builds the sibling checkout. Production requires an explicit image selection.
- Split CI and Dependabot ownership; added a manual workflow verifying an explicit backend/frontend commit pair.
- Updated local development, image transfer, deployment, rollback and security documentation.
- Isolated browser tests now own port 4173, avoiding the stale development server on 5173.

Verification from the new project:
- `npm test`: 6 passed.
- `npm run build`: typecheck and production build passed.
- `npm run test:ui`: 6 passed, exit 0. Windows test-server teardown stalled; stopped only the Vite PID identified in this test run's output, allowing the runner to exit normally.
- Base/local/production Compose configurations parse. Assertions confirm base/production use an image without frontend source and the local build resolves the new sibling directory.
- Both repositories' CI/Dependabot YAML parses; package and lock names match.

Limits:
- Docker engine access is denied in this session, so images and the real-stack journey were not rebuilt/rerun after separation. Earlier full-stack evidence predates this change.
- The previous Vite advisory remains; the security gate is retained in frontend and integration CI.
- An old dev server still occupies 5173. Stop it before starting development from the new location.
- The former `orderops/web` directory contains only ignored `.cache/node_modules-before-clean` leftovers. Windows denied moving this cache; recursive cleanup was rejected by the execution approval policy. No application source remains there. Cleanup must wait until the old process is closed and filesystem permissions permit it.
- Hosted CI, remote repositories, and AWS deployment were not executed.
