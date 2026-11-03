# Deploying the OrderOps portfolio demo

The source includes a single Linux EC2 deployment design. It has **not been provisioned or validated against a live AWS account**. The machine runs both Java apps, PostgreSQL, Kafka, the carrier simulator and the web edge. It is a single point of failure and is intended for a portfolio demonstration with fictional data.

## Before provisioning

Use an AWS account/profile, a region, a public subnet in an existing VPC, and a domain you control. Review the selected instance, EBS, public IPv4 and S3 costs for that region. The template defaults to an 8 GiB t3.large to leave room for the combined workload; this is a starting resource allocation, not a measured capacity claim.

Create an SSM **SecureString** parameter such as `/orderops/demo/environment`, using the standard AWS-managed SSM encryption key. Store environment-file text with independently generated passwords:

```dotenv
SITE_DOMAIN=orderops.example.com
ACME_EMAIL=your-address@example.com
ORDEROPS_POSTGRES_ADMIN_PASSWORD=<strong-random-value>
ORDEROPS_DATABASE_PASSWORD=<different-strong-random-value>
FULFILLMENT_DATABASE_PASSWORD=<different-strong-random-value>
ORDEROPS_OPERATOR_PASSWORD=<different-strong-random-value>
ORDEROPS_VIEWER_PASSWORD=<different-strong-random-value>
ORDEROPS_FRONTEND_IMAGE=orderops-frontend:<verified-frontend-commit-sha>
```

Do not copy the local demo passwords into this parameter. The two fixed account names are `operator` and `viewer`; public signup and multi-retailer tenancy are outside this product's scope. Secrets are retrieved on the host into a mode-600 file and are never part of the release artifact or CloudFormation outputs. The production portfolio Compose override explicitly seeds fictional inventory, without enabling the local profile's credential defaults.

## Provision the host

From an authenticated AWS CLI environment, first validate and review the template:

```bash
aws cloudformation validate-template --template-body file://infra/aws/orderops-stack.json --region <region>
aws cloudformation deploy --template-file infra/aws/orderops-stack.json \
  --stack-name orderops-demo --capabilities CAPABILITY_IAM --region <region> \
  --parameter-overrides VpcId=<vpc-id> PublicSubnetId=<subnet-id> \
    ConfigParameterName=/orderops/demo/environment
```

The template creates an EC2 instance, HTTPS-only ingress security group (ports 80/443), an SSM instance role and an encrypted/private backup bucket. There is no inbound SSH, database or Kafka security-group rule. The instance requires IMDSv2. Cloud-init installs Docker/Compose; inspect cloud-init status through SSM before deploying. Template syntax has been checked locally, but that does not replace AWS template validation or a provisioning test.

Use the instance ID/output to open an SSM session:

```bash
aws ssm start-session --target <instance-id> --region <region>
```

Point the domain's A record at the instance public IPv4 address. This template does not allocate an Elastic IP; stopping/replacing the instance can change the address. Caddy handles HTTPS at the public edge once DNS reaches the host. PostgreSQL, Kafka, API and simulator host bindings remain loopback-only.

## Build and transfer a release

The release must pass `mvn verify`, simulator tests, frontend checks and the real-stack browser suite in an environment with Docker and dependency access. A restricted/offline test result is insufficient for release approval.

Backend CI packages backend source and executable JARs after its checks. Place an immutable release directory at `/opt/orderops/releases/<backend-commit-sha>/`, preserving `infra/`, `tools/`, `contracts/`, `docs/` and both service target JARs. Frontend source belongs to the separate `orderops-frontend` repository and is not included.

Frontend CI produces `orderops-frontend-image.tar.gz` after its audit, build and isolated tests pass. Transfer both artifacts through an authenticated S3 object or an existing controlled artifact channel; do not include `.env`, `.cache`, `node_modules` or test reports. On the host, load the image with `docker load -i orderops-frontend-image.tar.gz`, then set `ORDEROPS_FRONTEND_IMAGE` in the SSM configuration to its exact `orderops-frontend:<frontend-commit-sha>` tag. Alternatively, use an authenticated registry image pinned by digest. Do not use the local build override in production.

