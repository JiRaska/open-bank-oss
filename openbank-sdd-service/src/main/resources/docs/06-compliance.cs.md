# Compliance

`openbank-sdd-service` **není** money-path služba (`rules.yaml: money_path_services` ji neuvádí) — v1 nikdy neprovádí nevratné zaúčtování. Přesto je to platebně regulovaná služba: je systémem záznamu inkasních mandátů a fail-closed bránou, která chrání plátce před neautorizovaným inkasem. Money-path klasifikace se změní, až přibude skutečné zaúčtování odepsání/refundu (fast-follow, který bude potřebovat threat model dle ADR-0030).

## Regulační rámec

| Regulace | Vztah ke službě | Implementace |
|---|---|---|
| **PSD2** (Reg. (EU) 2015/2366) | Autorizace platebních transakcí, právo plátce blokovat/limitovat (čl. 64, 79), nápravy neautorizovaných transakcí (čl. 73), právo na refund inkasa (čl. 76/77) | fail-closed `CollectionAuthorisationPolicy`; `DebtorControls` (block-all / block-list / limit částky); `RefundPolicy` (8 týdnů bezpodmínečně, 13 měsíců neautorizované) |
| **EPC SEPA Direct Debit Rulebooky** (EPC016 Core, EPC222 B2B) | životní cyklus mandátu, pravidla schématu, typy sekvence, povinnost pre-notifikace, EPC reason kódy | stavový automat `MandateLifecycle`; vznikový stav & ověření CORE vs B2B; EPC reason kódy (`MD01`, `FF05`, `MS02`, `MD06`); evidovaná pre-notifikace |
| **CZ zákon č. 370/2017 Sb. (zákon o platebním styku) §177** | lhůta pro neautorizovanou transakci v české transpozici | 13měsíční lhůta neautorizovaného refundu v `RefundPolicy` |
| **GDPR** (Reg. (EU) 2016/679) | IBAN/jméno plátce jsou PII | základ vedení záznamů má přednost před výmazem; PII omezeno na řádek mandátu + událost `collection.authorised` |
| **AMLD / vedení AML záznamů** | inkasní autorizace jsou platební záznamy | retence 7 let (`governance.yaml`) |
| **DORA** (Reg. (EU) 2022/2554) | provozní odolnost | health probes, odolný dispatch outboxu (circuit breaker / retry / DEAD parking), audit události, SLO, runbooky |
| **NIS2** | bezpečnost sítí a informací | bezpečnostní hlavičky, in-cluster TLS, OIDC auth, RBAC |

## Mapování GDPR

### Právní základ (čl. 6)

- **Smlouva** (čl. 6(1)(b)) — primárně: držení mandátu je nezbytné k provádění inkasa pro zákazníka.
- **Právní povinnost** (čl. 6(1)(c)) — sekundárně: vedení platebních záznamů (AMLD, zákon o platebním styku) vyžaduje uchování autorizace.

### Uchovávané osobní údaje

| Pole | Kde | Klasifikace |
|---|---|---|
| `debtor_iban` | `sdd_mandate`, událost `collection.authorised` | PII (finanční účet) |
| `debtor_name` | `sdd_mandate` | PII |
| `account_id` | `sdd_mandate`, každá událost | pseudonymní vazba na zákazníka |
| změněný IBAN/jméno | `sdd_mandate.amendments` (JSON) | PII |

### Práva subjektu údajů

| Právo | Aplikace |
|---|---|
| Přístup (čl. 15) | `GET /api/v1/sdd/mandates?accountId=...` vrátí mandáty subjektu |
| Oprava (čl. 16) | `PATCH /api/v1/sdd/mandates/{id}` zaznamená auditovatelnou změnu AMDT |
| Výmaz (čl. 17) | **Omezeno** — vedení platebních/AML záznamů (retence 7 let) má přednost před výmazem vypořádaného mandátu |
| Omezení (čl. 18) | `suspend` zaparkuje mandát (SUSPENDED); `cancel` jej ukončí |
| Přenositelnost (čl. 20) | data mandátu jsou strukturovaný JSON přes read API |
| Námitka (čl. 21) | právo plátce blokovat/odmítnout je implementováno jako `DebtorControls` a `cancel` |

### Toky dat ven

- → **ledger / zaúčtování platby** (Kafka `openbank.sdd.event`, `sdd.collection.authorised.v1`): `accountId`, `debtorIban`, `amount`, `currency`, `dueDate` — stejný správce, intra-OpenBank, pro navazující odepsání.
- → **audit-service** (Kafka): plné tělo události — stejný správce.
- → **notification** (Kafka): události životního cyklu mandátu.

Žádná data neopouštějí region EU/EHP.

