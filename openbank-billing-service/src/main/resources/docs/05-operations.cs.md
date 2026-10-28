# 05 — Provoz

## Varování při vyhodnocení poplatků

Varování o nedostupných podmínkách revize, selhání úložiště pinů, vyhledání v katalogu a
vynechaných složkách poplatků mají pevný text. Neobsahují účet, produkt, cyklus, revizi,
podrobnosti výjimky ani počty složek. Zaznamenané důvody přeskočení určuje omezený štítek
`reason` čítače `openbank.billing.assessment.skipped`; čítač nepokrývá všechna vynechaná vyhodnocení.
`openbank.billing.assessment.dropped_components` počítá vyhodnocení s jednou či více
vynechanými složkami, nikoli samotné složky. Tyto souhrnné čítače neurčují konkrétní vyhodnocení;
dotčené záznamy vyšetřujte přes oprávněný přístup k obchodním datům, nikoli provozní logy.

## Které cenové složky se účtují (#13230)

Revize, jejíž cenové složky nesou `role` z katalogu, se účtuje podle role. Účtuje se jen `FEE`; `LIMIT`,
`THRESHOLD` ani `RATE` nikdy. Obsahuje-li revize také dokument v1, účtuje se `FEE` jen tehdy, když jeho kód
odvozuje dokument v1, a ponechá si id tohoto poplatku v1. Idempotenční klíč (cyklus, účet, id poplatku,
měna) proto zůstane stejný i při přepnutí účtu na jinou revizi uprostřed cyklu a nic se nezaúčtuje dvakrát.
`FEE` s jakýmkoli jiným kódem se vynechá a započítá jako
`openbank.billing.fee.component.dropped{reason=NOT_IN_LEGACY_DOCUMENT}`. Revize bez rolí se účtuje
starým párováním proti vloženým poplatkům v1, přesně jako dřív.

Vynechaný poplatek `NOT_IN_LEGACY_DOCUMENT` se nikdy nezaúčtuje, takže jde o ušlý výnos při každém
vyhodnocení pod danou revizí. Hlásí ho alert `BillingFeeComponentNotBilledNotInLegacyDocument`
(warning). Nápravou je nová revize katalogu: buď poplatek doplňte do produktu v1, aby se jeho kód
odvodil, nebo publikujte revizi bez vloženého dokumentu v1. Potom dotčené účty znovu napinujte.
Postup popisuje `docs/runbooks/0031-billing-fee-not-in-legacy-document.md`.

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

