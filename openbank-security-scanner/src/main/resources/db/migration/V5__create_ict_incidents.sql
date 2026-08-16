-- The DORA ICT incident register (issue #4728). Until this migration the register lived only in
-- `IctIncidentService`'s ConcurrentHashMap, so every pod restart silently emptied it: open
-- incidents, their containment/resolution timestamps and the `reported_to_regulator` flag all
-- vanished, and `GET /api/v1/ict-incidents` answered 200 with `[]` as if none had ever been
-- reported. That is data loss at replicas: 1 — it does not need a second pod to happen.
--
-- No sequence here (contrast V3): the id is application-assigned (UUID.randomUUID() in
-- reportIncident), not allocated by Hibernate, so the entity is PanacheEntityBase with an explicit
-- @Id rather than PanacheEntity.
--
-- Rollback: DROP TABLE ict_incidents;

CREATE TABLE IF NOT EXISTS ict_incidents (
    id                      UUID         PRIMARY KEY,
    title                   TEXT         NOT NULL,
    description             TEXT         NOT NULL,
    category                VARCHAR(64)  NOT NULL,
    severity                VARCHAR(32)  NOT NULL,
    status                  VARCHAR(32)  NOT NULL,
    affected_services       TEXT         NOT NULL,
    detected_at             TIMESTAMPTZ  NOT NULL,
    reported_at             TIMESTAMPTZ  NOT NULL,
    contained_at            TIMESTAMPTZ,
    resolved_at             TIMESTAMPTZ,
    rto_minutes             INTEGER,
    rpo_minutes             INTEGER,
    reported_to_regulator   BOOLEAN      NOT NULL DEFAULT FALSE,
    regulatory_report_id    TEXT,
    assigned_to             TEXT,
    created_at              TIMESTAMPTZ  NOT NULL,
    updated_at              TIMESTAMPTZ  NOT NULL
);

-- The list endpoint filters on status/severity and orders by created_at DESC.
CREATE INDEX IF NOT EXISTS idx_ict_incidents_created_at ON ict_incidents (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_ict_incidents_status ON ict_incidents (status);
CREATE INDEX IF NOT EXISTS idx_ict_incidents_severity ON ict_incidents (severity);
