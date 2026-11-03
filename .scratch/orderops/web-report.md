# Web implementation report — 2026-11-02

Implemented only `web/**` plus this requested report. No commits, subagents, backend, simulator or infrastructure changes.

## Delivered

- Responsive React/TypeScript/Vite workspace with navy navigation, warm surfaces, teal actions, amber failure recovery, desktop/mobile layouts and inline SVG icons. No external fonts, images or UI framework.
- Session restoration, login/logout, role labels and viewer read-only behavior. Same-origin real HTTP only in production; CSRF is fetched before login and renewed after authentication; cookies accompany every request.
- Overview metrics, paginated order list, inventory stock cards, full create-order shipping/scenario form, order detail/timeline, shipment reference and operator recovery.
- Error/loading/empty states; failed polling preserves stale readable data; background updates preserve form state. Native modal dialogs trap focus and support Escape, except during submission.
- Frozen idempotency key/payload per uncertain operation; create request survives refresh in actor-scoped session storage. Exact request can be replayed safely. Editing an unresolved submission requires an explicit, explained choice to start a separate order. Pending recovery keys also survive refresh. Storage-disabled browsers retain identity in memory only.
- Production Docker/nginx static serving with health check, API/login/logout proxy, SPA fallback, CSP/security headers and incoming forwarded-protocol preservation. Backend internal endpoints are not proxied. Vite dev and preview defaults bind loopback; dev proxies backend port8080.
- `README.md` documents commands, isolated versus real-stack tests, credentials/configuration, persistence limits and metric semantics.

## Verification

Commands run from `D:/java-development/orderops/web`:

- `npm test`: **6 passed**, no failures. CSRF rotation/form encoding, cookie transport, mutation key/no automatic replay, network/HTML/invalid-JSON errors, definitive conflict messages, key persistence/reload/edit protection, pagination/encoded retry route. Tests were observed failing with unimplemented client stubs before implementing the client.
- `npm run build`: **passed**, includes strict TypeScript checking and Vite static build. Current output approximately223kB JS (69kB gzip),26kB CSS (6.6kB gzip).
- `npm run test:ui`: **6 passed**, Chromium. Operator timeline/recovery, viewer access, lost create response + reload + exact same key/body, polling preserving drafts + pagination, mobile navigation/no horizontal page overflow, failed refresh keeping orders readable + empty-state recovery. HTTP routes are replaced only in this isolated test file.
- Desktop and mobile screenshots were generated under ignored `web/.cache/` and visually inspected.
- An initial browser failure exposed native `fetch` being invoked with an incompatible receiver; the default transport now calls global fetch through a wrapper. Browser journeys then passed.
- A clean offline install generated a coherent cross-platform package lock, including Linux native optional package metadata. Final `npm ci --offline --no-audit --cache ../.cache/npm` **passed**, followed by another **passing** production build as the closing reproducibility check.

`e2e/real-stack.spec.ts` is a separate **unmocked** scenario, authored but not run by this worker because the complete running stack was not available. It requires `ORDEROPS_E2E_URL`, defaults only to local demo operator credentials, creates real orders and reserves actual stock. It covers failure exhaustion/manual recovery, exact create replay without double reservation, timeout-after-carrier-creation reconciliation, normal fulfillment and timeline/generation assertions. It fails when the stack URL is absent and is not a fake passing integration check. Coordinator should run it after Compose integration.

Docker image/nginx startup not executed by this worker; coordinator owns Compose/container integration.

## Dependency constraint requiring follow-up

Declared, locked and locally verified Vite version is **8.3.2**. A full `npm audit` succeeded and reported **one high advisory** in its development server; the recommended patched version is8.3.5. Production ships only static assets via nginx and contains no Vite server. Dev/preview bind127.0.0.1 by default. This limitation remains material for development tooling.

Attempts to obtain patched versions were bounded and failed because the restricted environment has incomplete package artifacts/metadata:

1. `npm install ... vite@7.3.7`: `EACCES` fetching `https://registry.npmjs.org/vite/-/vite-7.3.7.tgz`; log `.cache/npm/_logs/2026-11-02T12_17_48_500Z-debug-0.log` relative to the repository.
2. `npm install ... vite@8.3.5`: `ETARGET` for transitive `rolldown@~1.2.14`; log `.cache/npm/_logs/2026-11-02T12_29_15_898Z-debug-0.log`.
3. Coordinator-requested lock-only fallback `npm install ... vite@7.3.7 --package-lock-only --ignore-scripts`: `ETARGET` for transitive `@types/estree@^1.0.10`; log `.cache/npm/_logs/2026-11-02T12_30_46_726Z-debug-0.log`.

No lock metadata/integrity was fabricated. Actual cached dependencies remain coherent. Upgrade Vite in a network-enabled environment and rerun install/build/UI checks before considering the tooling advisory resolved. The later production-only audit attempt failed at its audit endpoint; **no clean production audit claim is made**.

Only public registry cache entries relevant to the frontend toolchain were read from the existing user npm cache and copied into repository `.cache/npm` for offline installation. Package-manager logs and temporary scripts/artifacts remain ignored under workspace cache directories.
