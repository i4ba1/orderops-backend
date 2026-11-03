#!/usr/bin/env bash
set -euo pipefail
umask 077

base=/opt/orderops
release=$(realpath "$base/current")
case "$release" in "$base"/releases/*) ;; *) echo 'Invalid current release path' >&2; exit 1 ;; esac
exec 200>"$base/shared/backup.lock"
flock -n 200 || { echo 'Another backup is running' >&2; exit 1; }
region=$(cat "$base/shared/aws-region")
bucket=$(cat "$base/shared/backup-bucket-name")
timestamp=$(date -u +%Y%m%dT%H%M%SZ)
backup_dir="$base/shared/backups/$timestamp"
mkdir -p "$backup_dir"

compose=(docker compose --project-name orderops --env-file "$base/shared/.env" \
  -f "$release/infra/compose.yaml" -f "$release/infra/compose.prod.yaml" --profile apps)
"${compose[@]}" exec -T postgres pg_dump -U postgres -Fc orders > "$backup_dir/orders.dump"
"${compose[@]}" exec -T postgres pg_dump -U postgres -Fc fulfillment > "$backup_dir/fulfillment.dump"
"${compose[@]}" exec -T carrier-simulator cat /data/state.json > "$backup_dir/carrier-state.json"
printf '%s\n' "$release" > "$backup_dir/release.txt"
(cd "$backup_dir" && sha256sum orders.dump fulfillment.dump carrier-state.json release.txt > SHA256SUMS)
for file in orders.dump fulfillment.dump carrier-state.json release.txt SHA256SUMS; do
  test -s "$backup_dir/$file"
  aws s3 cp --only-show-errors --region "$region" "$backup_dir/$file" \
    "s3://$bucket/backups/$timestamp/$file" --sse AES256
done
echo "Backup uploaded under backups/$timestamp. Host copies retained for inspection; apply the documented retention policy."
