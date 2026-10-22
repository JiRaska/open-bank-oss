# Prahy fraud pravidel

Pravidla podle částky (`large-single-transaction`, `velocity-h1-amount`, `new-payee-high-amount`) porovnávají platby s peněžními prahy pro každou měnu. Od ADR-0347 jsou tyto prahy verzovaná data v tabulce `fraud_threshold_set`, ne konstanty v Kotlinu. Logika pravidel zůstává v kódu.

## Která sada platí

Každé skórování čte aktivní sadu z tabulky: nejvyšší verzi ve stavu `EXECUTED`, jejíž `active_from` není v budoucnu. Vyšší verze vždy nahrazuje nižší, i takovou, která je naplánovaná na pozdější datum. Nic se necachuje, takže schválení platí na všech replikách od svého `active_from`. Každý řádek `fraud_scores` zaznamenává v `threshold_set_version` verzi, podle které byl vyhodnocen.

Verzi 1 zakládá `V9__fraud_threshold_set.sql`. Obsahuje přesně hodnoty, které pravidla měla jako konstanty (CZK 500 000 / EUR 20 000, CZK 1 000 000 / EUR 40 000 za hodinu, CZK 250 000 / EUR 10 000 pro nového příjemce), takže se nasazením verdikty nezměnily.

## Změna prahu (čtyři oči)

1. Uživatel z rizik nebo compliance (`ROLE_RISK`, `ROLE_COMPLIANCE`) pošle `POST /api/v1/fraud/thresholds/proposals` s celou novou verzí: všechna tři pravidla, částky jako desetinné řetězce, verze vyšší než všechny živé a volitelně `effectiveFrom`. Tím se žádný verdikt nezmění.
2. Jiný uživatel zavolá `POST /api/v1/fraud/thresholds/proposals/{id}/decide`. Navrhovatel nemůže schválit vlastní návrh: služba vrátí 422 a odmítne to i CHECK constraint v databázi.
3. Po schválení sada platí od pozdějšího z `effectiveFrom` a okamžiku schválení. Zpětné datování není možné.

`GET /api/v1/fraud/thresholds/proposals/pending` vypíše otevřené návrhy. `GET /api/v1/fraud/thresholds/active` ukáže platnou sadu. Operátoři a strojové identity tyto endpointy používat nemohou.

## Záznam nelze smazat

Každý návrh i rozhodnutí v tabulce zůstává. Databázová role služby smí záznamy číst, přidat návrh a zapsat rozhodnutí, nic víc. Nemůže smazat verzi, vyprázdnit tabulku ani změnit částky schválené sady nebo to, kdo ji navrhl.

## Selhání do bezpečného stavu

- Měna, která chybí v mapě pravidla, způsobí, že pravidlo pro tuto měnu vrátí REVIEW (`…-unmapped-currency`).
- Bez aktivní sady vrátí všechna pravidla podle částky REVIEW a verdikt nese důvod `threshold-set-unavailable`. `GET …/active` pak vrací 404.

REVIEW posílá platbu analytikovi. Engine zde nikdy nevrací DECLINE, protože chyba konfigurace nemá odmítnout každou platbu zákazníka.
