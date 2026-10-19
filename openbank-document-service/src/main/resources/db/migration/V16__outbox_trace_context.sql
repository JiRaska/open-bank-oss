-- Outbox trace propagation: the W3C traceparent of the request that wrote the row, so the
-- dispatcher (which runs later, with no request) can publish the event inside the producing
-- trace and a consumer span links to the originating request.
--
-- Nullable and additive: rows written before this migration, and rows written outside any span,
-- carry NULL and are published exactly as before.
--
-- Rollback (before apply only -- never edit an applied migration):
--   ALTER TABLE document_outbox DROP COLUMN IF EXISTS trace_context;
ALTER TABLE document_outbox ADD COLUMN IF NOT EXISTS trace_context TEXT;
