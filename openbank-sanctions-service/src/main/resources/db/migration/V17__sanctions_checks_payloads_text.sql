-- V17: Store sanctions_checks' opaque JSON payloads as text, not jsonb (#11948 follow-up).
--
-- Why: the four payload columns (aliases, identifiers, matches, checked_lists) are mapped as
-- plain String on SanctionsCheckEntity and only ever (de)serialised as JSON text in Kotlin —
-- no SQL query filters on them (status/name/score carry the indexes; the jsonb similarity
-- queries run against sanctions_list_entries, a different table via raw PgPool). Under Hibernate
-- REACTIVE, however, Vert.x decodes a jsonb value into io.vertx.core.json.JsonArray/JsonObject,
-- so reading ANY check row died at ResultSetAdaptor.getString with
-- "ClassCastException: Invalid String value type class io.vertx.core.json.JsonArray" — and
-- GET /api/v1/sanctions, /{id} and /pending answered 500 whenever at least one check existed.
-- The read path had never been exercised: the only pre-existing GET test runs against an empty
-- DB. Casting jsonb to text is lossless here (values are compact JSON texts either way) and
-- makes the wire type match the entity's String mapping. Measured 2026-10-16 by the first
-- consumer pact to read checks back (agent-service, ADR-0063).
--
-- Rollback: restore the jsonb types; the values stay valid JSON, so the cast is lossless both
-- ways (format is applied on output for jsonb, so text→jsonb re-parses cleanly):
--   ALTER TABLE sanctions_checks
--     ALTER COLUMN aliases      TYPE jsonb USING aliases::jsonb,
--     ALTER COLUMN identifiers  TYPE jsonb USING identifiers::jsonb,
--     ALTER COLUMN matches      TYPE jsonb USING matches::jsonb,
--     ALTER COLUMN checked_lists TYPE jsonb USING checked_lists::jsonb;

ALTER TABLE sanctions_checks
    ALTER COLUMN aliases       TYPE text USING aliases::text,
    ALTER COLUMN identifiers   TYPE text USING identifiers::text,
    ALTER COLUMN matches       TYPE text USING matches::text,
    ALTER COLUMN checked_lists TYPE text USING checked_lists::text;
