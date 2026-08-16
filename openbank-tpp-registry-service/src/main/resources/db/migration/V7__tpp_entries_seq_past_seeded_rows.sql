-- SPDX-License-Identifier: Apache-2.0
-- Advance tpp_entries_seq past the rows V1 seeded, so a registration can insert at all.
--
-- V1__init.sql creates `id BIGSERIAL PRIMARY KEY` and then seeds three TPPs, which take ids 1..3
-- from the implicit BIGSERIAL sequence `tpp_entries_id_seq`. V4__hibernate_sequences.sql then
-- created the sequence Panache actually allocates from — `tpp_entries_seq` — with the default
-- START 1, and nothing ever reconciled the two. So the FIRST TPP ever registered through
-- `POST /api/v1/tpp-registry` allocates an id inside the block that already contains the seeded
-- rows and dies with:
--
--   duplicate key value violates unique constraint "tpp_entries_pkey" (23505)
--
-- surfacing as a 500 from the endpoint. It is not a race and not environment-specific: it is
-- deterministic on any database that ran V1's seed, which is every one of them.
--
-- Why nobody has hit it: measured on the sandbox 2026-08-16, `tpp_entries` holds exactly the three
-- seeded rows, `tpp_entries_id_seq.last_value = 3`, and `tpp_entries_seq` reads
-- `last_value = 1, is_called = f` — the sequence Hibernate uses has NEVER been called, in the
-- whole life of the service. Registration has therefore never been exercised in a deployed
-- environment, and no test covered it either: the service's ITs read and boot, and the unit tests
-- mock the repository, so the only thing that could see this is a real-DB write test. Adding one
-- (TppOutboxWriteIT, issue #4007) is what found it.
--
-- setval to MAX(id) rather than a literal: with is_called = true the next `nextval` returns
-- MAX(id) + 50, so the pooled block Hibernate hands out starts strictly above every existing row
-- under either the `pooled` or the `pooled-lo` optimizer. GREATEST(..., 1) keeps it legal on an
-- empty table, where the sequence minimum is 1.
--
-- Rollback: SELECT setval('tpp_entries_seq', 1, false);

SELECT setval(
    'tpp_entries_seq',
    GREATEST((SELECT COALESCE(MAX(id), 0) FROM tpp_entries), 1)
);
