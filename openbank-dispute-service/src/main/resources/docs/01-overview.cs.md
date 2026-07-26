# Přehled

## Co služba dělá

`openbank-dispute-service` je **systém záznamu pro platební reklamace a chargebacky** na platformě OpenBank. Drží:

- **Agregát Dispute** — `reference` (`DSP-<epochMillis>`), reklamovanou `transactionId` + `accountId` + `partyId`, `disputeType` (UNAUTHORIZED / DUPLICATE / GOODS_NOT_RECEIVED / NOT_AS_DESCRIBED / CREDIT_NOT_PROCESSED / TECHNICAL_ERROR / OTHER), životní cyklus `status` (OPEN → UNDER_REVIEW → PENDING_CUSTOMER / PENDING_MERCHANT → RESOLVED_* / WITHDRAWN / ESCALATED), `resolution` (CHARGEBACK / REPRESENTMENT / ARBITRATION / WITHDRAWN / PENDING), nárokovanou `amount` + `currency`, volitelná data obchodníka a `chargebackAmount`.
- **DisputeEvidence** — podpůrné položky připojené k reklamaci (`evidenceType`, `description`, volitelná `fileReference`, `submittedBy`).
- **DisputeTimeline** — append-only auditní stopa událostí reklamace (`OPENED`, `STATUS_CHANGED`, `EVIDENCE_ADDED`, …) s jednajícím `actor`.

Reklamace nese SLA: `resolutionDeadline = filingDate + resolution-sla-days` (výchozí **45 dní**); je nakonfigurováno okno pro podání chargebacku **120 dní**.

## Co služba **NEDĚLÁ**

- ❌ Nepřesouvá peníze ani nestornuje transakci — jakýkoli reálný kredit/refundaci provádí `transaction-service` / `ledger-service`.
- ❌ Neclearuje chargebacky s externí sítí kartového schématu — není zde konektor na Mastercard/Visa.
- ❌ Nerozhoduje o podvodu — fraud scoring / AML žije v dedikovaných službách; reklamace zaznamenává lidský/operátorský workflow.
- ❌ Neukládá soubory s důkazy — ukládá **referenci** na důkaz (`fileReference`), ne binární blob.
- ❌ Neprovádí silné ověření zákazníka — SCA vlastní `sca-service`.

## Pozice v doméně

```
   ┌────────────┐   POST /disputes      ┌────────────────────┐
   │  admin UI  │ ───────────────────►  │  dispute-service   │
   │ (operátor) │                       │  (tato služba)     │
   └────────────┘                       └─────────┬──────────┘
   ┌────────────┐   POST /evidence                │ outbox → Kafka
   │ zákaznická │ ──────────────────────────────► │ openbank.disputes.dispute.event
   │  app/API   │                                 ▼
   └────────────┘                       ┌────────────────────┐
                                        │ audit-service      │
        PostgreSQL                      │ notification       │
      (db: openbank_dispute)            │ card-issuance (blk)│
                                        └────────────────────┘
```

## Klíčové případy užití

| Případ užití | API | Událost / efekt |
|---|---|---|
| Otevřít reklamaci na transakci | `POST /api/v1/disputes` | timeline `OPENED`, dispute event |
| Aktualizovat stav / řešení | `PUT /api/v1/disputes/{id}` | timeline `STATUS_CHANGED` |
| Přidat důkaz | `POST /api/v1/disputes/{id}/evidence` | timeline `EVIDENCE_ADDED` |
| Stáhnout reklamaci | `POST /api/v1/disputes/{id}/withdraw?actor=…` | status `WITHDRAWN`, resolution `WITHDRAWN` |
| Eskalovat reklamaci | `POST /api/v1/disputes/{id}/escalate?actor=…` | status `ESCALATED` |
| Získat reklamaci / dle reference | `GET /api/v1/disputes/{id}`, `…/reference/{ref}` | — |
| Seznam dle účtu / dle stavu | `GET /api/v1/disputes/account/{accountId}`, `?status=` | — |
| Číst timeline / důkazy | `GET …/{id}/timeline`, `…/{id}/evidence` | — |

## Volající

- **admin-ui** (přes Keycloak token) — operátoři a compliance pracovníci spravující reklamace
- **zákaznická app / API** — otevírání reklamace a nahrávání referencí na důkazy jménem držitele karty
- **servisní volající** (`ROLE_API`) — automatizované toky otevírající nebo aktualizující reklamace

## Závislosti

- **PostgreSQL** (databáze `openbank_dispute`)
- **Kafka** (topic `openbank.disputes.dispute.event`)
- **Redis (Valkey)** — klient nakonfigurován (zamýšlen pro idempotenci); vynucení TBD
- **Keycloak** — autentizace (realm `openbank`, klient `openbank-services`)
- **OPA sidecar** — poradní autorizace (ADR-0034), `authz.enforce=false` ve výchozím stavu
- **openbank-libs** — `authz.@Authorize`, outbox infrastruktura, BuildInfo, DocsResource

## Obchodní hodnota

- **Jediný zdroj pravdy** pro životní cyklus platební reklamace — jedna reference, jeden stavový automat, jedna časová osa.
- **Sledování regulatorních lhůt** — `resolutionDeadline` činí SLA na ochranu spotřebitele explicitní a dotazovatelnou.
- **Připraveno na audit** — každá změna stavu připojí neměnnou událost na timeline a vydá doménovou událost pro `audit-service`.
- **Efektivita operátora** — admin UI umí vypsat reklamace dle stavu/účtu, připojit důkazy a eskalovat z jediného místa.
