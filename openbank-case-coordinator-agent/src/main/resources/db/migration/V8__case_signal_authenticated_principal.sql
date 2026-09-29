-- SPDX-License-Identifier: AGPL-3.0-only
-- Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
-- A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
-- See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
--
-- Preserve who authenticated separately from the AI identity they were permitted to assert.
-- Rollback: ALTER TABLE case_signal_evidence DROP COLUMN authenticated_principal;

ALTER TABLE case_signal_evidence
    ADD COLUMN authenticated_principal VARCHAR(255) NOT NULL DEFAULT 'legacy-unknown';
