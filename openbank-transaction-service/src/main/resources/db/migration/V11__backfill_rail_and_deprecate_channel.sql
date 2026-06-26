-- ADR-0103 D4: best-effort backfill of rail + instruction_type from the vestigial channel
-- column (historically always 'API', so pre-D2 rows receive UNKNOWN). Mark channel deprecated.

-- Pre-D2 rows have NULL rail; best-effort fill = UNKNOWN (channel carries no routing signal).
UPDATE transactions
    SET rail            = 'UNKNOWN',
        instruction_type = 'UNKNOWN'
    WHERE rail IS NULL;

-- channel is vestigial (always 'API', never set by payment paths). Deprecated in ADR-0103 D4.
-- Will be physically dropped in a future release after all reads are confirmed zero.
COMMENT ON COLUMN transactions.channel
    IS 'DEPRECATED (ADR-0103 D4): vestigial PSD2 audit field, always API. Rail/instructionType supersede it. Scheduled for removal.';
