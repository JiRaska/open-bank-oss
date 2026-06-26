# Přehled

## Co služba dělá

`openbank-pid-service` (PID = **Party Identity Data**) je **system of record o identitě subjektu** na platformě OpenBank — kotva „jeden člověk / jedna právnická osoba = jeden party". Drží:

- **Agregát Party** — interní `id` (UUID), `partyType` (NATURAL_PERSON / LEGAL_ENTITY / SOLE_TRADER), `status` (ACTIVE / SUSPENDED / DECEASED / TERMINATED), `version` (optimistický zámek).
- **CoreAttributes** — jméno/příjmení, datum narození, šifrované rodné číslo, pohlaví, místo narození, státní příslušnosti, doklady totožnosti a `verificationSource` (BANKID / BRANCH_MANUAL / API_UPLOAD / ROB) s `verifiedAt`.
- **ExternalIds** — mezisystémové identifikátory ukazující na jeden party: `BANKID_SUB`, `ROB_AIFO`, `ICO`, `KEYCLOAK_ID`, `PASSPORT_NUMBER`, `ID_CARD_NUMBER`. Každá dvojice `(type, value)` je globálně unikátní.
- **AddressAttributes** — trvalá / korespondenční adresa s RUIAN kódem, synchronizovaná z ROB (Registr obyvatel / ISZR).
- **ContactAttributes** — email, telefon (s časem ověření), preferovaný jazyk, ID datové schránky.
- **KycAttributes** — KYC úroveň (NONE / BASIC / ENHANCED / FULL), AML rizikové skóre (LOW / MEDIUM / HIGH / UNACCEPTABLE), PEP flag, sanctions flag, časy UBO ověření a poslední AML revize. Jde o **uložené** atributy nastavené nadřazenými KYC/AML službami — zde se nepočítají.
- **Relationships** — role, které subjekt vůči bance hraje (CUSTOMER / EMPLOYEE / ADMIN / AGENT / GUARANTOR / AUTHORIZED_PERSON), každá s onboarding kanálem a stavem životního cyklu.
- **PID case lifecycle** — verifikační případ (`CaseType.PID_VERIFICATION`) řízený přes `libs.domain.case.CaseTransitionEngine` (DRAFT → OPEN → IN_REVIEW → APPROVED/REJECTED/…), s aktérem, reason code a háčky na navázání důkazů.

## Co služba **NEdělá**

- ❌ Nezakládá, nedrží ani neruší bankovní účty — to dělá `openbank-account-service`.
- ❌ Nevede rozhodovací KYC/AML proces ani sankční screening — to dělají `kyc-service` / `aml-service` / `sanctions-service`; pid-service jen *ukládá* výslednou KYC úroveň, rizikové skóre, PEP a sankční příznaky.
- ❌ Neautentizuje uživatele ani nevydává tokeny — IdP je Keycloak; pid-service jen naváže `KEYCLOAK_ID` / `BANKID_SUB`.
- ❌ V tomto kódu nevolá přímo bankID ani ROB/ISZR — endpointy `/sync/bankid` a `/sync/rob` přijímají již načtené atributy od volajícího (adaptér na externí registry žije nadřazeně).
- ❌ Zatím nederuplikuje identity přes blind-index rodného čísla — návrh sjednocení identity (jeden člověk = jeden party) je položkou roadmapy; dnes je vytvoření deduplikováno jen na unikátní bankID `sub`.

## Pozice v doméně

```
   ┌────────────┐   POST /parties           ┌──────────────────┐
   │  admin UI  │ ────────────────────────► │  pid-service     │
   └─────┬──────┘   (employee/admin)        │  (Party Identity)│
         │                                   └───────┬──────────┘
   ┌─────┴──────┐   POST /sync/bankid                │ outbox → Kafka
   │ onboarding │ ─────────────────────────►         │  topic: party.events
   │  / IdP     │                                     ▼
   └────────────┘                          ┌───────────────────────┐
                                           │ account-service       │
   GET /parties/{id}  ◄──── account /       │ kyc-service / aml      │
   GET /by-external-id ◄─── kyc / payment   │ audit-service          │
                                            │ notification           │
              PostgreSQL                    └───────────────────────┘
            (db: openbank_pid)
```

