-- SPDX-License-Identifier: Apache-2.0
-- History queries order by validFrom DESC for a given pair + source.
-- CONCURRENTLY is not valid inside a Flyway migration transaction; plain CREATE INDEX is used instead.
CREATE INDEX IF NOT EXISTS idx_fx_rates_history
    ON fx_rates (base_currency, quote_currency, source, valid_from DESC);
