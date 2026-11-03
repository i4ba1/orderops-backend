# Frontend build tooling

Date: 2026-11-03

The inherited Vite lock had a high-severity development-server advisory. Patched Vite package retrieval was denied. Keeping it would leave the release audit gate red. The application used no Vite-specific source APIs, so it now builds with esbuild and serves development assets through a small loopback-only Node proxy. Production remains nginx/static files.

The proxy preserves session cookies, CSRF and request identity, and serves only an explicit in-memory asset map. Tests cover proxy behavior, origin/host rejection, traversal and missing assets. esbuild watches source files; browser refresh is manual. This introduces a small maintained development script and gives up Vite hot module replacement.

Audit now passes the high-severity gate with one low esbuild finding. It concerns esbuild's own serving API, which the application does not invoke; the Node proxy rejects the reported traversal shape. Updating the library to 0.28.2 was attempted but its tarball was denied. This decision does not claim a vulnerability-free dependency tree. See frontend/SECURITY.md.