S `false` obsahují řádky poplatku v **CZK** účty, na které billing vždy účtoval (2100 / 4003). Poplatek
v jiné měně uvádí třídu a roli vždy, bez ohledu na přepínač: oba pevné účty jsou v CZK a ledger je pro
jinou měnu odmítá (#13226). S `true` uvádí třídu a roli každý poplatek a rozhoduje mapa ledgeru. Výnos
z poplatku se účtuje v původní měně poplatku na 4003 (CZK), 4004 (EUR), 4005 (USD) a 4006 (GBP). Tyto
účty zakládá ledger `V34`, proto ho nasaďte před tímto vydáním Billingu. Poplatek v jakékoli jiné měně se
do outboxu nezapíše a započítá se jako
`openbank_billing_assessment_skipped_total{reason="FEE_INCOME_CURRENCY_UNMAPPED"}`. Postup pro změření
a přehrání nedoručených poplatků v jiné měně než CZK popisuje
`docs/runbooks/0030-billing-non-czk-fee-backlog.md`.

**Rollback.** S příznakem `true` řádky účet neobsahují, takže starší ledger (pod `V32`) odmítne každý
řádek. Samotnou konfigurací se vrátit nelze: nejdřív nastavte `OPENBANK_BILLING_LEDGER_POST_BY_CLASS=false` a znovu nasaďte Billing,
teprve potom vraťte ledger. Pokud se ledger vrátí jako první, každé zaúčtování selže, dokud se Billing
znovu nenasadí.

## Shadow comparator produktového IR (ADR-0352 fáze 3)

Billing dál rozhoduje každý poplatek postaru; shadow jen měří. `openbank.billing.product-ir.decision-mode` je `shadow` (výchozí a jediná přijímaná hodnota); `ir`, přepnutí podle ADR-0352 §9, služba odmítne při startu, dokud není postavené. `ProductShadowScheduler` běží každých `openbank.billing.product-shadow.every` (výchozí 5 minut, ve výchozím stavu zapnutý) a znovu rozhoduje uložená vyměření produktovým IR připnuté revize účtu. IR a jeho podpis čte z product-catalog jednou na revizi a drží si lokální neměnnou kopii; účtovací cesta katalog kvůli tomu nikdy nevolá.

Ověření podpisu řídí `openbank.billing.product-shadow.verification.mode`: `disabled` (výchozí; nic se neporovná a každý vstup s IR se započte jako `verification_disabled`), `openbao` (Transit `verify` na klíči `product-ir-publish`; role billingu smí mít jen `verify`) nebo `hmac` (jen dev a test; produkční start s ním odmítne nastartovat).

Každý IR `Post` se před porovnáním kontroluje na zachování peněz; nevyvážený, nebo vyhodnocení, které interpret odmítne, se počítá jako `ir_evaluation_failed` a nikdy se neporovná. Metriky: `openbank_product_shadow_inputs_total`, `openbank_product_shadow_compared_total{product,rule_id}`, `openbank_product_shadow_diverged_total{product,rule_id}` a `openbank_product_shadow_not_comparable_total{reason}`, všechny s `consumer="billing"`. Alerty: `ProductShadowStalled` (vstupy ve frontě, žádný zpracovaný za 6 hodin), `ProductShadowComparedNothing` (revize s IR, žádné porovnání za 24 hodin) a `ProductShadowDiverged`. Pokud jsou všechny vstupy `no_ir`, žádný alert nevznikne: takové produkty ještě nejsou převedené. Na IR smí billing přejít až po nulové divergenci za celou kadenci každého pravidla poplatku (ADR-0352 §9), za přepínačem, který zatím neexistuje.

Chybějící zůstatek se při vyhodnocení plánovaného triggeru závislého na zůstatku nikdy nenahrazuje naměřenou nulou. Takový poplatek zaznamená `input_unknown` v čítači neporovnatelných vstupů a stav fronty `INPUT_UNKNOWN`, bez zvýšení čítačů porovnání či odchylek. Poplatky, jejichž trigger zůstatek nepotřebuje, zůstávají porovnatelné.

## Účast na uzávěrce dne (ADR-0342, #13167)

Billing hlásí každý dokončený účetní den (pražský čas) do ledger-service jako účastník `billing`
(`BillingCloseParticipationScheduler`, každou hodinu v :50; zpětné okno 3 dny) pod vlastní identitou
(`m2m`, Keycloak `openbank-billing`); ledger to autorizuje jako `ledger.close.participate` jen pro `billing`.

- Cyklus není splatný nebo je sweep vypnutý: SKIPPED s důvodem.
- Splatný cyklus bez záznamu běhu, nebo poplatek cyklu stále PENDING: zatím nic, ledger den drží.
- Cyklus proběhl a všechny poplatky jsou zaúčtované: RAN.
- Selhané účty, přerušený sweep nebo poplatek ve stavu FAILED: REFUSED s pevnou kategorií.

**Zmeškaný cyklus.** `BillingCycleScheduler.catchUp` (každých 15 min, poprvé 2 min po startu) spustí
sweep pro den splatnosti, pokud cron v tomto měsíci už proběhl a žádný běh cyklu není zaznamenán;
idempotentně podle cycle id a nikdy souběžně s běžícím sweepem.

**Poplatek odmítnutý do uzavřeného dne.** `409 ACCOUNTING_DAY_CLOSED` označí poplatek FAILED a zvýší
`openbank_billing_fee_post_closed_day_refusals_total`; spustí se alert `BillingFeeRefusedIntoClosedDay`.
Náprava: runbook `0033-billing-fee-refused-into-closed-day.md` (přefakturace pod novým klíčem se
čtyřma očima, záznam v `billing_fee_rebill`).
