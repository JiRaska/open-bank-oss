-- Keep NULL for legacy rows with no recorded provenance. New writers supply a verified
-- Boolean and derive each new event from the payment's stored value.
-- Rollback retains this column and existing evidence. Pause synthetic submissions and
-- transitions before returning to an older binary, which cannot propagate the marker.
ALTER TABLE sct_inst_payments ADD COLUMN synthetic BOOLEAN;
