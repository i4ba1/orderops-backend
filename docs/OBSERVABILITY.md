# Operational logs and correlation

Both Java applications emit ECS JSON through Spring Boot's [structured logging support](https://docs.spring.io/spring-boot/3.5/reference/features/logging.html). Application/service identity, level, timestamp and thread metadata accompany each record.

Every HTTP request receives `X-Correlation-ID`. A single incoming identifier matching `[A-Za-z0-9._-]{1,64}` is accepted; missing, duplicate, overlong or unsafe values are replaced with a UUID. The filter runs before authentication, returns the header and scopes `correlationId` in MDC until request completion. Completion logs contain HTTP status and duration, not URLs, query strings, credentials or request bodies. Tests cover failure cleanup and log privacy.

Create/retry completion logs link the HTTP correlation ID to an `orderId`. Outbox publication, Kafka consumption and worker attempts use `orderId` and `eventId`; worker results add attempt/generation/outcome fields. These are durable identifiers already in the event contract. The original HTTP ID is not persisted in events: follow the request-to-order log, then query that order ID across both services. This is correlation, not a distributed tracing implementation.

Do not put personal information in caller-provided correlation IDs. Do not turn on HTTP wire or SQL parameter logging with real shipping data. DLT payloads contain private business data and remain internal; the Order Service adds a bounded validation reason and source offset to DLT headers.

`GET /internal/health` remains process health. Fulfillment `/internal/metrics` reports durable job states, pending result events and shipment counts. These endpoints are available on private/loopback service ports and are not exposed by the public nginx proxy. Use logs and backlog metrics together; HTTP availability alone does not prove fulfillment is progressing.
