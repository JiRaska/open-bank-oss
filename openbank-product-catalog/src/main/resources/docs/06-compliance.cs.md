# Compliance

> **Poznámka k rozsahu:** produktový katalog drží **pouze referenční data — žádná osobní data a neteče přes něj žádný kapitál**. **Není** to služba na peněžní cestě (není v `rules.yaml: money_path_services`), takže **nevyžaduje** bránu 2 schválení + threat-model (ADR-0030). Jeho compliance relevance je **transparentnost cen a přesnost informací o produktu**, ne zpracování transakcí či PII.

## Regulatorní rámec

| Regulace | Vztah k této službě | Implementace / stav |
|---|---|---|
| **GDPR** | Nezpracovává ani neukládá žádná osobní data. | `dataClassification: internal`; pouze produktová/cenová referenční data — žádné PII, žádná dimenze subjektu údajů. |
| **DORA** (Reg. (EU) 2022/2554) | Provozní odolnost ICT závislosti používané při onboardingu a billingu. | SmallRye Health probes, PostgreSQL perzistence, `BuildInfo`/`/api/v1/info`, runbooky a atomický důkaz každé v2 změny v auditu/outboxu. Důkaz obnovy zůstává delivery prací. |
| **NIS2** | Bezpečnost sítí a informací. | mTLS v clusteru (Istio), CORS allowlist + bezpečnostní response hlavičky (CSP, HSTS, X-Frame-Options) v `application.yaml`. |
| **Směrnice o spotřebitelském úvěru (2008/48/ES) / CCD2 (EU) 2023/2225** | Deklarované RPSN/sazby a transparentnost poplatků pro úvěr, hypotéku, povolený debet a kreditní karty. | Katalog deklaruje `baseRate`, `overdraftConfig` a sazebník. Mutabilní v1 `versionHistory` není auditní důkaz; ADR-0257 vyžaduje neměnné schválené revize. |
| **PAD — směrnice o platebních účtech (2014/92/EU)** | Srovnatelné informace o poplatcích pro platební účty. | `GET /api/v1/fees` poskytuje jednotný, strukturovaný, filtrovatelný sazebník (zdrojová data FID). |
| **MiFID II** | Informace o investičních produktech. | `INVESTMENT_BASIC` modelován jako DRAFT/neveřejný; deklarovány poplatky za správu/transakci. (Spuštění investic je mimo rozsah, dokud produkt není publikován.) |
| **ČNB pravidla ochrany spotřebitele / transparentnosti** | Přesné informace o produktu a ceně pro český trh. | CZK produkty (`CURRENT_CZK`, `SAVINGS_CZK`, `TERM_DEPOSIT_6M_CZK`) nesou obchodní podmínky v češtině. |

## Mapování GDPR

Tabulka právního základu / práv subjektu údajů se v obvyklém smyslu neuplatní: **v této službě nejsou žádná osobní data**. Katalog ukládá definice produktů a ceny (komerční/interní data). Pokud by budoucí funkce připojila cenotvorbu nebo rozhodnutí o způsobilosti specifická pro zákazníka, toto zpracování by patřilo do jiné služby (offer/eligibility) a tato sekce by se přepracovala.

| Aspekt GDPR | Uplatnění zde |
|---|---|
| Kategorie osobních údajů | Žádné |
| Právní základ | N/A (žádná osobní data) |
| Práva subjektu údajů | N/A (žádné subjekty údajů) |
| Retence | `indefinite` pro historii produktů/verzí — důkaz transparentnosti, žádné PII k výmazu |
| Mezinárodní přenosy | Žádné (žádná osobní data nikam neopouštějí) |

## Datové toky

```
admin-ui  ──GET/POST/PUT /products, GET /fees──►  product-catalog
account / interest / fx / card služby  ──čtení definic produktů──►  product-catalog
```

- Všechny toky jsou **uvnitř OpenBank, referenční data**. Žádná osobní data, žádný pohyb peněz, žádná externí (TPP/PSD2) expozice.
- Katalog **neprovádí žádná downstream volání**. V2 zapisuje transportně neutrální události do outboxu; brokerový dispatcher zatím není součástí služby.

## Mapování DORA (Reg. (EU) 2022/2554)

| Článek | Téma | Implementace |
|---|---|---|
| Čl. 5/6 | Rámec řízení ICT rizik | závislost centralizovaná na openbank-libs; služba v governance katalogu (ADR-0029). |
| Čl. 9 | Ochrana & prevence | bezpečnostní hlavičky, CORS allowlist; čelní gateway. |
| Čl. 9 | Identifikace | `BuildInfo` (gitCommit, buildTime, version) na `/api/v1/info`. |
| Čl. 10 | Detekce | metriky + health probes. |
| Čl. 11 | Odezva & obnova | runbooky v [05 — Provoz](./05-operations.md); trvalý stav vlastní PostgreSQL a seeder nepřepisuje neprázdné úložiště. Důkaz obnovy zůstává mezerou. |
| Čl. 28 | Riziko třetích stran | žádný third-party SaaS — self-hosted. |

## Bezpečnostní kontroly

- Validace vstupu: vynucena povinná pole `ProductRequest`; neznámé id produktu → 404; duplicitní code → 409.
- Kódování výstupu: Jackson (automaticky).
- Bezpečnostní hlavičky: CSP `default-src 'self'`, HSTS, `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, `Referrer-Policy`, `Permissions-Policy` — nastaveny v `application.yaml`.
- CORS: omezený allowlist (jen origins admin-ui).
- TLS: mTLS v clusteru (Istio), TLS terminace na gateway.
- AuthN/AuthZ: služba validuje OIDC bearer tokeny; čtení vyžaduje autentizaci a mutace OPERATOR/ADMIN. `@Authorize` je přítomno, ale OPA je advisory, dokud deployment nedodá vynucující policy profil.
- Audit: každá přijatá v2 změna zapisuje audit a verzovanou událost ve stejné transakci; publikace navíc zapisuje maker-checker schválení. Neměnnost chrání databázové triggery. Doručení outboxu do brokeru je samostatný transportní follow-up.

## Známé mezery / follow-upy (zralost)

- Vynucovaný OPA profil v bankovním deploymentu a provider-neutral scopes pro samostatné OIDC.
- Dispatcher a provozní telemetrie pro doručování transportně neutrálního outboxu.
- Sjednocení sdílené chybové obálky (RFC-7807 problem+json).

Tyto jsou rámovány jako roadmapa zralosti služby, ne jako zneužitelné specifikace.
