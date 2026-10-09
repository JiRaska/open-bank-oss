-- Persist ambiguity before handing a screened transfer to the scheme gateway.
-- Pre-existing rows have no recorded unknown scheme outcome; do not infer one retroactively.
ALTER TABLE sct_inst_payments ADD COLUMN scheme_outcome_unknown BOOLEAN NOT NULL DEFAULT FALSE;
-- Rollback: retain this additive column while reverting the application; older binaries ignore it.
