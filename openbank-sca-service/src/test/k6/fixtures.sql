-- SPDX-License-Identifier: Apache-2.0
-- Perf read-baseline fixture for openbank-sca-service (perf-gate.yml lane).
--
-- Loaded into the throwaway PostgreSQL after the service boots (Flyway has created the
-- schema by then) and discarded with the container. The k6 script only reads it. The
-- challenge and party ids are the script's own defaults; without the row the challenge
-- detail probe answers 404 (rejection evidence, not a handler baseline). The expiry is far
-- out so the row stays PENDING for the whole ramp.
INSERT INTO sca_challenges (id, party_id, purpose, method, status, expires_at)
VALUES (
    '00000000-0000-0000-0000-000000000003',
    '00000000-0000-0000-0000-000000000001',
    'LOGIN',
    'TOTP',
    'PENDING',
    NOW() + INTERVAL '1 day'
);
