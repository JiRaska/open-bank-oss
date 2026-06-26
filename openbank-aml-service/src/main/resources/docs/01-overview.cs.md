# Přehled

## Co služba dělá

`openbank-aml-service` je **systém záznamu pro AML screeningové případy** na platformě OpenBank. Spravuje životní cyklus AML případu od založení přes posouzení analytikem až po finální rozhodnutí:

- **Agregát AmlCase** — id, vlastnící `partyId`, volitelné `accountId` / `transactionId`, `customerReference`, `screeningType` (CUSTOMER_ONBOARDING / TRANSACTION_MONITORING / PERIODIC_REVIEW / MANUAL_INVESTIGATION), `riskLevel` (LOW / MEDIUM / HIGH / CRITICAL), `status`, `alertCode`, detaily nalezené entity a metadata rozhodnutí (`decisionReason`, `assignedAnalyst`, `decidedBy`, `decidedAt`).
- **Stavový automat případu** — `OPEN` / `UNDER_REVIEW` / `ESCALATED` → terminální `CLEARED` nebo `BLOCKED`. Případy s rizikem HIGH/CRITICAL se zakládají rovnou jako `UNDER_REVIEW`; LOW/MEDIUM jako `OPEN`. Terminální stavy už nelze měnit.
- **Compliance metadata** — sledování nalezeného seznamu, fuzzy match skóre, příznak false-positive, eskalace na MLRO a reference k podání SAR (Suspicious Activity Report) — DB sloupce z V2.

## Co služba **NEDĚLÁ**

- Neporovnává jména proti sankčním / PEP / adverse-media seznamům — to je `openbank-sanctions-service`.
- Neprovádí KYC ověření totožnosti — to je `openbank-kyc-service`.
- Nepřesouvá peníze, neúčtuje do hlavní knihy ani přímo neodmítá platby — platební rozhraní volají screening a podle výsledku jednají sama.
- Nezmrazuje účty — freeze/unfreeze workflow vlastní `openbank-account-service`.
- Nepodává SAR externě na FIU — eviduje pouze referenci SAR; samotné podání je činnost compliance ops mimo systém.

## Pozice v doméně

```
   ┌──────────────┐  PARTY_CREATED (Kafka)   ┌───────────────┐
   │ party-service│ ───────────────────────► │  aml-service  │
   └──────────────┘                           │ (založ případ)│
                                              └──────┬────────┘
   ┌──────────────┐  POST /aml/cases                │ outbox → Kafka
   │ platební svc │ ───────────────────────►        ▼  openbank.aml.events
   │ sepa/instant │                           ┌────────────────┐
   │ domestic/swift│                          │ party-service  │ (AML klíč
   └──────────────┘                           │ audit-service  │  aktivační brány)
   ┌──────────────┐  POST /aml/cases          └────────────────┘
   │ admin UI     │ ───────────────────────►        │
   │ (compliance) │  PUT .../decision               ▼
   └──────────────┘                              PostgreSQL
                                              (db: openbank_aml)
```

## Klíčové případy užití

| Případ užití | API | Událost |
|---|---|---|
| Založit screeningový případ (manuální / platba / transakce) | `POST /api/v1/aml/cases` | `aml.case.created.v1` |
| Založit onboarding screeningový případ pro nového klienta | konzumace `PARTY_CREATED` na `openbank.party.events` | `aml.case.created.v1` |
| Získat případ podle id | `GET /api/v1/aml/cases/{caseId}` | — |
| Vypsat / filtrovat případy | `GET /api/v1/aml/cases?status=&partyId=&screeningType=` | — |
| Zaznamenat rozhodnutí analytika (clear / block / escalate) | `PUT /api/v1/aml/cases/{caseId}/decision` | `aml.case.status_changed.v1` |

## Kdo službu volá

- **admin-ui** (přes Keycloak token) — compliance analytici a MLRO zakládají případy a zaznamenávají rozhodnutí přes compliance cockpit.
- **platební služby** (sepa-payment, sepa-instant, domestic-payment, swift-service) — zakládají screeningový případ jako součást své platební screening brány (deklarovaná upstream `api` linie v `governance.yaml`).
- **party-service** (Kafka) — emituje `PARTY_CREATED` pro spuštění onboarding screeningu; konzumuje `aml.case.status_changed.v1` jako AML klíč své dvouklíčové aktivační brány KYC+AML.
- **kyc-service / sanctions-service** (Kafka, deklarovaná `topic` linie) — spouštějí / dodávají screeningové signály.

## Závislosti

- **PostgreSQL** (`openbank-postgres`, databáze `openbank_aml`)
- **Kafka** (`openbank-kafka`, odchozí topic `openbank.aml.events`, příchozí `openbank.party.events`)
- **Redis (Valkey)** — idempotenční cache
- **Keycloak** — autentizace (OIDC)
- **OPA sidecar** — jednotná autorizace (ADR-0034), ve výchozím stavu advisory
- **openbank-libs** — `IdempotencyStore`, `Authorize`/PDP, `ApiError`/`ErrorCode`, outbox plumbing, `ServiceInfoResource`, `DocsResource`

## Byznysová hodnota

- **Jediný zdroj pravdy** o stavu a rozhodnutích AML případů — každý screeningový alert i rozhodnutí analytika je zaznamenán s úplnými audit metadaty (kdo rozhodl, proč, kdy).
- **Rozhodování ve čtyřech očích** — v produkci je decision endpoint jedinou cestou do terminálního stavu `CLEARED`/`BLOCKED` (sandbox auto-clear je za feature flagem, ve výchozím stavu vypnutý).
- **Regulatorní evidence** — události životního cyklu případu tečou přes outbox + Kafka do `audit-service` po zákonnou dobu retence; sloupce SAR/MLRO podporují reportingové povinnosti dle 6AMLD.
- **Účast na onboarding bráně** — poskytuje AML klíč pro aktivaci klienta, čímž zajišťuje, že žádný klient není aktivován bez screeningového případu.