## PSD2 — ochrana plátce

Práva plátce dle PSD2 jsou v doméně prvotřídní:

```
inkasní instrukce ─► CollectionAuthorisationPolicy (fail-closed, v pořadí)
   1. mandát přítomen & ACTIVE      ─ ne  ─► REJECT MD01
   2. shoda schématu               ─ ne  ─► REJECT MD01
   3. pouze EUR                    ─ ne  ─► REJECT FF05
   4. B2B ověřen                   ─ ne  ─► REJECT MD01
   5. jednorázový dosud nepoužit    ─ ne  ─► REJECT MD01
   6. kontroly plátce (čl. 79):
        block-all / block-list / limit částky  ─► REFUSE MS02
   jinak                                        ─► ACCEPT (emituj, deleguj zaúčtování)
```

- **REJECT** = technické odmítnutí na straně banky (závada mandátu).
- **REFUSE** = plátce uplatnil kontrolu dle PSD2 čl. 79 — blokace všech inkas, blokace konkrétního creditora, nebo limit částky na inkaso.

## Lhůty pro refund (PSD2 čl. 73/76/77, CZ §177)

| Případ | Lhůta | Výsledek |
|---|---|---|
| Autorizované **Core** inkaso | ≤ 8 týdnů (56 dní) od odepsání | `UNCONDITIONAL` refund (`MD06`) |
| Autorizované **Core** po 8 týdnech | — | nezpůsobilé |
| Autorizované **B2B** inkaso | — | žádné právo na refund po vypořádání |
| **Neautorizované** inkaso (žádný/neplatný mandát) | ≤ 13 měsíců od odepsání | `UNAUTHORISED` refund (`MD06`) |

Aritmetika je čistá, jednotkově testovaná doménová funkce (`RefundPolicy`); endpoint `refund-assessment` ji vystavuje.

## Pre-notifikace (povinnost EPC)

Povinnost creditora pre-notifikovat plátce nejméně 14 dní před datem splatnosti je **evidována, nevynucována** (`last_pre_notification_date`, `MandateLifecycle.recordPreNotification`). Chybějící pre-notifikace je v v1 doložený důvod k odmítnutí, nikoli automatický tvrdý blok.

## Mapování DORA (Reg. (EU) 2022/2554)

| Článek | Téma | Implementace |
|---|---|---|
| čl. 9 | Identifikace | `BuildInfo` (gitCommit, buildTime, version) v `/api/v1/info` |
| čl. 10 | Detekce | Prometheus metriky + alerting na chybovost, latenci, zpoždění outboxu |
| čl. 11 | Reakce a obnova | odolný outbox (circuit breaker / retry / parking `DEAD`); runbooky v `05-operations.md` |
| čl. 16 | Řízení incidentů | události životního cyklu + `collection.authorised` emitovány do audit-service |
| čl. 28 | Riziko třetích stran | žádná SaaS třetí strany — vše self-hosted |

## Auditní stopa

Každá mutace a každé akceptované inkaso vytvoří doménovou událost zapsanou do transakčního outboxu a publikovanou na `openbank.sdd.event`; `audit-service` ji persistuje. Událost nese `event_id` (idempotenční id), `aggregate_id` (mandát) a typované tělo.

## Bezpečnostní kontroly

- ✅ Validace vstupu (Bean Validation na DTO; enum-omezené hodnoty schématu/sekvence/pole/rozhodnutí)
- ✅ AuthN: Keycloak OIDC, RS256 JWT
- ✅ AuthZ: Quarkus `@RolesAllowed` — mutace gated na operator/admin/payments/service; čtení přidává viewer
- ✅ Fail-closed autorizace — defaultní rozhodnutí je reject/refuse, nikdy tiché accept
- ✅ Idempotence: registrace je idempotentní na rulebookový klíč `(CID, UMR)`; outbox `event_id` deduplikuje downstream
- ✅ Bezpečnostní hlavičky: CSP `default-src 'self'`, HSTS, X-Frame-Options DENY, nosniff, Referrer-Policy, Permissions-Policy
- ✅ Secrety: jen dev placeholdery; prod přes Vault (ADR-0017)
- ✅ Odolné eventování: circuit breaker + omezený retry + terminální parking `DEAD` pro poison řádky
- ⚠️ Money-path zaúčtování (provedení odepsání/refundu): **v v1 neimplementováno** — delegováno; fast-follow, který je přidá, bude money-path a bude vyžadovat threat model (ADR-0030)
- ⚠️ Tokenizace IBAN: neimplementováno; IBAN je uložen v čitelné podobě v řádku mandátu a v události `collection.authorised` (evidováno jako riziko regulačního auditu, konzistentně se zbytkem flotily)
