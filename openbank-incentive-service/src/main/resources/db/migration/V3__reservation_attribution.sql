-- Rollback: stop customer claims and event dispatch, then drop the partial unique index and
-- attribution_ref column. Existing v2 evidence is append-only and intentionally remains on Kafka.
ALTER TABLE promo_reservation
  ADD COLUMN attribution_ref UUID;

CREATE UNIQUE INDEX promo_reservation_attribution_ref_uq
  ON promo_reservation(attribution_ref)
  WHERE attribution_ref IS NOT NULL;
