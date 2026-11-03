ALTER TABLE customer_orders ADD COLUMN shipping_recipient VARCHAR(120), ADD COLUMN shipping_line1 VARCHAR(200),
    ADD COLUMN shipping_city VARCHAR(100), ADD COLUMN shipping_postal_code VARCHAR(32), ADD COLUMN shipping_country VARCHAR(2),
    ADD COLUMN demo_scenario VARCHAR(32) NOT NULL DEFAULT 'NORMAL', ADD COLUMN attempt_generation INTEGER NOT NULL DEFAULT 1 CHECK(attempt_generation>0),
    ADD COLUMN shipment_id VARCHAR(160), ADD COLUMN tracking_number VARCHAR(160);
ALTER TABLE outbox_events ADD COLUMN quarantine_reason VARCHAR(160);
UPDATE outbox_events SET quarantine_reason='Legacy event without validated shipping address'
    WHERE published_at IS NULL AND jsonb_typeof(payload #> '{payload,shippingAddress}') IS DISTINCT FROM 'object';
CREATE TABLE order_timeline (
    order_id UUID NOT NULL REFERENCES customer_orders(id), event_position INTEGER NOT NULL,
    event_id UUID NOT NULL UNIQUE, event_type VARCHAR(64) NOT NULL, message VARCHAR(500) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL, PRIMARY KEY(order_id,event_position)
);
INSERT INTO order_timeline(order_id,event_position,event_id,event_type,message,occurred_at)
    SELECT id,0,id,'OrderConfirmed','Legacy order; shipping details unavailable',created_at FROM customer_orders;
CREATE TABLE idempotency_claims (
    actor VARCHAR(120) NOT NULL, operation VARCHAR(100) NOT NULL, request_key VARCHAR(100) NOT NULL,
    request_hash VARCHAR(64) NOT NULL, order_id UUID NOT NULL REFERENCES customer_orders(id),
    PRIMARY KEY(actor,operation,request_key)
);
CREATE TABLE consumed_fulfillment_events (
    event_id UUID PRIMARY KEY, order_id UUID NOT NULL REFERENCES customer_orders(id), consumed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX order_created_idx ON customer_orders(created_at DESC,id DESC);
