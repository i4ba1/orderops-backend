# Jagaad requirements mapped to evidence

Target: [Senior Java Developer](https://jagaad.com/careers/senior-java-developer/).

This table distinguishes implemented source from release acceptance. The historical 83-test Java/full-stack run predates the folder split and latest logging changes; it is not a verification of the current release.

| Requirement | Implementation/evidence | Current verification |
| --- | --- | --- |
| Java, Spring MVC, REST | Java 21 controllers, application services, repositories and explicit HTTP contracts | Core/MVC suite runs locally |
| SOA and API design | Two independently packaged applications, separate database ownership, OpenAPI/event schemas | Both compile; latest distributed rerun blocked |
| Data/JPA/Hibernate | Atomic reservations, sorted locks, migrations, held-lock concurrency tests | Real PostgreSQL tests passed historically; rerun pending |
| Security | Sessions, CSRF, roles, strict coercion; no high frontend audit findings | HTTP tests and frontend audit gate pass; one low dev-tool advisory remains |
| Pub-sub/Kafka | Transactional outboxes, persisted deduplication, generation guards, acknowledged DLT with reason metadata | Adapters compile; current broker rerun pending |
| CI/CD | Independent backend/frontend pipelines, explicit commit-pair integration, image/release artifacts | YAML validated; hosted execution requires repository targets |
| Tests | Domain/HTTP, PostgreSQL/Kafka, simulator, client/proxy and real/isolated browser journeys | See current release ledger for counts and blocked checks |
| Docker | Full stack and optional sibling frontend build override | Configuration validates; engine access currently denied |
| AWS/Linux | CloudFormation EC2/SSM/IAM/S3, HTTPS, backup checksums and disposable restore checker | Script/config validation; actual provisioning/restore/rollback pending |
| Frontend/mobile | React/TypeScript responsive operations dashboard | Build and isolated browser journeys pass |
| Observability | ECS JSON, bounded request correlation, durable order/event context, aggregate worker metrics | Context/cleanup/privacy tests pass |
| Communication | Domain language, contracts, ADR, demo outline, real-stack video recorder and operations guides | Recording awaits a running stack |

Read [.scratch/orderops/release-completion.md](../.scratch/orderops/release-completion.md) for current evidence and the remaining external prerequisites. A solo portfolio project does not establish employment history or years of professional tenure.
