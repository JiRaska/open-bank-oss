-- ClearingService.submit() parks newly submitted items in the sentinel batch
-- 00000000-0000-0000-0000-000000000000 ("assigned during clearing") and a later
-- clearing cycle re-assigns them to a real batch. clearing_items.batch_id is
-- NOT NULL + FK, so this row must exist or every submit fails with 23503.
--
-- Rollback: DELETE FROM clearing_batches WHERE id = '00000000-0000-0000-0000-000000000000';
-- (only safe when no clearing_items reference the sentinel — i.e. no PENDING items)
INSERT INTO clearing_batches (id, batch_reference, rail, settlement_type, status, currency)
VALUES (
    '00000000-0000-0000-0000-000000000000',
    'UNASSIGNED-SENTINEL',
    'INTERNAL',
    'NET',
    'PENDING',
    'EUR'
)
ON CONFLICT (id) DO NOTHING;
