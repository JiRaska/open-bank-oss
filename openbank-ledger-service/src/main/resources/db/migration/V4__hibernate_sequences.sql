-- Hibernate (Quarkus PanacheEntity) allocates Long ids from a per-entity sequence
-- named "<table>_seq" using a pooled optimizer with allocationSize 50. The outbox
-- table's BIGSERIAL default is bypassed by Hibernate, so the sequence must exist.
CREATE SEQUENCE IF NOT EXISTS ledger_outbox_seq INCREMENT BY 50;
