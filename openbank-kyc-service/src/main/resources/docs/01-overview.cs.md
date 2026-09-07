# Přehled

## Co služba dělá

`openbank-kyc-service` je **systém pravdy pro správu případů Know Your Customer (KYC) / Customer Due Diligence (CDD)** na platformě OpenBank. Drží:

- **Agregát KycCase** — `partyId`, stav případu (OPEN / DOCUMENTS_REQUIRED / UNDER_REVIEW / APPROVED / REJECTED / EXPIRED) (`DOCUMENTS_REQUIRED` je deklarovaný, ale nedosažitelný — žádná operace `KycService` ho nenastavuje, viz #8535; ponechán, protože odebrání hodnoty z publikovaného enumu je rozbíjející zúžení kontraktu, #8618), úroveň rizika (LOW / MEDIUM / HIGH / VERY_HIGH), úroveň hloubkové prověrky (SDD / CDD / EDD), revizora, expiraci a seznam kontrol.
- **KycCheck** — výsledek jednotlivé kontroly uvnitř případu: IDENTITY, ADDRESS, PEP_SCREENING, SANCTIONS_SCREENING, ADVERSE_MEDIA, každá se stavem (PENDING / PASSED / FAILED / MANUAL_REVIEW), volitelnou poznámkou k výsledku a referencí poskytovatele.
- **Compliance obohacující pole** (V2) — zdroj prostředků / majetku, účel obchodního vztahu, očekávaný obrat, PEP samoprohlášení, skutečný majitel, poskytovatel/reference screeningu, datum příští periodické revize a metadata eskalace.

Případ otevírá buď operátor (`POST /api/v1/kyc/cases`), nebo se otevře automaticky při příchodu události `PARTY_CREATED` z `party-service` (onboarding cockpit, ADR-0068). Případ pak akumuluje výsledky kontrol, dokud jej oprávněný revizor neschválí nebo nezamítne pod kontrolou čtyř očí.

## Co služba **NEDĚLÁ**

- ❌ Neprovádí samotný screening sankcí / PEP — zaznamenává **výsledek** screeningu; engine je `sanctions-service` / `aml-service`.
- ❌ Nevlastní master data klienta (jméno, rodné číslo, adresa) — to je `party-service`. KYC ukládá pouze `partyId` a compliance zjištění.
- ❌ Neotevírá bankovní účty — to je `account-service`. KYC clearance je předpoklad konzumovaný onboarding tokem.
- ❌ Neprovádí cache idempotence na úrovni požadavku — unikátnost je vynucena na úrovni domény (jeden aktivní případ na party).
- ❌ V produkci automaticky neschvaluje případy — sandboxová straight-through cesta (`openbank.kyc.auto-approve`) MUSÍ v produkci zůstat `false`; čtyři oči jsou jediná cesta schválení (ADR-0068).

## Pozice v doméně

```
   ┌──────────────┐  PARTY_CREATED        ┌─────────────┐
   │ party-service│ ───────────────────►  │ kyc-service │
   └──────────────┘  (openbank.party.events)└─────┬──────┘
                                                   │ outbox → Kafka
   ┌────────────┐  POST /kyc/cases                 │ (openbank.kyc.events)
   │  admin UI  │ ───────────────────────────────► │
   │ (operátoři)│  approve / reject (čtyři oči)     ▼
   └────────────┘                          ┌────────────────┐
                                           │ party-service  │ (aktivace)
                                           │ aml-service    │ (triggery)
                                           │ notification   │ (události)
                                           │ audit-service  │ (důkazy)
                                           └────────────────┘
            kyc-service → PostgreSQL (databáze: openbank_kyc)
```

## Klíčové případy užití

| Případ užití | API | Událost |
|---|---|---|
| Automatické otevření případu při vzniku party | — (konzumuje `PARTY_CREATED`) | `KYC_CASE_OPENED` |
| Ruční otevření KYC případu | `POST /api/v1/kyc/cases` | `KYC_CASE_OPENED` |
| Výpis případů podle fáze funnelu (cockpit) | `GET /api/v1/kyc/cases?status=…` | — |
| Načtení případu podle id / podle party | `GET /api/v1/kyc/cases/{id}`, `GET /api/v1/kyc/cases/party/{partyId}` | — |
| Zaznamenání výsledku kontroly | `PUT /api/v1/kyc/cases/{id}/checks/{checkType}` | `KYC_CASE_STATUS_CHANGED` (při přechodu) |
| Schválení případu (čtyři oči) | `POST /api/v1/kyc/cases/{id}/approve` | `KYC_CASE_APPROVED` |
| Zamítnutí případu (čtyři oči) | `POST /api/v1/kyc/cases/{id}/reject` | `KYC_CASE_REJECTED` |

## Volající

- **admin-ui** (přes Keycloak token) — KYC pracovníci, compliance ops, funnel onboarding cockpitu (ADR-0068)
- **party-service** (události) — upstream producent `PARTY_CREATED`; downstream konzument schválení pro aktivaci
- **aml-service / sanctions-service** — konzumují KYC události pro spuštění nebo korelaci screeningu
- **service-to-service čtenáři** (`ROLE_API`) — čtou KYC stav party během onboardingu

## Závislosti

- **PostgreSQL** (`openbank-postgres`, databáze `openbank_kyc`)
- **Kafka** (`openbank-kafka`, odchozí topic `openbank.kyc.events`, příchozí topic `openbank.party.events`)
- **Keycloak** — OIDC autentizace
- **OPA sidecar** — advisory autorizace (ADR-0034)
- **openbank-libs** — `ApiError`/`ErrorCode`, `@Authorize`, BuildInfo/ServiceInfo, DocsResource, outbox plumbing

## Obchodní hodnota

- **Jediný zdroj pravdy** pro KYC/CDD stav party a audit-grade historii, jak se k danému rozhodnutí dospělo.
- **Automatizace onboardingu** — případ se otevře automaticky při vzniku party a napájí funnel onboarding cockpitu bez ruční tikety (ADR-0068).
- **Compliance čtyř očí** — schválit/zamítnout je autorizovaná, auditovaná akce dvojí kontroly podpořená `@Authorize` a emitovanými doménovými událostmi.
- **Eventuální konzistence** — outbox + Kafka propagují KYC rozhodnutí do aktivace party, AML, notifikací a auditu téměř v reálném čase.
