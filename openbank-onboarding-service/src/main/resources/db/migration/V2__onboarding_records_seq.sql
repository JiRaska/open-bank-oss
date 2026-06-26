-- Hibernate (PanacheEntity, ORM 6) allocates ids from a sequence named
-- `onboarding_records_seq` (allocationSize 50, pooled optimizer). V1 created the table
-- with `id BIGSERIAL`, whose implicit sequence is `onboarding_records_id_seq` — a
-- different name — so inserts failed with `relation "onboarding_records_seq" does not
-- exist` and the read-model never persisted. Create the sequence Hibernate expects.
CREATE SEQUENCE IF NOT EXISTS onboarding_records_seq START WITH 1 INCREMENT BY 50;
