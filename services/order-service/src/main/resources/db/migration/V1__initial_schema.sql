CREATE TABLE inventory_items (
    sku VARCHAR(64) PRIMARY KEY,
    name VARCHAR(160) NOT NULL,
    available_quantity INTEGER NOT NULL CHECK (available_quantity >= 0)
);

CREATE TABLE customer_orders (
    id UUID PRIMARY KEY,
    customer_reference VARCHAR(120) NOT NULL,
    status VARCHAR(32) NOT NULL CHECK (status IN ('CONFIRMED', 'SHIPMENT_CREATED', 'FULFILLMENT_FAILED')),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE order_items (
    order_id UUID NOT NULL REFERENCES customer_orders(id),
    line_position INTEGER NOT NULL,
    sku VARCHAR(64) NOT NULL REFERENCES inventory_items(sku),
    quantity INTEGER NOT NULL CHECK (quantity BETWEEN 1 AND 1000),
    PRIMARY KEY (order_id, line_position),
    UNIQUE (order_id, sku)
);

CREATE TABLE stock_reservations (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES customer_orders(id),
    sku VARCHAR(64) NOT NULL REFERENCES inventory_items(sku),
    quantity INTEGER NOT NULL CHECK (quantity BETWEEN 1 AND 1000),
    UNIQUE (order_id, sku)
);

CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    schema_version INTEGER NOT NULL CHECK (schema_version > 0),
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    payload JSONB NOT NULL,
    published_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX outbox_unpublished_idx ON outbox_events(occurred_at) WHERE published_at IS NULL;
