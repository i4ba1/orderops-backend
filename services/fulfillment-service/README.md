# Fulfillment Service

The service consumes versioned order commands, stores a job for each order generation, and requests a shipment using the order UUID as the carrier idempotency key. It owns its PostgreSQL database exclusively.

Configuration: database variables are required by default. Activate `SPRING_PROFILES_ACTIVE=local` to enable the development database defaults below.

| Variable | Local default |
| --- | --- |
| `FULFILLMENT_DATABASE_URL` | `jdbc:postgresql://localhost:5433/fulfillment` |
| `FULFILLMENT_DATABASE_USERNAME` | `fulfillment` |
| `FULFILLMENT_DATABASE_PASSWORD` | `fulfillment-local-only` |
| `ORDEROPS_KAFKA_BOOTSTRAP_SERVERS` | `localhost:19092` |
| `CARRIER_BASE_URL` | `http://localhost:8090` |

Supply deployment credentials through environment variables. Keep port 8081 internal; `/internal/health` reports service availability and `/internal/metrics` returns aggregate ready/running/failed job counts, unpublished outbox rows and stored shipments. Failed job counts include retained failed generations. Metrics contain no order or address details.

The worker commits a lease before HTTP, persists at most three attempts per generation, and uses exponential backoff starting at two seconds. The default lease lasts thirty seconds; the HTTP deadline is one second. Configure `orderops.fulfillment.backoff`, `orderops.fulfillment.lease`, and `orderops.carrier.timeout` through normal Spring configuration. A retry command starts a new generation and retains the original carrier key and shipping details. Stale failures are discarded; late carrier acceptance reconciles the newest generation.

Outbox rows remain pending until the broker acknowledges publication. A crash between publication and marking can duplicate a result. Consumers acknowledge commands only after database commit. Invalid envelopes, unsupported versions, mismatched keys, and legacy addressless commands go to `orderops.orders.v1.DLT`; DLT publication must succeed before acknowledgment. Infrastructure failures retry indefinitely.

From the repository root, `mvn -pl services/fulfillment-service verify` runs unit tests and disposable PostgreSQL/Kafka integration tests; Docker and dependency downloads must be available. The `offline` Maven profile excludes Kafka adapters/tests and verifies core/MVC/HTTP behavior. `FulfillmentPostgresIT` still requires real PostgreSQL via Testcontainers and fails explicitly when Docker is unavailable; it never deletes data from a development database.
