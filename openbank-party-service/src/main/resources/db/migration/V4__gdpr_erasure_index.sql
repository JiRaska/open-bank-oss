-- SPDX-License-Identifier: MPL-2.0
-- Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.

CREATE INDEX IF NOT EXISTS idx_parties_status ON parties(status);
CREATE INDEX IF NOT EXISTS idx_parties_updated_at ON parties(updated_at);
