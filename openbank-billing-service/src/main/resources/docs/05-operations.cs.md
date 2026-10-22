# 05 — Provoz

## Varování při vyhodnocení poplatků

Varování o nedostupných podmínkách revize, selhání úložiště pinů, vyhledání v katalogu a
vynechaných složkách poplatků mají pevný text. Neobsahují účet, produkt, cyklus, revizi,
podrobnosti výjimky ani počty složek. Zaznamenané důvody přeskočení určuje omezený štítek
`reason` čítače `openbank.billing.assessment.skipped`; čítač nepokrývá všechna vynechaná vyhodnocení.
`openbank.billing.assessment.dropped_components` počítá vyhodnocení s jednou či více
vynechanými složkami, nikoli samotné složky. Tyto souhrnné čítače neurčují konkrétní vyhodnocení;
dotčené záznamy vyšetřujte přes oprávněný přístup k obchodním datům, nikoli provozní logy.

## Přechod na prefix metrik billingu

Sedm čítačů billingu nyní používá prefix Micrometer `openbank.billing.*`. Jejich koncovky jsou
`assessment.no_fee_identity`, `assessment.dropped_components`, `assessment.skipped{reason}`,
`revision_pin.fallback_to_current`, `accounting_class.derived{source}`,
`fee.component.dropped{reason}` a `fee.post.legacy_payload`. Prometheus převádí tečky na
podtržítka (a přidává příponu čítače); před nasazením nového billing image aktualizujte externí
dashboardy, recording rules a alerty. V tomto repozitáři není evidován žádný čtenář sedmi
starých řad. Změna názvu či reset čítače nedokazuje, že příslušný stav billingu přestal nastávat.
Čítač dropped-components roste jednou za dotčené vyhodnocení, nikoli za každou složku zvlášť.

Vrácení billing image obnoví staré názvy řad. Po dobu přechodu ponechte v externích dotazech
oba selektory; starý odstraňte až po ověření nové řady na běžící instanci. Tato změna metrik
nezapíná účtování a nemění rozhodování o poplatcích.

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
