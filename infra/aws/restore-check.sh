#!/usr/bin/env bash
# Restore backup artifacts into a disposable, network-isolated PostgreSQL container.
# Never connects to or modifies the application's databases or volumes.
set -euo pipefail

backup_dir=$(realpath "${1:?Usage: restore-check.sh /path/to/downloaded/backup}")
for file in orders.dump fulfillment.dump carrier-state.json release.txt SHA256SUMS; do
  test -s "$backup_dir/$file" || { echo "Missing backup artifact: $file" >&2; exit 1; }
done
(cd "$backup_dir" && sha256sum --check --strict SHA256SUMS)

container="orderops-restore-check-$(date -u +%Y%m%d%H%M%S)-$$"
created=false
cleanup() {
  if test "$created" = true; then docker rm -f "$container" >/dev/null; fi
}
trap cleanup EXIT
# No published ports, persistent volumes, or external network access.
docker run --detach --name "$container" --network none \
  --tmpfs /var/lib/postgresql/data:rw,size=1g \
  --env POSTGRES_HOST_AUTH_METHOD=trust postgres:17-alpine >/dev/null
created=true
ready=false
for attempt in $(seq 1 30); do
  if docker exec "$container" pg_isready -h 127.0.0.1 -U postgres >/dev/null 2>&1; then ready=true; break; fi
  sleep 1
done
test "$ready" = true || { echo 'Disposable PostgreSQL did not become ready' >&2; exit 1; }

for database in orders fulfillment; do
  docker exec "$container" createdb -U postgres "$database"
  docker exec -i "$container" pg_restore -U postgres --dbname "$database" \
    --no-owner --no-privileges --exit-on-error < "$backup_dir/$database.dump"
done

# Query restored business tables, not merely the dump's header/catalogue.
docker exec "$container" psql -U postgres -d orders --set=ON_ERROR_STOP=1 -c \
  'SELECT (SELECT count(*) FROM customer_orders) AS orders, (SELECT count(*) FROM stock_reservations) AS reservations, (SELECT count(*) FROM outbox_events) AS events;'
docker exec "$container" psql -U postgres -d fulfillment --set=ON_ERROR_STOP=1 -c \
  'SELECT (SELECT count(*) FROM fulfillment_jobs) AS jobs, (SELECT count(*) FROM fulfillment_shipments) AS shipments, (SELECT count(*) FROM fulfillment_outbox) AS events;'

echo 'Both database backups restored successfully into disposable storage. This verifies restoreability, not distributed reconciliation or a live rollback.'
