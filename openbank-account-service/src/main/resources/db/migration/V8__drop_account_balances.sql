-- N3 (ADR-0024): operational balances are owned by the balance-service, the single source of truth.
-- account-service no longer keeps a duplicate balance table; structure (pockets) stays, money moves out.
DROP TABLE IF EXISTS account_balances;
