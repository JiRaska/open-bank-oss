-- Read fixtures for the sanctions read-baseline lane (.github/workflows/perf-gate.yml).
--
-- The table is created by Flyway (V1), so this file only adds screening checks:
--   * one CLEAR check at a FIXED id (00000000-0000-0000-0000-0000000000e2) probed by the
--     detail read, and
--   * 199 more checks — 100 CLEAR, 60 POTENTIAL_HIT, 40 HIT — so the checks, pending and
--     hits lists page real rows.
--
-- Every row is /api/v1-compatible on purpose: profile, country and bankIdentifier stay
-- NULL and checkedLists uses only V1 list types, so isV1Compatible() keeps the rows in
-- the v1 responses this lane probes (SanctionsCheckVersioning).
--
-- idempotency_key is UNIQUE, so every row gets its own key. A re-load into the same
-- throwaway database is a no-op-ish duplicate key rather than a second fixture set; the
-- gate loads fixtures exactly once.

-- Fixed detail-probe check first.
INSERT INTO sanctions_checks (id, idempotency_key, entity_type, name, aliases,
                              status, matches, overall_score, checked_lists)
VALUES ('00000000-0000-0000-0000-0000000000e2',
        'perf-sanctions-detail-fixture',
        'INDIVIDUAL',
        'Perf Screened Person',
        '[]',
        'CLEAR',
        '[]',
        0.0,
        '["OFAC_SDN"]')
ON CONFLICT (id) DO NOTHING;

-- 199 more checks across the three statuses the list endpoints filter on.
INSERT INTO sanctions_checks (id, idempotency_key, entity_type, name, aliases,
                              status, matches, overall_score, checked_lists)
SELECT ('00000000-0000-0000-0000-' || lpad(to_hex(g), 12, '0'))::uuid,
       'perf-sanctions-fixture-' || lpad(to_hex(g), 12, '0'),
       'INDIVIDUAL',
       'Perf Screened Person ' || g,
       '[]',
       CASE WHEN g <= 100 THEN 'CLEAR' WHEN g <= 160 THEN 'POTENTIAL_HIT' ELSE 'HIT' END,
       '[]',
       CASE WHEN g <= 100 THEN 0.0 WHEN g <= 160 THEN 0.6 ELSE 0.95 END,
       '["OFAC_SDN"]'
FROM generate_series(3, 201) AS g
ON CONFLICT (id) DO NOTHING;
