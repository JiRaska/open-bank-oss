# 05 — Provoz

## Nasazení účetních tříd v ledgeru (ADR-0341 D3)

Billing umí účtovat podle účetní třídy (GL účet určí ledger) nebo s explicitními GL účty.
Volí to `openbank.billing.ledger.post-by-class` (env `OPENBANK_BILLING_LEDGER_POST_BY_CLASS`); výchozí hodnota je `false` a posílá explicitní payload, kterému rozumí
každý ledger.

Pořadí nasazení, vždy:

1. Nasaďte ledger s migrací `V32__accounting_class_gl_map.sql` a ověřte, že odpovídá na
   `GET /api/v1/accounting-classes` (200).
2. Teprve potom nastavte `OPENBANK_BILLING_LEDGER_POST_BY_CLASS=true` pro Billing a službu restartujte. Příznak se čte při startu.

Nasazení Billing před ledgerem je bezpečné, dokud je příznak `false`.

S `false` řádky poplatků obsahují účty, na které billing vždy účtoval (2100 / 4003). S `true` uvádějí třídu
a roli a rozhoduje mapa ledgeru; poplatky v měně bez namapovaného účtu výnosů z poplatků ledger odmítne
(422, sleduje #13226), stejně jako je odmítal dříve.

**Rollback.** S příznakem `true` řádky účet neobsahují, takže starší ledger (pod `V32`) odmítne každý
řádek. Samotnou konfigurací se vrátit nelze: nejdřív nastavte `OPENBANK_BILLING_LEDGER_POST_BY_CLASS=false` a znovu nasaďte Billing,
teprve potom vraťte ledger. Pokud se ledger vrátí jako první, každé zaúčtování selže, dokud se Billing
znovu nenasadí.
