# Přehled

## Co služba dělá

`openbank-lending-service` je **ohraničený kontext úvěrování (lending / credit)** (ADR-0028). Vlastní úvěrovou knihu po celý její životní cyklus:

- **Vznik úvěru (origination)** — agregát `LoanApplication` procházející čtyřoč rozhodovacím tokem (maker-checker): maker navrhne, jiný checker schválí/zamítne a třetí pracovník provede čerpání. Identity makera, checkera i disbursera se berou z ověřeného JWT subjektu na straně serveru, nikdy z těla requestu (ADR-0028 D5, EBA/GL/2020/06).
- **Správa (servicing)** — agregát `Loan` zaúčtovaný ze schválené a načerpané žádosti, se smluvním splátkovým kalendářem (řádky `LoanInstallment` generované z čisté primitivy `libs.lending.Amortization`: ANNUITY / EQUAL_PRINCIPAL / BULLET).
- **Akruální uznání úroku** — naplánovaný servicing průchod uznává úrok každé splátky jako výnos v okamžiku splatnosti (IAS 1), nezávisle na tom, kdy se peníze inkasují; příznak `interest_accrued` zajišťuje idempotenci.
- **Zajištění (collateral)** — agregát `Collateral` (kategorie ochrany dle AnaCredit) evidovaný k úvěru, s rizikovým haircutem v intervalu `[0,1]`.
- **Opravné položky dle IFRS 9** — bodový snímek stage + očekávané úvěrové ztráty (ECL) na úvěr, počítaný z čistých primitiv `libs.lending.Ifrs9` a `Delinquency`.

## Co služba **NEDĚLÁ**

- Nevede podvojnou knihu ani nevlastní zůstatky — posílá vyvážené zápisy do `ledger-service` (`POST /api/v1/journals`).
- Nepočítá úvěrovou matematiku sama — veškerá amortizace / IFRS 9 / delikvence žije v `openbank-libs` (`libs.lending`).
- Neprovozuje úvěrový registr ani PD model — porty `CreditBureauPort` / `RiskParameterSource` mají konzervativní no-op výchozí hodnoty; reálný model je pouze otázka zapojení.
- Nehýbe penězi ani nedrží účty — čerpání a splátka jsou účetní zápisy, ne provedení platby.
- Neprovádí KYC/AML při podání žádosti — to dělá `kyc-service` / `aml-service` (události úvěru živí audit/compliance pipeline).

## Pozice v doméně

```
   ┌────────────┐  POST /applications     ┌──────────────────┐
   │  admin UI  │ ─────────────────────►  │ lending-service  │
   │ (operátoři)│  decision / disburse    │  (úvěrová kniha) │
   └────────────┘                         └────┬─────────────┘
                                               │ účetní zápis
                                               │ (POST /api/v1/journals)
                                               ▼
                                         ┌──────────────────┐
                                         │  ledger-service  │
                                         └──────────────────┘
                       outbox → Kafka          │
   ┌──────────────────┐   openbank.lending.events
   │ PostgreSQL        │ ◄── lending-service ──────► ┌────────────────┐
   │ (openbank_lending)│                             │ audit-service  │
   └──────────────────┘                             │ analytika / BI │
                                                     └────────────────┘
```

## Klíčové případy užití

| Případ užití | API | Událost / zápis |
|---|---|---|
| Podání žádosti o úvěr (maker) | `POST /api/v1/lending/applications` | — |
| Schválení / zamítnutí (checker ≠ maker) | `POST /api/v1/lending/applications/{id}/decision` | — |
| Čerpání schváleného úvěru (disburser ≠ checker) | `POST /api/v1/lending/applications/{id}/disburse` | událost `loan.disbursed`, zápis `DISBURSEMENT` |
| Zaznamenání splátky | `POST /api/v1/lending/loans/{id}/installments/{instId}/repay` | zápisy `PRINCIPAL_REPAYMENT` + `INTEREST`/`INTEREST_SETTLEMENT` |
| Naběhnutí splatného úroku (naplánované) | — (servicing smyčka) | událost `loan.interest_accrued`, zápis `INTEREST_ACCRUAL` |
| Odpis nevymahatelného úvěru | `POST /api/v1/lending/loans/{id}/writeoff` | událost `loan.written_off`, zápis `WRITE_OFF` |
| Evidence zajištění | `POST /api/v1/lending/loans/{id}/collateral` | — |
| IFRS 9 stage + ECL snímek | `GET /api/v1/lending/loans/{id}/provisioning` | — |

## Kdo volá

- **admin-ui** (přes Keycloak token) — úvěroví pracovníci, credit-risk, compliance operátoři
- **scheduler (interní)** — servicing smyčka úročení běží v procesu, není to externí volající

## Závislosti

- **PostgreSQL** (databáze `openbank_lending`; governance název schématu `lending_schema`)
- **Kafka** (`openbank-kafka`, topic `openbank.lending.events`)
- **ledger-service** — REST klient `POST /api/v1/journals` (build-time přepínač; offline no-op)
- **Redis (Valkey)** — nakonfigurovaný klient (rate-limit / cache plumbing přes libs)
- **Keycloak** — auth (OIDC + OIDC-client pro service-to-ledger token)
- **openbank-libs** — `Money`, identifikátory (`LoanId`, `LoanApplicationId`, `CollateralId`), `libs.lending` (`Amortization`, `Ifrs9`, `Delinquency`), outbox plumbing, BuildInfo, DocsResource

## Obchodní přínos

- **Jediný zdroj pravdy** pro úvěrovou knihu — rozhodnutí o vzniku úvěru, kalendáře, zajištění a opravné položky v jednom ohraničeném kontextu.
- **Audit-grade oddělení odpovědností** — čtyřoč princip nad úvěrovým rozhodnutím a segregace odpovědností nad krokem výplaty peněz, vynucené na serveru z JWT subjektu a nezfalšovatelné.
- **Správné účetnictví z konstrukce** — úrokový výnos je uznán právě jednou (rozdělení akruál vs. cash-basis) a každá peněžní událost se vyvažuje jako podvojný zápis v ledgeru.
- **Regulatorní připravenost** — IFRS 9 stage/ECL, kategorie zajištění v souladu s AnaCredit a neměnná stopa událostí pro audit.
