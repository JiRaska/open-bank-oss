-- Preserve every distinct Treasury cash-flow state accepted by the Risk Engine.
-- A fixing can change the state without changing the source deal_version.
-- Backfilled rows are captured at migration time. Earlier history is unavailable.
--
-- Rollback (discards archived revisions):
--   DROP TRIGGER archive_treasury_cash_flow_revision ON treasury_deal_cash_flows;
--   DROP FUNCTION archive_treasury_cash_flow_revision();
--   DROP TABLE treasury_deal_cash_flow_revision;

CREATE TABLE treasury_deal_cash_flow_revision (
    revision_id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    deal_id       UUID         NOT NULL,
    deal_version  INTEGER      NOT NULL,
    schedule_hash VARCHAR(128) NOT NULL,
    flows         JSONB        NOT NULL,
    recorded_at   TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX ix_treasury_cash_flow_revision_deal
    ON treasury_deal_cash_flow_revision (deal_id, revision_id DESC);

-- Keep the backfill and trigger installation atomic with respect to ingestion.
LOCK TABLE treasury_deal_cash_flows IN SHARE ROW EXCLUSIVE MODE;

INSERT INTO treasury_deal_cash_flow_revision (deal_id, deal_version, schedule_hash, flows)
SELECT deal_id, deal_version, schedule_hash, flows FROM treasury_deal_cash_flows;

CREATE FUNCTION archive_treasury_cash_flow_revision() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF (OLD.deal_version, OLD.schedule_hash, OLD.flows)
            IS NOT DISTINCT FROM (NEW.deal_version, NEW.schedule_hash, NEW.flows) THEN
            RETURN NEW;
        END IF;
    END IF;

    INSERT INTO treasury_deal_cash_flow_revision (deal_id, deal_version, schedule_hash, flows)
    VALUES (NEW.deal_id, NEW.deal_version, NEW.schedule_hash, NEW.flows);
    RETURN NEW;
END;
$$;

CREATE TRIGGER archive_treasury_cash_flow_revision
AFTER INSERT OR UPDATE ON treasury_deal_cash_flows
FOR EACH ROW EXECUTE FUNCTION archive_treasury_cash_flow_revision();
