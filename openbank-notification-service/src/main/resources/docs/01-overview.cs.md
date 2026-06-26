# Přehled

## Co služba dělá

`openbank-notification-service` je **centrální bod odchozí komunikace se zákazníkem** v platformě OpenBank. Služba:

- **Konzumuje požadavky na notifikaci** z Kafka topicu `openbank.notification.requests` — každý požadavek je `NotificationRequest` (partyId, kanál, šablona, příjemce, proměnné šablony) emitovaný původní doménovou službou.
- **Renderuje šablonu** na předmět + tělo. Šablony jsou enum (`ACCOUNT_OPENED`, `TRANSACTION_COMPLETED`, `KYC_APPROVED`, `KYC_REJECTED`, `OTP_CODE`, `WELCOME`, `CONSENT_REVOKED`, `ACCOUNT_FROZEN`, …).
- **Ukládá** každou notifikaci (tabulka `notifications`) se stavem doručení (PENDING → SENT / FAILED / BOUNCED).
- **Doručuje** podle kanálu: EMAIL přes reaktivní Quarkus mailer (SMTP), PUSH rozesláním na každý ACTIVE device token registrovaný pro party (FCM / APNs). SMS a IN_APP jsou stuby.
- **Spravuje registr push device tokenů** — zákaznická app registruje FCM/APNs tokeny přes `POST /api/v1/devices`; PUSH doručení čte ACTIVE tokeny pro party.
- **Emituje anonymizovaný oversight signál** do Slacku/Teams pro malý allow-list rizikových šablon ([ADR 0059](../../../../docs/adr/0059-outbound-oversight-webhooks-slack-teams.md)) — ve výchozím stavu vypnuté, bez PII z principu.
- **Vystavuje break-glass řízení výpravy** ([ADR 0047](../../../../docs/adr/0047-governed-runtime-operational-control-plane.md)) — operátor může okamžitě zastavit veškerou odchozí výpravu (jeden aktér, odložená revize) a obnovit ji jen přes four-eyes.

## Co služba **NEDĚLÁ**

- ❌ Nerozhoduje, *kdy* notifikovat — požadavek vytváří původní služby (account, transaction, kyc, consent).
- ❌ Nepracuje s penězi, zůstatky, platbami ani účetními zápisy — **není** služba na peněžní cestě.
- ❌ Neprovádí KYC/AML/screening — pouze přenáší jejich *výsledek* jako zprávu.
- ❌ Neukládá ani nevrací přes REST surový push token — token poskytovatele je jen pro zápis (PII-adjacentní).
- ❌ Neposkytuje vstupní idempotenci — akceptuje se doručení at-least-once (žádná peněžní cesta).

## Pozice v doméně

```
   ┌──────────────────┐                        ┌─────────────────────────┐
   │ account-service  │                        │ notification-service     │
   │ transaction-svc  │  openbank.notification │  ─ render šablony        │
   │ kyc-service      │  .requests (Kafka)     │  ─ uložit (PENDING)      │
   │ consent-service  │ ─────────────────────► │  ─ doručit:              │
   └──────────────────┘                        │      EMAIL → SMTP        │
                                                │      PUSH  → FCM/APNs    │
   ┌──────────────────┐  POST /api/v1/devices  │  ─ oversight → Slack     │
   │ zákaznická app   │ ─────────────────────► │      (anonymizovaný)     │
   │ (přes edge)      │   registrace push tok. └────────────┬────────────┘
   └──────────────────┘                                     │
                                                            ▼
                                                   PostgreSQL (openbank_notifications)
```

## Klíčové případy užití

| Případ užití | API / spouštěč | Kanál(y) |
|---|---|---|
| Informovat zákazníka o události | Kafka `openbank.notification.requests` (`NotificationRequest`) | EMAIL / SMS / PUSH / IN_APP |
| Registrovat push device token | `POST /api/v1/devices` | — |
| Vypsat zařízení party | `GET /api/v1/devices?partyId=…` | — |
| Vypsat / přečíst notifikace | `GET /api/v1/notifications`, `GET /api/v1/notifications/{id}` | — |
| Break-glass zastavení výpravy | `POST /api/v1/ops/dispatch/halt` | — |
| Obnovení výpravy (four-eyes) | `POST /api/v1/ops/dispatch/resume/propose` + `/approve` | — |
| Anonymizovaný oversight do Slacku | automaticky pro rizikové šablony (ADR-0059) | webhook |

## Volající

- **Produkující doménové služby** — account-service, transaction-service, kyc-service, consent-service publikují požadavky na notifikaci do Kafky.
- **zákaznická app** (přes `openbank-customer-edge`) — registruje push device tokeny; edge injektuje autoritativní `partyId` ze zákaznického JWT (prevence IDOR).
- **admin-ui** (operátoři / auditoři přes Keycloak) — čtou notifikace, řídí break-glass workflow výpravy.

## Závislosti

- **PostgreSQL** (databáze `openbank_notifications`)
- **Kafka** — vstupní topic `openbank.notification.requests`
- **SMTP mailer** (Quarkus Mailer) — doručení e-mailu; v dev/test mockovaný
- **FCM / APNs** — push poskytovatelé, ve výchozím stavu vypnutí, údaje z Vaultu
- **Slack/Teams incoming webhook** — oversight side-channel, ve výchozím stavu vypnutý
- **Keycloak** — OIDC autentizace
- **openbank-libs** — audit (`AuditEventPublisher`), governance (`Proposal` four-eyes), `PiiMask`, `ServiceInfoResource`, `DocsResource`

## Obchodní hodnota

- **Jediný řízený výstupní bod** pro veškerou komunikaci se zákazníkem — jedno místo pro šablonování, throttling a audit zpráv.
- **Odolné doručení** — outbox + plánovaná výprava s fault-tolerance (circuit-breaker/retry/bulkhead/timeout); čistě se zastaví pod break-glass řízením.
- **Oversight s ochranou soukromí z principu** — provozní rizikové signály se dostanou na ops kanál, aniž by cluster opustila zákaznická data (pozitivní allow-list + defense-in-depth PII scrubber).
- **Multi-device push** — rozeslání na všechna registrovaná zařízení zákazníka, s automatickým vyřazením tokenů odmítnutých poskytovatelem.
