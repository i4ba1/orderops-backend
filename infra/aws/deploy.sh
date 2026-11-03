#!/usr/bin/env bash
set -euo pipefail
umask 077

base=/opt/orderops
candidate=$(realpath "${1:?Usage: deploy.sh /opt/orderops/releases/<release-id>}")
case "$candidate" in "$base"/releases/*) ;; *) echo 'Release must be inside /opt/orderops/releases' >&2; exit 1 ;; esac
test -f "$candidate/infra/compose.yaml"
test -f "$candidate/infra/compose.prod.yaml"
for service in order-service fulfillment-service; do
  jar="$candidate/services/$service/target/$service-0.1.0-SNAPSHOT.jar"
  test -f "$jar"
  if ! unzip -p "$jar" META-INF/MANIFEST.MF | tr -d '\r' | grep -q '^OrderOps-Build-Mode: kafka$'; then
    echo "$service is not a default-profile Kafka build; refusing an offline or unstamped artifact" >&2
    exit 1
  fi
done
mkdir -p "$base/shared"
exec 200>"$base/shared/deploy.lock"
flock -n 200 || { echo 'Another deployment is running' >&2; exit 1; }

region=$(cat "$base/shared/aws-region")
parameter=$(cat "$base/shared/config-parameter-name")
incoming="$base/shared/.env.incoming"
trap 'rm -f -- "$incoming"' EXIT
aws ssm get-parameter --region "$region" --name "$parameter" --with-decryption \
  --query Parameter.Value --output text > "$incoming"
chmod 600 "$incoming"

compose_candidate=(docker compose --project-name orderops --env-file "$incoming" \
  -f "$candidate/infra/compose.yaml" -f "$candidate/infra/compose.prod.yaml" --profile apps)
"${compose_candidate[@]}" config --quiet

if test -L "$base/current"; then
  previous=$(realpath "$base/current")
  bash "$previous/infra/aws/backup.sh"
  printf '%s\n' "$previous" > "$base/shared/previous-release"
fi

mv -- "$incoming" "$base/shared/.env"
compose=(docker compose --project-name orderops --env-file "$base/shared/.env" \
  -f "$candidate/infra/compose.yaml" -f "$candidate/infra/compose.prod.yaml" --profile apps)
"${compose[@]}" build
"${compose[@]}" up -d

healthy=false
for attempt in $(seq 1 60); do
  if curl -fsS http://127.0.0.1:8080/internal/health >/dev/null \
    && curl -fsS http://127.0.0.1:8081/internal/health >/dev/null \
    && curl -fsS http://127.0.0.1:8088/ >/dev/null; then
    healthy=true
    break
  fi
  sleep 2
done
if test "$healthy" != true; then
  echo 'Deployment health checks failed. Previous release pointer retained; inspect logs and use rollback runbook.' >&2
  exit 1
fi

ln -sfn "$candidate" "$base/current"
install -m 644 "$candidate/infra/aws/orderops-backup.service" /etc/systemd/system/orderops-backup.service
install -m 644 "$candidate/infra/aws/orderops-backup.timer" /etc/systemd/system/orderops-backup.timer
systemctl daemon-reload
systemctl enable --now orderops-backup.timer
echo 'Deployment serving HTTP internally. Verify the external HTTPS session and full recovery journey before calling this release healthy.'
