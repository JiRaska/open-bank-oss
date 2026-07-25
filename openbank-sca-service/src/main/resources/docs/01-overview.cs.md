# Přehled

## Co služba dělá

`openbank-sca-service` je **stroj pro silné ověření zákazníka (SCA)** na platformě OpenBank. Provádí step-up autentizaci, když si jiná služba potřebuje být jistá, že je přítomen skutečný zákazník a schválil citlivou akci. Drží:

- **Agregát ScaChallenge** — jedna autentizační výzva: party, účel (PAYMENT_INITIATION / CONSENT_GRANT / LOGIN / AGENT_ACTION / SENSITIVE_DATA_ACCESS), metoda (TOTP / PUSH_NOTIFICATION / BIOMETRIC), stav (PENDING / COMPLETED / FAILED / EXPIRED / CANCELLED), čítač pokusů, expirace a volitelná **data dynamického provázání** (částka, měna, IBAN/jméno příjemce, reference) dle PSD2 RTS čl. 5.
- **EnrolledDevice** — credential zařízení (veřejný klíč + algoritmus ES256/ED25519) zapsaný k party, slouží k ověření pozdějších podpisů schválení (ADR-0021). Privátní klíč nikdy neopustí hardwarové úložiště zařízení (Secure Enclave / Android Keystore).
- **DeviceApprovalDecision** — podpisem ověřené, dynamicky provázané rozhodnutí APPROVED/DENIED zaznamenané out-of-band zapsaným zařízením; uloženo přechodně (musí jen přežít svou výzvu).

## Co služba **NEDĚLÁ**

- ❌ Neautentizuje primární přihlášení — to je Keycloak (OIDC). SCA je *step-up* nad již ověřenou session.
- ❌ Neukládá souhlasy — záznamy souhlasů vlastní `consent-service`; ten SCA spouští.
- ❌ Neprovádí ani neautorizuje platbu — platební služby inicializují SCA výzvu a čekají na stav `COMPLETED`.
- ❌ Neschvaluje push/biometriku automaticky — záměrně (ADR-0021). Decoupled metody vyžadují skutečné, podepsané, dynamicky provázané rozhodnutí ze zapsaného zařízení.
- ❌ Sama v produkci neposílá push/SMS — deleguje na notifikační cestu; lokálně `LoggingNotificationSender` jen loguje.

## Pozice v doméně

```
   ┌──────────────────┐  inicializace SCA ┌──────────────┐
   │ platba / souhlas │ ───────────────►  │              │
   │ / psd2 / agent   │  POST /challenges │  sca-service │
   └──────────────────┘                   │              │
                                          └──────┬───────┘
   ┌──────────────────┐  verify / poll          │ outbox → Kafka
   │ volající čeká na │ ◄───────────────────────┤ (DEVICE_ENROLLED)
   │ status==COMPLETED│                          ▼
   └──────────────────┘                   ┌───────────────────┐
                                          │ onboarding cockpit│
   ┌──────────────────┐ rozhodnutí(podpis)│ (ADR-0068)        │
   │ zapsané zařízení │ ───────────────►  └───────────────────┘
   │ (zákaznická app) │  POST /decision
   └──────────────────┘
                          PostgreSQL (openbank_sca) + Redis (OTP / idempotence / rozhodnutí)
```

## Klíčové use case

| Use case | API | Událost |
|---|---|---|
| Inicializace SCA výzvy | `POST /api/v1/sca/challenges` | — |
| Ověření výzvy (OTP, nebo poll decoupled rozhodnutí) | `POST /api/v1/sca/challenges/{id}/verify` | — |
| Získání stavu výzvy | `GET /api/v1/sca/challenges/{id}` | — |
| Zápis credentialu zařízení | `POST /api/v1/sca/parties/{partyId}/devices` | `DEVICE_ENROLLED` |
| Výpis zapsaných zařízení party | `GET /api/v1/sca/parties/{partyId}/devices` | — |
| Záznam out-of-band schválení/zamítnutí zařízením | `POST /api/v1/sca/challenges/{id}/decision` | — |

## Volající

- **platební služby** (sepa, domestic, sepa-instant, …) — inicializují SCA pro PAYMENT_INITIATION a čekají na `COMPLETED` před uvolněním prostředků.
- **consent-service / psd2-service** — inicializují SCA pro CONSENT_GRANT (deklarováno jako upstream v `governance.yaml`).
- **agent gateway** (ADR-0031) — inicializuje SCA pro AGENT_ACTION (human-in-the-loop schválení akce iniciované AI).
- **zákaznická app** (ADR-0064/0065/0066) — zapisuje zařízení a posílá podepsané out-of-band rozhodnutí; vypisuje vlastní zařízení.
- **admin-ui / onboarding cockpit** (ADR-0068) — čte zapsaná zařízení pro posun onboarding read-modelu.

## Závislosti

- **PostgreSQL** (`openbank-postgres`, databáze `openbank_sca`)
- **Kafka** (`openbank-kafka`, topic `openbank.sca.challenge.event`)
- **Redis (Valkey)** — přechodné úložiště OTP, idempotenční klíče, decoupled rozhodnutí
- **Keycloak** — OIDC auth
- **OPA sidecar** — vyhodnocení politik `@Authorize` (ADR-0034, ve výchozím stavu advisory)
- **openbank-libs** — `IdempotencyStore`, `@Authorize`/authz, `ApiError`/`ErrorCode`, outbox plumbing, BuildInfo, DocsResource

## Obchodní hodnota

- **PSD2 compliance** — SCA s dynamickým provázáním je tvrdý regulatorní požadavek pro elektronické platby a přístup k účtům; tato služba je jediné auditované místo, které jej vynucuje.
- **Fail-closed dle návrhu** — push/biometrika se nikdy neschválí automaticky (ADR-0021 uzavírá kritické audit finding K2); nepoužitelný faktor je striktně bezpečnější než obejitelný.
- **Odolné vůči replay** — každé decoupled schválení je podepsané přesně nad id výzvy + rozhodnutím + částkou + příjemcem, takže zachycený podpis nelze přehrát pro jinou částku, jiného příjemce ani převrátit DENIED na APPROVED.
- **Znovupoužitelné napříč povrchy** — jeden stroj obsluhuje platby, souhlasy, přihlášení, akce agentů i přístup k citlivým datům.
