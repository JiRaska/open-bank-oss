# Přehled

## Co služba dělá

`openbank-party-service` je **systém záznamů o party** v platformě OpenBank. *Party* je libovolná fyzická nebo právnická osoba, se kterou banka jedná. Drží:

- **Agregát Party** — `partyType` (INDIVIDUAL / SOLE_TRADER / COMPANY / TRUST), `status` (PENDING_KYC / ACTIVE / SUSPENDED / CLOSED), právní název, volitelný obchodní název, kontaktní údaje (e-mail — unikátní, telefon, adresu), identifikační atributy (datum narození, národnost, DIČ, registrační číslo) a compliance metadata (PEP příznak, rizikové hodnocení, FATCA/CRS status, GDPR souhlas, termíny revizí).
- **Dokumenty party** — identifikační doklady navázané na party (NATIONAL_ID / PASSPORT / DRIVING_LICENCE / COMPANY_REGISTRATION / TAX_ID), se zemí vydání a expirací.
- **Stav životního cyklu KYC + AML** — `kycStatus` (NOT_STARTED / IN_PROGRESS / APPROVED / REJECTED / EXPIRED) a `amlStatus` (NOT_SCREENED / CLEARED / BLOCKED). Party se stává ACTIVE pouze tehdy, když projdou oba klíče (dvouklíčová aktivační brána).

## Co služba **NEDĚLÁ**

- ❌ Neprovádí KYC revize — `kyc-service` vlastní engine případů; party-service jen zaznamenává koncový výsledek (přes REST nebo stream `openbank.kyc.events`).
- ❌ Neprovádí AML / sanctions screening — to dělá `aml-service` / `sanctions-service`; party-service zaznamenává výsledek z `openbank.aml.events`.
- ❌ Neukládá šifrované rodné číslo — to žije v `pid-service`. Rodné číslo zde není nikdy vyhledatelné (GDPR minimalizace dat).
- ❌ Nezakládá ani nedrží účty — to dělá `account-service`, klíčované přes `ownerPartyId`.
- ❌ Nepřesouvá peníze — party-service není money-path služba.

## Pozice v doméně

```
   ┌────────────┐  POST/GET /parties   ┌──────────────────┐
   │  admin UI  │ ───────────────────► │  party-service   │
   └────────────┘                      │ (identity SoR)   │
                                        └───┬──────────┬───┘
   ┌────────────┐  kyc-status (REST)        │          │ outbox → Kafka
   │ kyc-service│ ─────────────────────────►│          ▼  openbank.party.events
   └─────┬──────┘                           │     ┌──────────────┐
         │ openbank.kyc.events              │     │ account-svc  │
         ▼ (konzumováno)                    │     │ audit-service│
   ┌────────────────┐  openbank.aml.events  │     │ onboarding   │
   │  aml-service   │ ─────────────────────►│     └──────────────┘
   └────────────────┘ (konzumováno)         ▼
                                       PostgreSQL
                                    (db: openbank_parties)
```

## Klíčové případy užití

| Případ užití | API | Event |
|---|---|---|
| Registrace nové party (zákazník/firma) | `POST /api/v1/parties` | `PARTY_CREATED` |
| Aktualizace kontaktních údajů party | `PATCH /api/v1/parties/{id}` | `PARTY_UPDATED` |
| Přidání identifikačního dokladu | `POST /api/v1/parties/{id}/documents` | — |
| Zaznamenání koncového výsledku KYC | `PUT /api/v1/parties/{id}/kyc-status` | `KYC_STATUS_CHANGED` |
| Zaznamenání koncového výsledku KYC (event) | konzumuje `openbank.kyc.events` | `KYC_STATUS_CHANGED` |
| Zaznamenání koncového výsledku AML (event) | konzumuje `openbank.aml.events` | `KYC_STATUS_CHANGED` |
| Výpis party (stránkovaný, volitelný filtr stavu) | `GET /api/v1/parties?status=…` | — |
| Vyhledání party podle jména (trigram) | `GET /api/v1/parties/search?q=…` | — |
| Výmaz party (GDPR čl. 17) | `DELETE /api/v1/parties/{id}` | `PARTY_ERASED` |

## Volající

- **admin-ui** (přes Keycloak token) — operátoři, compliance, onboarding cockpit funnel (filtr stavu dle ADR-0068).
- **kyc-service** — posílá koncová KYC rozhodnutí (`ROLE_KYC` na `PUT .../kyc-status`) a/nebo emituje do `openbank.kyc.events`.
- **aml-service** — emituje koncová AML rozhodnutí do `openbank.aml.events`.
- **account-service** — read-only dotaz na vlastníka (`GET /parties/{id}`) při zakládání účtu.
- **pid-service** — vztah pro šifrovaná data dokladů (downstream).

## Závislosti

- **PostgreSQL** (databáze `openbank_parties`)
- **Kafka** — odchozí topic `openbank.party.events`; příchozí `openbank.kyc.events`, `openbank.aml.events`
- **Keycloak** — OIDC autentizace
- **flagd** (OpenFeature, ADR-0067) — feature flagy `party-search`, `party-list-enriched`; fail-static
- **OPA sidecar** (ADR-0034) — autorizace, advisory režim
- **openbank-libs** — ApiError, CursorPage/PageInfo/CursorEncoder, SearchRequest, FeatureClient/@FeatureFlag, @Authorize, outbox plumbing, DocsResource, ServiceInfoResource

## Obchodní hodnota

- **Jediný zdroj pravdy** o tom, kdo jsou zákazníci banky — každý účet, platba a výpis se nakonec rozpadá na `partyId` zde.
- **Dvouklíčová onboarding brána** — party je aktivována jen když projdou KYC (APPROVED) i AML (CLEARED), fail-closed; tvrdý negativ na kterémkoli ji suspenduje. Aktivační rozhodnutí je tak auditovatelné na jednom místě.
- **GDPR-ready** — explicitní tok Práva na výmaz, který anonymizuje PII a přitom zachová unikátnost/tombstone potřebný pro AML retenci.
- **Compliance metadata jako první třída** — PEP, rizikové hodnocení, FATCA/CRS, termíny revizí žijí na agregátu pro downstream AML/regulatorní reporting.