Before deploying, run the backend repository's manual **Verify frontend and backend together** workflow with the chosen frontend repository and commit SHA. A private frontend repository may require a read-only `FRONTEND_REPO_TOKEN` secret. Record the verified backend/frontend commit pair with the release. Independent repository checks alone do not verify their integration.

On the host:

```bash
sudo bash /opt/orderops/releases/<commit-sha>/infra/aws/deploy.sh /opt/orderops/releases/<commit-sha>
```

The script validates environment/configuration, backs up an existing release, builds images, starts Compose, checks application/web availability and updates the current-release pointer. It installs a daily backup timer. It does not claim the complete business journey is healthy based only on health endpoints: log in over HTTPS and demonstrate all three carrier scenarios after deployment.

## Rollback

Read `/opt/orderops/shared/previous-release`, inspect its code/schema compatibility and deploy that directory with the same script. Select its matching frontend image in the SSM configuration first; changing the backend directory does not roll back that image selection. Retain both release artifacts. Flyway migrations are forward-only. A code rollback is safe only if the older code supports the migrated schema. Otherwise restore into a **separate disposable environment** from a known backup and verify the migration/recovery path before touching live state.

This design accepts deployment downtime. It has no load-balancer failover, managed database, broker replication or automatic disaster recovery.

## Path beyond the single-host demo

Preserve service database ownership when moving PostgreSQL to a managed deployment; rehearse dump/restore and migration compatibility before cutover. Move Kafka to an authenticated replicated service, then verify producer acknowledgments, consumer offsets and DLT permissions under broker failure. Deploy Java processes independently behind a private network and HTTPS edge. Multiple Order Service instances also require a shared session strategy. Replace the simulator with a carrier that explicitly supports the same durable idempotency contract. Measure workload and recovery targets before choosing capacity or claiming availability; none of these changes is part of the current demo deployment.

## Backup and recovery

The timer runs a custom-format `pg_dump` for both service databases and copies the carrier's durable state, then uploads them with a release pointer to the private encrypted S3 bucket. Inspect `systemctl status orderops-backup.timer` and `journalctl -u orderops-backup.service`; test restoration instead of assuming uploaded files are sufficient. Host backup copies are retained for inspection and should be pruned under an explicit retention policy before disk space becomes constrained.

New backups also contain `SHA256SUMS`. Download all five files from one backup timestamp to a private directory and run `bash infra/aws/restore-check.sh /path/to/backup` on a Docker-capable Linux host. It verifies checksums, restores both databases into a temporary PostgreSQL container with no network or published ports, queries business tables, and removes only its own container on exit. It never connects to the live databases. Its 1 GiB temporary storage is intended for this small portfolio dataset. This checks database restoreability; it does not automatically reconcile Kafka or roll back a live deployment. The integration workflow runs the same check against a quiesced disposable stack.

Database dumps are taken sequentially, not as one distributed snapshot. Restoring the system therefore requires reconciliation: restore both databases and carrier state, retain a compatible release, and safely replay durable outgoing events into a fresh broker so projections catch up. Consumer deduplication, stable carrier keys and generation checks are designed to make replay safe, but the restore/replay procedure must be exercised with real infrastructure before claiming recovery guarantees. Never discard outbox or carrier state merely to reset a demo.

The backup bucket is retained when the stack is deleted and has a 30-day object/noncurrent-version lifecycle. The EC2 root volume is deleted with the instance. Export/test backups before removing the host, and explicitly clean up retained AWS resources when the demonstration ends.

## Sources

The template and edge configuration follow [CloudFormation EC2 resource documentation](https://docs.aws.amazon.com/AWSCloudFormation/latest/TemplateReference/aws-resource-ec2-instance.html) and [Caddy reverse-proxy documentation](https://caddyserver.com/docs/caddyfile/directives/reverse_proxy). Review provider documentation before a live rollout.
