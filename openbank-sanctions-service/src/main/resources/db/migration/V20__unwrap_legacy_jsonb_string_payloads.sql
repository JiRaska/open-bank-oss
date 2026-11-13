-- SPDX-License-Identifier: Apache-2.0
-- Rows written before the reactive JSONB fix stored each payload column as a jsonb STRING
-- scalar holding the JSON text ("[\"OFAC_SDN\"]") instead of the array/object itself.
-- The entity reads these columns with `::text`, so such a row yields a quoted string that
-- Jackson cannot bind to List/Map, and GET /api/v1/sanctions answered 400 for every caller
-- while any one of them existed. Unwrap each scalar into the JSON value it encodes.
-- A string scalar is never a legitimate value for these columns (arrays and objects only),
-- and an inner text that is not valid JSON aborts the migration rather than being kept.
-- Rollback: none needed. The unwrapped value is the one current code writes and reads;
-- older application code reads it identically, so rolling back code needs no data change.
UPDATE sanctions_checks SET aliases = (aliases #>> '{}')::jsonb
WHERE jsonb_typeof(aliases) = 'string';

UPDATE sanctions_checks SET identifiers = (identifiers #>> '{}')::jsonb
WHERE jsonb_typeof(identifiers) = 'string';

UPDATE sanctions_checks SET matches = (matches #>> '{}')::jsonb
WHERE jsonb_typeof(matches) = 'string';

UPDATE sanctions_checks SET checked_lists = (checked_lists #>> '{}')::jsonb
WHERE jsonb_typeof(checked_lists) = 'string';
