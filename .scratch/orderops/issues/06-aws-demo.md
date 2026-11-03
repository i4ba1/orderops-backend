# 06 - Run the complete demo on AWS

Status: needs-info
Progress: infrastructure and operational tooling implemented; live acceptance pending
Blocked by: 05

**What to build:** A reviewer can access the full application over HTTPS and inspect a documented, repeatable deployment.

- [ ] Provision the agreed single-host Linux demo with reproducible configuration.
- [x] Keep database and Kafka ports private; enable secure cookies and environment-managed secrets.
- [ ] CI checks the full application and builds versioned images before deployment.
- [ ] Demonstrate deployment, health checks and rollback with a known previous version.
- [x] Add operational metrics, structured logs and correlation identifiers without logging credentials or shipping details.
- [x] Record the single point of failure, resource/cost assumptions and migration path to managed services.
- [ ] Create cloud resources only when account access and deployment authorization are available.

## Current evidence — 2026-11-03

ECS JSON/request and durable-event correlation are implemented and tested. Compose, CloudFormation JSON, and shell syntax validate. Checksummed backups and a disposable PostgreSQL restore checker are implemented and wired into integration CI. Hosted CI needs repository targets; deployment needs an AWS profile, region, subnet and domain. No live deployment, rollback, restore or cloud provisioning is claimed.
