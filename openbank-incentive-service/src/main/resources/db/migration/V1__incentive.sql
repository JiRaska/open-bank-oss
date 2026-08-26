-- Forward-only. Rollback: remove the incentive deployment first, export audit evidence,
-- then DROP the six new tables; no existing schema or money-path data is modified.
CREATE TABLE incentive_offer (
  id UUID PRIMARY KEY,
  name VARCHAR(160) NOT NULL,
  version INTEGER NOT NULL CHECK (version > 0),
  product_scope TEXT NOT NULL,
  effective_from TIMESTAMPTZ NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  total_limit INTEGER NOT NULL CHECK (total_limit > 0),
  per_party_limit INTEGER NOT NULL CHECK (per_party_limit > 0),
  stacking_policy VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL,
  maker VARCHAR(255) NOT NULL,
  checker VARCHAR(255),
  created_at TIMESTAMPTZ NOT NULL,
  published_at TIMESTAMPTZ,
  UNIQUE (name, version),
  CHECK (effective_from < expires_at),
  CHECK (per_party_limit <= total_limit)
);

CREATE TABLE promo_code_inventory (
  digest CHAR(64) PRIMARY KEY,
  offer_id UUID NOT NULL REFERENCES incentive_offer(id),
  status VARCHAR(24) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  retained_until TIMESTAMPTZ NOT NULL
);

CREATE TABLE promo_reservation (
  id UUID PRIMARY KEY,
  offer_id UUID NOT NULL REFERENCES incentive_offer(id),
  offer_name VARCHAR(160) NOT NULL,
  offer_version INTEGER NOT NULL,
  code_digest CHAR(64) NOT NULL REFERENCES promo_code_inventory(digest),
  party_ref VARCHAR(255) NOT NULL,
  product_ref VARCHAR(255) NOT NULL,
  idempotency_key VARCHAR(255) NOT NULL UNIQUE,
  status VARCHAR(24) NOT NULL,
  reserved_at TIMESTAMPTZ NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  committed_at TIMESTAMPTZ,
  released_at TIMESTAMPTZ
);
CREATE INDEX promo_reservation_offer_status_idx ON promo_reservation(offer_id, status);
CREATE INDEX promo_reservation_party_status_idx ON promo_reservation(offer_id, party_ref, status);

CREATE TABLE incentive_audit_event (
  id UUID PRIMARY KEY,
  aggregate_id UUID NOT NULL,
  event_type VARCHAR(100) NOT NULL,
  actor VARCHAR(255) NOT NULL,
  occurred_at TIMESTAMPTZ NOT NULL,
  details TEXT NOT NULL
);

CREATE TABLE incentive_outbox (
  id UUID PRIMARY KEY,
  aggregate_id UUID NOT NULL,
  event_type VARCHAR(100) NOT NULL,
  payload TEXT NOT NULL,
  occurred_at TIMESTAMPTZ NOT NULL,
  published_at TIMESTAMPTZ
);
