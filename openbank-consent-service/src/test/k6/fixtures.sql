-- SPDX-License-Identifier: Apache-2.0
-- Perf read-baseline fixture for openbank-consent-service (perf-gate.yml lane).
--
-- Loaded into the throwaway PostgreSQL after the service boots (Flyway has created the
-- schema by then) and discarded with the container. The k6 script never mutates it. The
-- party and grantee ids are the script's own defaults; without an ACTIVE consent between
-- them the active-consent resolve answers 404 (rejection evidence, not a handler baseline).
INSERT INTO consents (id, party_id, grantee_id, grantee_type, grantee_name, status, valid_from, valid_to)
VALUES (
    '00000000-0000-0000-0000-0000000000d1',
    '00000000-0000-0000-0000-000000000001',
    'tpp-perf-baseline',
    'TPP',
    'Perf baseline TPP',
    'ACTIVE',
    NOW() - INTERVAL '1 day',
    NOW() + INTERVAL '89 days'
);
