-- Arranged (povolený) overdraft limit per balance (N2 / ADR-0024). The available/booked balance
-- may be drawn down to -arranged_overdraft_limit; beyond that is an unarranged (nepovolený)
-- overdraft and is rejected at cover time. The drawn overdraft is a credit exposure that must be
-- reported (AnaCredit, 25k EUR threshold) and reclassified to a receivable GL by the ledger.
ALTER TABLE balances
    ADD COLUMN arranged_overdraft_limit NUMERIC(19,4) NOT NULL DEFAULT 0;
