-- SPDX-License-Identifier: Apache-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-- See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
--
-- ADR-0252: retain synthetic activity as auditable bronze provenance, but never let it enter
-- baseline state/history/as-of or daily-volume projections.
ALTER TABLE openbank_analytics.bronze_events
    ADD COLUMN IF NOT EXISTS synthetic UInt8 DEFAULT 0;

CREATE OR REPLACE VIEW openbank_analytics.silver_current_state AS
SELECT
    upper(b.aggregate_type) AS aggregate_type,
    b.aggregate_id,
    argMax(b.event_id, (b.aggregate_version, b.occurred_at, b.ingested_at)) AS event_id,
    max(b.aggregate_version) AS aggregate_version,
    argMax(b.event_type, (b.aggregate_version, b.occurred_at, b.ingested_at)) AS event_type,
    argMax(b.occurred_at, (b.aggregate_version, b.occurred_at, b.ingested_at)) AS occurred_at,
    argMax(b.source_service, (b.aggregate_version, b.occurred_at, b.ingested_at)) AS source_service,
    argMax(b.schema_version, (b.aggregate_version, b.occurred_at, b.ingested_at)) AS schema_version,
    argMax(b.payload, (b.aggregate_version, b.occurred_at, b.ingested_at)) AS payload
FROM openbank_analytics.bronze_events AS b
WHERE b.synthetic = 0
GROUP BY upper(b.aggregate_type), b.aggregate_id;

CREATE OR REPLACE VIEW openbank_analytics.gold_daily_event_volume AS
SELECT
    toDate(occurred_at) AS day,
    source_service,
    aggregate_type,
    event_type,
    count() AS events
FROM openbank_analytics.bronze_events
WHERE synthetic = 0
GROUP BY day, source_service, aggregate_type, event_type;

CREATE OR REPLACE VIEW openbank_analytics.silver_history AS
SELECT
    upper(aggregate_type) AS aggregate_type,
    aggregate_id,
    aggregate_version,
    event_type,
    occurred_at AS valid_from,
    leadInFrame(occurred_at) OVER (
        PARTITION BY upper(aggregate_type), aggregate_id
        ORDER BY aggregate_version, occurred_at, ingested_at
        ROWS BETWEEN CURRENT ROW AND UNBOUNDED FOLLOWING
    ) AS valid_to,
    payload
FROM openbank_analytics.bronze_events
WHERE synthetic = 0;

CREATE OR REPLACE VIEW openbank_analytics.silver_as_of AS
SELECT
    upper(b.aggregate_type) AS aggregate_type,
    b.aggregate_id,
    argMax(b.event_id, (b.aggregate_version, b.occurred_at, b.ingested_at)) AS event_id,
    max(b.aggregate_version) AS aggregate_version,
    argMax(b.event_type, (b.aggregate_version, b.occurred_at, b.ingested_at)) AS event_type,
    argMax(b.occurred_at, (b.aggregate_version, b.occurred_at, b.ingested_at)) AS occurred_at,
    argMax(b.payload, (b.aggregate_version, b.occurred_at, b.ingested_at)) AS payload
FROM openbank_analytics.bronze_events AS b
WHERE b.synthetic = 0
  AND b.occurred_at <= {t:DateTime64(3, 'UTC')}
GROUP BY upper(b.aggregate_type), b.aggregate_id;
