CREATE TABLE stock_decrement_ledger (
    resource_id     UUID NOT NULL,
    booking_id      UUID NOT NULL,
    decremented_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (resource_id, booking_id)
);

CREATE INDEX idx_stock_decrement_ledger_resource ON stock_decrement_ledger (resource_id);
