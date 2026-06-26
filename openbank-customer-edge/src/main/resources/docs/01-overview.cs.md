# Přehled

## Co služba dělá

`openbank-customer-edge` je **jediný vstupní bod z internetu** pro retailovou zákaznickou aplikaci (KMP, ADR-0064). Je to backend-for-frontend (BFF) plus brána (ADR-0065), která:

- **Validuje zákaznické JWT** vydané Keycloak realmem `openbank-customers` (oddělený realm od operátorského `openbank`).
- **Extrahuje identitu party volajícího** z JWT claimu `party_id` (s fallbackem na `sub`) do `CustomerIdentity`.
- **Vynucuje vlastnictví podle party** na každém čtení i zápisu — zákazník se vždy dostane jen ke svým účtům, zůstatkům, transakcím, výpisům, platbám a SCA zdrojům (obrana proti IDOR; deny-by-default).
- **Proxuje explicitní allow-list cest** na backendové služby přes `UpstreamClient`. Jakákoli cesta mimo allow-list vrací 404.
- **Reautentizuje směrem ven** — zákaznický token se *nepřeposílá*; edge si získá vlastní machine-to-machine (M2M) servisní token (operátorský realm, `client_credentials`) a request označí hlavičkou `X-Customer-Party-Id`, aby upstreamy mohly nezávisle omezit data.
- **Obohacuje lehká těla od klienta** do plných instrukcí, které upstream služby potřebují (např. dohledání debtorova IBAN/BBAN a právního jména pro platbu).
- **Bránuje onboarding** — `POST /onboarding/start` vytvoří party ve stavu `PENDING_ACTIVATION` (neautentizovaně); `POST /onboarding/account` otevře první účet až po tom, co je party v KYC stavu `ACTIVE` (ADR-0069).

## Co služba **NEDĚLÁ**

- ❌ Nedrží žádný byznysový stav — bez databáze, bez outboxu, bez doménového agregátu (je bezstavová).
- ❌ Neobsluhuje operátory — to je admin-UI BFF (ADR-0056); operátoři jsou v realmu `openbank`.
- ❌ Nevlastní účty, zůstatky, transakce, výpisy ani platby — jen proxuje na služby, které je vlastní.
- ❌ Nehýbe penězi — platební cesty instrukci *vytvoří a proscreenují*; settlement je pozdější krok pod SCA.
- ❌ Nedělá samo KYC/AML — KYC bránu vynucuje čtením stavu party z party-service.
- ❌ Nevydává zákaznické tokeny ani nezakládá Keycloak uživatele (Fáze 1: operátor/seed skript; Fáze 2: follow-up).

## Pozice v doméně

```
   ┌──────────────────┐  HTTPS (zákaznický JWT)  ┌────────────────────┐
   │ retail app (KMP) │ ──────────────────────►  │ ingress-nginx      │
   └──────────────────┘                          │ (rate limit per IP)│
                                                 └─────────┬──────────┘
                                                           │ /customer/v1/*
                                                           ▼
                                            ┌─────────────────────────┐
                                            │  openbank-customer-edge  │
                                            │  validace JWT · IDOR ·   │
                                            │  M2M token · allow-list  │
                                            └───────────┬─────────────┘
                       M2M token + X-Customer-Party-Id  │
        ┌──────────────┬──────────────┬─────────────────┼───────────────┬───────────────┐
        ▼              ▼              ▼                  ▼               ▼               ▼
   account-svc    balance-svc    transaction-svc    sca-svc       party-svc      statement-svc
                                                  (+ domestic-payment, sepa-payment, notification)
```

## Klíčové případy užití

Edge je proxy: **nevydává žádné vlastní události**. Sloupec „upstream" uvádí službu, na kterou se cesta přeposílá.

| Případ užití | API (báze `/customer/v1`) | Upstream |
|---|---|---|
| Vypsat moje účty | `GET /accounts` | account-service |
| Získat jeden z mých účtů | `GET /accounts/{accountId}` | account-service (vlastnictví vynuceno zde) |
| Získat zůstatek | `GET /balances/{accountId}` | balance-service (vlastnictví vynuceno zde) |
| Vypsat moje transakce | `GET /transactions?accountId=…` | transaction-service (vlastnictví vynuceno zde) |
| Vypsat období výpisů | `GET /statements/{accountId}` | statement-service (vlastnictví vynuceno zde) |
| Vyrenderovat výpis (camt.053/MT940/PDF) | `GET /statements/{accountId}/{currency}/{legalSequence}` | statement-service |
| Získat můj profil | `GET /profile` | party-service (omezeno na party) |
| Vypsat moje notifikace | `GET /notifications` | notification-service (omezeno na party) |
| Iniciovat tuzemskou platbu | `POST /domestic-payments` | domestic-payment-service (obohaceno, pod SCA) |
| Iniciovat SEPA úhradu | `POST /sepa-payments` | sepa-payment-service (obohaceno, pod SCA) |
| Zaregistrovat SCA zařízení | `POST /sca/parties/{partyId}/devices` | sca-service (ADR-0021) |
| Iniciovat / číst / rozhodnout SCA challenge | `POST,GET /sca/challenges[/{id}][/decision]` | sca-service |
| Registrovat / vypsat push zařízení | `POST,GET /devices` | notification-service |
| Začít onboarding (anonymně) | `POST /onboarding/start` | party-service (M2M, bez party hlavičky) |
| Otevřít první účet po KYC | `POST /onboarding/account` | account-service (KYC brána) |

## Volající

- **retailová zákaznická aplikace** (KMP, ADR-0064) — jediný zamýšlený volající, přes veřejný internet, s JWT z realmu `openbank-customers`.

## Závislosti

- **Keycloak** — validace příchozího JWT (realm `openbank-customers`) **a** odchozí M2M token (operátorský realm `openbank`).
- **party-service** — onboarding, profil, brána KYC stavu, dohledání právního jména debtora.
- **account-service** — seznam účtů / kontrola vlastnictví / otevření prvního účtu.
- **balance-service**, **transaction-service**, **statement-service** — read proxy (vlastnictví vynuceno na edge).
- **domestic-payment-service**, **sepa-payment-service** — iniciace plateb (jen instrukce).
- **sca-service** — registrace zařízení + životní cyklus challenge (ADR-0021).
- **notification-service** — in-app feed + registrace push zařízení.
- **openbank-libs** — ServiceInfoResource (`/api/v1/info`), Docs-as-Service (`/q/openbank/docs`), BuildInfo, health.

## Byznysová hodnota

- **Jediná úzká hranice důvěry** mezi nedůvěryhodnými retailovými zařízeními a interní flotilou — deny-by-default allow-list, jedno místo pro rate limity, obranu proti zneužití a attestaci (ADR-0065).
- **Oddělení realmů** — zákazníci nikdy nedostanou token operátorského realmu; doménová důvěra personálu a zákazníků zůstává oddělená.
- **Omezení IDOR** — vlastnictví je vynuceno na edge i tam, kde upstream omezuje jen podle id, takže uhádnuté id účtu nevyteče data jiné party.
- **Zjednodušení klienta** — aplikace posílá lehká těla; edge je obohatí do plného upstream kontraktu a drží bankovní detail mimo mobilního klienta.
- **Compliance brána** — party bez KYC nemůže získat IBAN (AML/PSD2), vynuceno před přeposláním na account-service.
