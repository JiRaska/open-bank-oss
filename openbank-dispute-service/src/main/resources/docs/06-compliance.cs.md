# Compliance

> Tato služba **není** na money path (`rules.yaml: money_path_services`) — zaznamenává workflow reklamací a vydává události, peníze nepřesouvá. Přesto je to služba **compliance domény** (`governance.yaml: dataDomain=compliance`) držící důvěrné záznamy, které tvoří regulatorní sadu důkazů.

## Regulatorní rámec

| Regulace | Vztah k této službě | Implementace |
|---|---|---|
| **PSD2** (Reg. (EU) 2015/2366) | Řešení neautorizovaných transakcí a nároků na refundaci; právo spotřebitele reklamovat a lhůta banky na vyšetření | životní cyklus reklamace, `disputeType=UNAUTHORIZED`, `resolutionDeadline` (45denní SLA), timeline stopa |
| **Pravidla kartových schémat** (chargeback) | Workflow chargeback / representment / arbitrage a okno pro podání | enum `DisputeResolution`, `chargeback-window-days=120`, `chargebackAmount` |
| **GDPR** (Reg. (EU) 2016/679) | Volný text reklamace a důkazů může obsahovat PII; party/account jsou pseudonymní UUID | data klasifikována jako důvěrná, 7letá retence přebíjí rutinní výmaz |
| **DORA** (Reg. (EU) 2022/2554) | Provozní odolnost | health probes, odolnost outboxu (CB/retry/timeout), metriky, runbooky, BuildInfo |
| **AML/CTF** (AMLD) | Reklamace může odhalit podezřelou aktivitu předanou AML | události vydávány pro downstream AML/audit; zde se neadjudikuje |
| **NIS2** | Síťová a informační bezpečnost | bezpečnostní hlavičky (CSP/HSTS), mTLS v clusteru, OIDC, auditní události |
| **Vedení záznamů na ochranu spotřebitele** | Uchovat historii reklamací | 7letá retence (`governance.yaml`) |

## Mapování GDPR

### Právní základ (čl. 6)
- **Právní povinnost** (čl. 6(1)(c)) — řešení platební reklamace a vedení záznamů je vyžadováno dle PSD2 / pravidel schémat.
- **Smlouva** (čl. 6(1)(b)) — vyšetření reklamace je součástí plnění smlouvy o platebních službách.

### Práva subjektu údajů

| Právo | Aplikace |
|---|---|
| Přístup (čl. 15) | `GET /api/v1/disputes/account/{accountId}` vrací reklamace subjektu |
| Oprava (čl. 16) | opravy přes admin UI (`PUT`), připojené do timeline |
| Výmaz (čl. 17) | **Omezeno** — regulatorní vedení záznamů (7 let) přebíjí rutinní výmaz uzavřených reklamací |
| Omezení (čl. 18) | stav může držet reklamaci v neřešícím stavu |
| Přenositelnost (čl. 20) | reklamace + důkazy + timeline jsou exportovatelné jako JSON (`evidenceExported: true`) |
| Námitka (čl. 21) | N/A — žádné marketingové zpracování |

### Toky dat ven
- → **audit-service** (Kafka, `openbank.disputes.dispute.event`): plný payload události reklamace — stejný správce, intra-OpenBank.
- → **notification**: notifikace o změně stavu zákazníkovi — stejný správce.
- → **card-issuance-service** (deklarovaný downstream, vztah `blocks`): reklamace může spustit blokaci karty.
- **Soubory** s důkazy se nepřenášejí — pouze ukazatel `file_reference`.

Žádná data neopouštějí region EU/EHP.

### Retence (čl. 5(1)(e))

| Záznam | Retence |
|---|---|
| Otevřená / probíhající reklamace | po dobu života reklamace |
| Uzavřená / vyřešená / stažená reklamace | **7 let** od vyřešení (`governance.yaml: retentionPolicy`) |
| Reklamace navázaná na AML případ | sladěno s pozdržením AML případu |

## Mapování DORA (Reg. (EU) 2022/2554)

| Článek | Téma | Implementace |
|---|---|---|
| čl. 5/6 | Rámec řízení ICT rizik | závislost na centralizovaných `openbank-libs`; konvenční plugin |
| čl. 9 | Identifikace | `BuildInfo` (gitCommit, buildTime, verze) přes `/api/v1/info` |
| čl. 10 | Detekce | metriky Micrometer/Prometheus, trasování OpenTelemetry |
| čl. 11 | Odezva a obnova | outbox circuit-breaker/retry/timeout; runbooky v `05-operations.md` |
| čl. 16/17 | Řízení a hlášení incidentů | doménové události streamovány do audit-service jako důkaz |
| čl. 28 | Riziko třetích stran | žádné third-party SaaS — vše self-hosted |

## Bezpečnostní kontroly

- ✅ AuthN: Keycloak OIDC, RS256 JWT (realm `openbank`)
- ✅ AuthZ: Quarkus `@RolesAllowed` (role pro čtení vs mutace) + `@Authorize` (OPA, poradní — ADR-0034)
- ✅ Bezpečnostní hlavičky: CSP, HSTS, `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, Referrer/Permissions-Policy
- ✅ Rate limiting: `openbank.rate-limit` (max 200 souběžných)
- ✅ Vstupní omezení: CHECK constrainty na kladné částky (V4); enum-typované status/type/resolution
- ✅ Audit: každá mutace připojí neměnnou událost do `dispute_timeline`; doménové události pro audit-service
- ✅ TLS: mTLS v clusteru, terminace TLS na gateway
- ⚠️ Idempotence: u mutací zatím nevynucena (Redis klient přítomen, ale nezapojen) — sledovaný follow-up
- ⚠️ Vynucení OPA: pouze poradní (`authz.enforce=false`), dokud flotila nepřepne na enforce
- ⚠️ Emise outboxu: zařazení doménových událostí do `dispute_outbox` je last-mile mezera (viz `02-architecture.md`)
- ⚠️ Drift OpenAPI kontraktu: port serveru a schéma `OpenDisputeRequest` zaostávají za kódem (viz `03-api.md`)

Tyto mezery zralosti jsou položky roadmapy, nikoli zneužitelná specifika v produkčním scope.
