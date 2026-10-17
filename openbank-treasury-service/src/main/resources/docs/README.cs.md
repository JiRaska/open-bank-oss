# openbank-treasury-service — Dokumentace

> **Co to je:** vlastní obchody banky na peněžním trhu (ADR-0315): umístění, přijaté půjčky, depozitní facilita ČNB, lombard ČNB a FX spot. Obchod prochází stavy `DRAFT → PENDING_APPROVAL → BOOKED → CONFIRMED → SETTLED → MATURED`, případně `CANCELLED`, přičemž princip čtyř očí odděluje osoby, které obchod vytvoří, předloží, schválí a potvrdí.

Tuto dokumentaci publikuje služba na management endpointu `/q/openbank/docs` (Docs-as-Service, ADR 0019).

[Kapitola architektury](./02-architecture.cs.md) sleduje obchod přes schválení, účtování do ledgeru a párování výpisu. Generovaná kapitola **API surface** se při každém buildu image obnovuje z `openapi.yaml`; úplná schémata zůstávají na `/q/openapi`.

## TL;DR

- **Technologie:** Kotlin / Quarkus 3.x / PostgreSQL (migrace Flyway)
- **Porty:** 8160 (aplikace), 8090 (management)
- **Autorizace:** dealeři, schvalovatelé a senior schvalovatelé podle rolí (`ROLE_TREASURY_DEALER`, `ROLE_TREASURY_APPROVER`, `ROLE_TREASURY_SENIOR_APPROVER`, `ROLE_ADMIN`). Nelidský principál nemůže provést krok vyhrazený člověku (403).

## Limity (ADR-0315 D4)

Dvě rodiny limitů se kontrolují při předložení obchodu a znovu při jeho schválení, protože expozice i mandáty se mezitím mohou změnit.

- **Limit protistrany.** Zaznamenává se při předložení; porušení blokuje zaúčtování (422 `LIMIT_BREACHED`), pokud senior schvalovatel nezaznamenal výjimku s důvodem, který porušení stále pokrývá (`POST /deals/{id}/override-limit`).
- **Produktový limit.** Mandát banky pro daný produkt, nezávislý na protistraně. Je **vynucován**: při předložení obchod mimo mandát nikdy nedojde do fronty schvalovatele a při schválení se vyhodnotí znovu proti mandátu tak, jak je deklarován nyní. Porušení je 422 `PRODUCT_LIMIT_BREACHED` a odpověď nese `breaches[]`, jednu položku za každé porušené pravidlo s deklarovaným `limit` a skutečnou hodnotou obchodu `actual`. Limit **nelze přebít výjimkou**: senior výjimka pokrývá pouze limit protistrany. Obchodovat mimo mandát znamená změnit deklarovaný mandát, a to v rámci revize.

### Deklarace produktových limitů

Produktové limity jsou deklarovány jako kód v `application.yaml` pod `openbank.treasury.product-limits`, jedna položka na produkt (`MM_PLACEMENT`, `MM_BORROWING`, `CNB_DEPOSIT_FACILITY`, `CNB_LOMBARD`, `FX_SPOT`):

- `max-principal` — nejvyšší jistina na obchod podle měny. Množina měn JE seznam povolených měn: měna bez položky se v daném produktu obchodovat nesmí.
- `max-tenor-days` — volitelný strop doby od valuty do splatnosti v kalendářních dnech; pro `FX_SPOT`, který nemá splatnost, se neuplatňuje.

Pravidla jsou fail-closed. Produkt bez položky není povolen vůbec a neznámý název produktu v konfiguraci zabrání startu služby, takže překlep nemůže nechat skutečný produkt odmítaný jen tím, že chybí. Dodané hodnoty jsou dimenzované pro sandbox, každý strop leží na úrovni nebo nad největším syntetickým limitem protistrany, takže v sandboxu zůstává určující limit protistrany.

### Pravidla a událost o zaúčtování

Pravidla hlášená v `breaches[]` jsou `PRODUCT_NOT_PERMITTED`, `CURRENCY_NOT_PERMITTED`, `MAX_PRINCIPAL` a `MAX_TENOR`. Událost `treasury.deal.booked.v1` nese aditivní, nepovinné pole `productLimit` (`decision` = `WITHIN_LIMIT`, plus nejvyšší jistina v měně obchodu a nejdelší splatnost); zaúčtovaný obchod je vždy v rámci produktového limitu a konzumenty `v1` se to netýká.

## Kontrakt

Kontrakt API je `openapi.yaml` (`info.version` se řídí ADR-0048). Produktové limity jsou aditivní: 1.18.0, s odpovědí 422 u `submit` a `approve`.