## Klíčové use cases

| Use case | API | Vyslané událost(i) |
|---|---|---|
| Vytvoření party (sjednocená identita) | `POST /api/v1/parties` | `PartyCreated`, `case.created`, `RelationshipAdded` |
| Získání party podle interního id | `GET /api/v1/parties/{id}` | — |
| Vyhledání party podle externího id | `GET /api/v1/parties/by-external-id?type=&value=` | — |
| Vyhledávání party | `GET /api/v1/parties?familyName=…&role=…` | — |
| Synchronizace atributů z bankID | `POST /api/v1/parties/{id}/sync/bankid` | `PartyVerified` |
| Synchronizace adresy z ROB | `POST /api/v1/parties/{id}/sync/rob` | `AddressUpdatedFromRob` |
| Aktualizace kontaktu | `PATCH /api/v1/parties/{id}/contact` | — |
| Aktualizace KYC/AML atributů | `PUT /api/v1/parties/{id}/kyc` | `KycLevelChanged` (jen při změně úrovně) |
| Změna stavu party | `PATCH /api/v1/parties/{id}/status` | `PartyStatusChanged` |
| Přechod PID verifikačního případu | `PATCH /api/v1/parties/{id}/case` | `case.transitioned` |
| Přidání role/vztahu | `POST /api/v1/parties/{id}/relationships` | `RelationshipAdded` |
| Ukončení vztahu | `DELETE /api/v1/parties/{id}/relationships/{relationshipId}` | `RelationshipTerminated` |

## Volající

- **admin-ui** (přes Keycloak token) — operátoři / compliance zakládají, vyhledávají a kurátorsky spravují party.
- **onboarding / zákaznická app** — dodává ověřené bankID atributy (cesta `/sync/bankid`) a čte vlastní profil (role `openbank-customer` na `GET /{id}` a `PATCH /contact`).
- **account-service / platební služby** — read-only rozlišení `partyId` a externích id před navázáním účtu nebo zpracováním platby.
- **kyc / aml / sanctions služby** — konzumují události `PartyCreated` / `PartyVerified` a vracejí KYC/AML atributy přes `PUT /kyc`.

## Závislosti

- **PostgreSQL** (`openbank-postgres`, databáze `openbank_pid`)
- **Kafka** (`openbank-kafka`, topic `party.events`)
- **Keycloak** — OIDC autentizace
- **OPA sidecar** (advisory) — rozhodnutí `@Authorize` (ADR-0034)
- **openbank-libs** — `libs.domain.case` (CaseTransitionEngine, CaseId, CaseStatus, CaseReasonCode, CaseType), `libs.domain.event.DomainEvent`, `libs.authz.@Authorize`, `libs.api.error.ApiError`, `libs.docs.DocsResource`, `libs.web.ServiceInfoResource`

## Byznys hodnota

- **Jediný zdroj pravdy o identitě** — jeden kanonický záznam party napříč bankou; účetní/platební/KYC služby odkazují na `partyId` místo duplikace osobních dat.
- **Mezi-registrové rozlišení** — `by-external-id` mapuje bankID `sub`, ROB AIFO nebo IČO na jeden interní party, základ cíle sjednocení identity „jeden člověk = jeden party".
- **Auditovatelný životní cyklus identity** — každá změna identity, KYC, stavu a vztahu vyšle doménovou událost pro audit trail; PID verifikační case lifecycle dává compliance vysvětlitelnou historii schválení/zamítnutí.
- **Datový model v souladu s regulátorem** — bankID, ROB/ISZR, RUIAN, datová schránka, šifrování rodného čísla a PEP/sankční příznaky přímo mapují na české AML/KYC a eIDAS očekávání.
