# Přehled

## Co služba dělá

`openbank-card-processing-service` je bounded context pro **karetní útratu** definovaný v [ADR-0283](../../../../docs/adr/0283-card-platform-scheme-agnostic-capability-ports.md). Vlastní:

- **Autorizaci** — acquirer předloží karetní transakci (`cardId`, částka v minor units, měna, kanál, MCC, obchodník). Služba zjistí účet a klienta karty z card-issuance, sečte útratu, kterou karta již provedla v aktuálním denním/měsíčním okně, a požádá card-issuance o rozhodnutí. Rozhodovacím bodem je card-issuance (ADR-0194 D3); tato služba odpověď zaznamená.
- **Hold** — schválená autorizace *je* hold. Dokud má stav `APPROVED` nebo `PARTIALLY_CLEARED`, blokuje `amount − cleared`. Blokovaná částka se odvozuje, nikdy se neukládá.
- **Clearing** — prezentace proti autorizaci, i částečná. Kumulativní clearing nikdy nesmí překročit autorizovanou částku a musí být ve stejné měně.
- **Zaúčtování** — každý přijatý clearing se zaúčtuje přes transaction-service jako transakce na railu `CARD`, až poté, co se clearing commitne.
- **Uvolnění** — reverzace od acquirera nebo expirace neprezentovaného holdu (výchozí 7 dní, sweep každých 15 minut).
- **Zamítnutí se také zaznamenávají** — zamítnutá autorizace je řádek a událost `card.declined.v1` nesoucí doslovně název důvodu z card-issuance.
- **Stínové fraud skórování** — každá commitnutá autorizace je oskórována fraud-service; verdikt nic nemění (ADR-0084).
- **Capability porty schémat** (ADR-0283 fáze 2) — `BinLookupPort`, `MerchantDataPort`, `TokenisationPort` a `DisputePort` z `openbank-libs-domain`, každý se simulátorem. Viz [02 — Architektura](./02-architecture.md).

## Co služba **NEDĚLÁ**

- ❌ Nepřijímá, neukládá ani neloguje PAN, CVV ani kartové údaje. Karta je referencována svým id z card-issuance (ADR-0283 D7).
- ❌ Není issuer-processor: žádné 3-D Secure, žádné PIN ani HSM operace, žádné živé spojení s karetním schématem. Jedinou vazbou procesorové strany v tomto repozitáři je **sandbox acquirer**.
- ❌ Sama nerozhoduje o schválení/zamítnutí — to dělá card-issuance.
- ❌ Fraud skórování nic neblokuje (pouze stínové).
- ❌ Žádná tolerance přečerpání clearingu podle kategorie (palivo, pohostinství): jakékoli překročení je odmítnuto.
- ❌ Tokenizace (VTS / MDES) a reklamace (VROL / Mastercom) **nejsou napojeny na vendora** — tyto programy vyžadují smlouvu. Odpovídají jen simulátory. Žádný REST endpoint této služby zatím nevystavuje tokenizaci, reklamace ani BIN lookup.

## Pozice v doméně

```mermaid
graph LR
  acq["Acquirer / sandbox acquirer"] -- "autorizace, clearing, reverzace" --> cp["card-processing-service"]
  cp -- "lookup karty + rozhodnutí" --> ci["card-issuance-service"]
  cp -- "stínové skóre" --> fr["fraud-service"]
  cp -- "zaúčtování na railu CARD" --> tx["transaction-service"]
  cp -- "outbox" --> k[("Kafka: openbank.card.processing.events")]
  cp --> db[("PostgreSQL: openbank_card_processing")]
```

## Klíčové případy užití

| Případ užití | API | Událost |
|---|---|---|
| Autorizovat karetní transakci | `POST /api/v1/card-authorizations` | `card.authorised.v1` nebo `card.declined.v1` |
| Započítat clearingovou prezentaci | `POST /api/v1/card-authorizations/{id}/clearing` | `card.cleared.v1` |
| Reverzovat zbývající hold | `POST /api/v1/card-authorizations/{id}/reversal` | `card.hold_released.v1` (`REVERSAL`) |
| Expirovat neprezentované holdy | plánovač `card-processing-hold-expiry` | `card.hold_released.v1` (`EXPIRY`) |
| Načíst jednu autorizaci | `GET /api/v1/card-authorizations/{id}` | — |
| Vypsat autorizace karty | `GET /api/v1/card-authorizations/card/{cardId}` | — |
| Sandbox nákup (autorizace + volitelný clearing) | `POST /api/v1/sandbox/acquirer/purchase` | viz výše |

## Volající

- **Integrace na straně acquirera** autentizované OIDC tokenem s `ROLE_API` / `ROLE_OPERATOR` / `ROLE_ADMIN`. Produkční adaptér acquirera ani procesoru v tomto repozitáři dnes neexistuje.
- **Sandbox acquirer** (`/api/v1/sandbox/acquirer/purchase`) — zapnutý jen v `%dev` a `%test`; jinde odpovídá 404.

## Závislosti

- **card-issuance-service** — zjištění vlastníka karty a rozhodnutí o autorizaci (fail closed: nedostupnost ⇒ zamítnutí).
- **transaction-service** — zaúčtování zúčtované útraty na railu `CARD`.
- **fraud-service** — stínové skórování.
- **PostgreSQL**, **Kafka**, **Keycloak** (příchozí OIDC i odchozí client credentials), **OPA sidecar**.
- Volitelné sandboxy vendorů pro BIN lookup: Visa Developer Platform (mTLS + API klíč), Mastercard Developers (podepisování požadavků OAuth 1.0a). Bez přihlašovacích údajů odpovídají `NOT_BOUND` a žádný požadavek neodešlou.

## Obchodní hodnota

- Jedno místo, kde se karetní útrata stává penězi: rozhodnutí, hold, clearing i zaúčtování jsou dohledatelné pro každou autorizaci.
- Limity útraty započítávají holdy v plné výši, takže rozpracované autorizace nelze využít k překročení denního či měsíčního limitu.
- Síť je konfigurační volba (`openbank.card-processing.scheme.*`), ne změna kódu — a nenakonfigurovaná síť to řekne, místo aby tiše přešla na náhradní řešení.
