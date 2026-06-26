# Přehled

## Co služba dělá

`openbank-sdd-service` je **systém záznamu mandátů SEPA inkasa na straně plátce (debtora)** v platformě OpenBank (ADR-0036). Drží:

- **Agregát SddMandate** — trvalou autorizaci, kterou zákazník (debtor) dává příjemci (creditorovi) k inkasu z EUR kapsy jednoho účtu. Identitou je rulebooková dvojice `(creditorIdentifier, UMR)`. Nese schéma (CORE / B2B), typ sekvence (OOFF / FRST / RCUR / FNAL), jména creditora/debtora, datum podpisu, stav a seznam zaznamenaných změn (amendments).
- **Životní cyklus mandátu** — čistý stavový automat `PENDING_CONFIRMATION → ACTIVE → SUSPENDED ⇄ ACTIVE → CANCELLED` a automatický `EXPIRED` po 36 měsících nečinnosti. **Core** mandáty se rodí jako `ACTIVE`; **B2B** mandáty se rodí jako `PENDING_CONFIRMATION` a musí být potvrzeny bankou plátce.
- **Autorizaci inkasa** — fail-closed čisté rozhodnutí (`ACCEPT` / `REJECT` / `REFUSE`) nad příchozí inkasní instrukcí oproti uloženému mandátu a kontrolám plátce.
- **Posouzení refundu** — vypočtené rozhodnutí o lhůtě pro vrácení (8 týdnů bezpodmínečně u autorizovaného Core, 13 měsíců u neautorizovaného, žádný u autorizovaného B2B).

## Co služba **NEDĚLÁ**

- ❌ Nepřevádí peníze — v1 nikdy neodepisuje a nezaúčtovává refund. `ACCEPT` pouze vydá `sdd.collection.authorised.v1` k provedení ledger/platební cestou (nevratné zaúčtování zůstává na službách, které jsou na to již zpevněné).
- ❌ Nevystavuje inkasa na straně příjemce — tato služba *inkasuje od jiných*; vystavování na straně creditora je mimo rozsah.
- ❌ Nepřipojuje se k CSM / clearingovému domu — konektivita k CSM je v v1 mimo rozsah.
- ❌ Není český tuzemský nástroj *souhlas/povolení k inkasu* (CERTIS) — samostatný nástroj.
- ❌ Neprovádí AML/sanctions screening — ten žije v `aml-service` / `sanctions-service` na navazující zaúčtovací cestě.
- ❌ Nevynucuje povinnost creditora pre-notifikovat — pre-notifikace ≥14 dní se *eviduje*, nevynucuje (její chybění je doložený důvod k odmítnutí).

## Pozice v doméně

```
   ┌────────────┐  POST /mandates       ┌──────────────────┐
   │  admin UI  │ ───────────────────►  │                  │
   └────────────┘                       │  sdd-service     │
   ┌────────────┐  POST /collections/   │  (trezor mandátů)│
   │  platba /  │  authorise            │                  │
   │  clearing  │ ───────────────────►  └────────┬─────────┘
   └────────────┘                                │ outbox → Kafka
                                                 ▼
                                       ┌──────────────────────┐
   PostgreSQL  ◄──────────────────────┤  openbank.sdd.event   │
   (db: openbank_sdd)                  │  → ledger / platba    │
                                       │  → audit / notify     │
                                       └──────────────────────┘
```

## Klíčové případy užití

| Případ užití | API | Událost |
|---|---|---|
| Registrace mandátu plátce | `POST /api/v1/sdd/mandates` | `sdd.mandate.registered.v1` |
| Potvrzení B2B mandátu (PENDING_CONFIRMATION → ACTIVE) | `POST /api/v1/sdd/mandates/{id}/confirm` | `sdd.mandate.confirmed.v1` |
| Pozastavení ACTIVE mandátu | `POST /api/v1/sdd/mandates/{id}/suspend` | `sdd.mandate.suspended.v1` |
| Obnovení SUSPENDED mandátu | `POST /api/v1/sdd/mandates/{id}/resume` | `sdd.mandate.resumed.v1` |
| Zrušení mandátu (terminální) | `POST /api/v1/sdd/mandates/{id}/cancel` | `sdd.mandate.cancelled.v1` |
| Změna pole mandátu (AMDT marker) | `PATCH /api/v1/sdd/mandates/{id}` | `sdd.mandate.amended.v1` |
| Autorizace příchozího inkasa | `POST /api/v1/sdd/collections/authorise` | `sdd.collection.authorised.v1` (při ACCEPT) |
| Posouzení nároku na refund | `GET /api/v1/sdd/mandates/{id}/refund-assessment` | — |
| Výpis / načtení mandátů | `GET /api/v1/sdd/mandates?accountId=…`, `GET …/{id}` | — |

## Volající

- **admin-ui** (přes Keycloak token) — operátoři, payments ops registrují/spravují mandáty.
- **platební / clearingové služby** — volají `POST /collections/authorise` pro fail-closed rozhodnutí před provedením odepsání.
- **navazující konzumenti** (ledger/platba, audit, notifikace) — konzumují `openbank.sdd.event` (read-only, asynchronně).

## Závislosti

- **PostgreSQL** (`openbank-postgres`, databáze `openbank_sdd`)
- **Kafka** (`openbank-kafka`, topic `openbank.sdd.event`)
- **Keycloak** — OIDC autentizace
- **openbank-libs** — sdílené runtime (BuildInfo, ServiceInfoResource, DocsResource, security)

## Obchodní hodnota

- **Jediný zdroj pravdy** pro trvalé inkasní autorizace, které zákazník udělil — žádné duplicitní seznamy mandátů napříč službami.
- **Fail-closed by design** — v případě pochybnosti je inkaso odmítnuto, nikdy tiše akceptováno; to je ochrana zákazníka proti neautorizovanému odepsání.
- **Regulačně přesná aritmetika refundu** — lhůty pro vrácení (PSD2 čl. 73/76/77, CZ §177) jsou vypočteny, nikoli odhadnuty.
- **Eventuálně konzistentní propagace** přes transakční outbox + Kafka — navazující zaúčtování, audit a notifikace vidí autorizované inkaso během sekund.
