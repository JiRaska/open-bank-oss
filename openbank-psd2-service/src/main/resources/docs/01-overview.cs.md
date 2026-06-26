# Přehled

## Co služba dělá

`openbank-psd2-service` je **okrajová PSD2 / Open Banking fasáda** platformy OpenBank. Vystavuje regulovaný Open Banking povrch licencovaným poskytovatelům třetích stran (TPP) a překládá jej do interních volání OpenBank. Nabízí tři skupiny schopností:

- **AIS — Account Information Service** — dostupné účty, zůstatky a transakce pro PSU (uživatele platební služby), podmíněné aktivním souhlasem.
- **PIS — Payment Initiation Service** — iniciace a sledování stavu pro SEPA úhrady, okamžité SEPA, české tuzemské platby a SIPO.
- **Životní cyklus souhlasů** — vytvoření / čtení / stav / zrušení PSD2 souhlasu (fasáda deleguje autoritativní úložiště souhlasů na `consent-service`).

Podporované platební produkty (`PaymentProduct`): `SEPA_CREDIT_TRANSFERS`, `INSTANT_SEPA_CREDIT_TRANSFERS`, `DOMESTIC_CZ` (ČOBS tuzemská CZ), `SIPO` (Sdružené inkaso plateb obyvatelstva).

Služba je **bezstavová** kromě transakčního outboxu; nevlastní žádná data o účtech, zůstatcích, transakcích ani souhlasech.

## Co služba **NEDĚLÁ**

- ❌ Neukládá účty, zůstatky ani transakce — čte je z `account-service` (přes výstupní port `AccountServiceClient`).
- ❌ Neukládá souhlasy — autoritativním úložištěm je `consent-service`; tato fasáda přes něj vytváří/ověřuje/ruší.
- ❌ Neprovádí ani nezúčtovává platby — iniciaci přeposílá do `transaction-service` (která směruje na SEPA/instant/tuzemské exekutory, clearing a ledger).
- ❌ Neregistruje ani neprověřuje TPP — `tpp-registry-service` drží eIDAS role (`AISP`/`PISP`); tato služba se ho jen dotazuje.
- ❌ Neprovádí SCA — silné ověření zákazníka je delegováno (viz [ADR 0021](../../../../docs/adr/0021-sca-decoupled-device-approval-no-auto-approve.md)); fasáda jen vystavuje SCA odkazy/stav.
- ❌ Neprovádí AML/sankční screening — ten probíhá downstream v platebním toku.

## Pozice v doméně

```
   ┌──────────┐  QWAC / X-TPP-ID    ┌──────────────────────┐
   │   TPP    │ ──────────────────► │ tpp-registry-service │  (kontrola role AISP/PISP)
   │ (AISP/   │                     └──────────────────────┘
   │  PISP)   │
   └────┬─────┘  Open Banking v2
        │ AIS čtení / PIS / souhlasy
        ▼
   ┌─────────────────┐  ověření souhlasu   ┌──────────────────┐
   │  psd2-service   │ ──────────────────► │ consent-service  │
   │  (fasáda)       │                     └──────────────────┘
   └──┬───────┬──────┘  čtení účtů         ┌──────────────────┐
      │       └───────────────────────────►│ account-service  │
      │   iniciace platby                  └──────────────────┘
      │       ┌───────────────────────────►┌──────────────────┐
      │       │                             │transaction-service│→ SEPA/instant/tuzemské
      │       │                             └──────────────────┘   + clearing + ledger
      ▼ outbox → Kafka (openbank.psd2.events)
   ┌─────────────────┐
   │ audit / TPP     │  (konzumenti událostí, doručení webhooků)
   │ webhooky        │
   └─────────────────┘
```

## Klíčové případy užití

| Případ užití | API | Downstream / Událost |
|---|---|---|
| Seznam dostupných účtů (AIS) | `GET /open-banking/v2/accounts` | ověření souhlasu → `account-service` |
| Získat zůstatky (AIS) | `GET /open-banking/v2/accounts/{id}/balances` | ověření souhlasu → `account-service` |
| Získat transakce (AIS, stránkované) | `GET /open-banking/v2/accounts/{id}/transactions` | ověření souhlasu → `account-service` |
| Vytvořit souhlas | `POST /open-banking/v2/consents` | `consent-service` (scope odvozen z `access`) |
| Číst / stav / zrušit souhlas | `GET`/`DELETE /open-banking/v2/consents/{id}` | `consent-service` |
| Iniciovat SEPA / instant / domestic-CZ / SIPO platbu | `POST /open-banking/v2/payments/{product}` | ověření souhlasu → `transaction-service` |
| Dotaz na stav platby | `GET /open-banking/v2/payments/{product}/{id}/status` | `transaction-service` |
| Vývojářský sandbox (fixtures) | `…/open-banking/sandbox/v2/…` | statické fixtures, bez downstream volání |

Outbox topic `openbank.psd2.events` nese asynchronní notifikace (např. zrušený souhlas, změna stavu platby, hlášení transakcí) pro audit a doručení TPP webhooků. Typy událostí TPP webhooku jsou `TRANSACTION_REPORT`, `CONSENT_REVOKED`, `PAYMENT_STATUS_CHANGED`, `ACCOUNT_STATUS_CHANGED`.

## Volající

- **Externí TPP (AISP / PISP)** — autentizováni eIDAS QWAC klientským certifikátem (`SSL-CLIENT-S-DN`, terminovaný na bráně) nebo hlavičkou `X-TPP-ID` v topologiích bez mTLS.
- **Vývojářské integrace TPP** — proti sandbox povrchu (`/open-banking/sandbox/v2`), který vrací deterministické fixtures.

## Závislosti

- **consent-service** (REST, přes port `ConsentServiceClient`) — vytvoření / ověření / stav / zrušení souhlasu.
- **account-service** (REST, přes port `AccountServiceClient`) — čtení účtů, zůstatků, transakcí.
- **transaction-service** (REST, přes port `TransactionServiceClient`) — iniciace platby, dotaz na stav.
- **tpp-registry-service** (REST klient `tpp-registry`, výchozí `http://localhost:8108`) — autorizace / kontrola role TPP.
- **Kafka** (`openbank-kafka`, topic `openbank.psd2.events`) — drain outboxu.
- **Redis (Valkey)** — idempotenční cache.
- **Keycloak** — OIDC je nakonfigurován (service-to-service), ale neautorizuje Open Banking cesty.
- **openbank-libs** — `IdempotencyStore` (Redis impl), outbox plumbing, `DocsResource`, build/service-info, bezpečnostní hlavičky.

> Všechna mezislužbová volání jsou obalena MicroProfile Fault Tolerance (timeout / retry / circuit breaker / fallback). Aktuální downstream klienti jsou **stub implementace** (`StubClients.kt`); odolné obaly (`ResilientClients.kt`) na ně delegují, dokud nepřijdou skuteční REST klienti.

## Obchodní hodnota

- **Jeden regulovaný vstupní bod** — jediný PSD2 kompatibilní povrch (AIS + PIS + souhlasy) místo vystavování interních core-banking služeb TPP.
- **Consent-first** — každé AIS čtení a PIS iniciace je podmíněno voláním ověření souhlasu; při výpadku consent-service fasáda **selhává uzavřeně** (odpírá přístup).
- **Odolnost už v návrhu** — circuit breakery a fallbacky chrání TPP před interními částečnými výpadky; iniciace platby nikdy tiše neuspěje při downstream chybě.
- **Sandbox pro onboarding** — vývojáři TPP integrují proti deterministickým fixtures, než přejdou do ostrého provozu.
