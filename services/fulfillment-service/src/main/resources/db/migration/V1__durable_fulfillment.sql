CREATE TABLE fulfillment_consumed_events (
    event_id uuid PRIMARY KEY,
    order_id uuid NOT NULL,
    consumed_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE fulfillment_jobs (
    order_id uuid NOT NULL,
    generation integer NOT NULL CHECK (generation > 0),
    command_json jsonb NOT NULL,
    status varchar(20) NOT NULL CHECK (status IN ('READY','RUNNING','SUCCEEDED','FAILED','SUPERSEDED')),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 3),
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    lease_token uuid,
    lease_until timestamptz,
    last_error varchar(500),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (order_id, generation)
);
CREATE INDEX fulfillment_jobs_due ON fulfillment_jobs(next_attempt_at) WHERE status IN ('READY','RUNNING');
CREATE TABLE fulfillment_shipments (
    order_id uuid PRIMARY KEY,
    shipment_id varchar(200) NOT NULL,
    tracking_number varchar(200) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE fulfillment_outbox (
    event_id uuid PRIMARY KEY,
    order_id uuid NOT NULL,
    generation integer NOT NULL,
    event_type varchar(40) NOT NULL,
    event_json jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz,
    UNIQUE(order_id, generation, event_type)
);
CREATE INDEX fulfillment_outbox_pending ON fulfillment_outbox(created_at) WHERE published_at IS NULL;
