-- W3C traceparent of the transaction that wrote the row: the relay publishes it later, from
-- another thread, and must continue the original trace (ADR-006, phase 8).
ALTER TABLE outbox_event ADD COLUMN trace_parent TEXT;
