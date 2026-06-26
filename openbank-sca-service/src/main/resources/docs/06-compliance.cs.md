# Compliance

`openbank-sca-service` je **money-path služba** (`rules.yaml: money_path_services`). Změny vyžadují 2 schválení + aktuální threat model (`docs/threat-models/openbank-sca-service.md`, ADR-0030). Je regulatorním kontrolním bodem pro silné ověření zákazníka.

## Regulatorní rámec

| Regulace | Vztah ke službě | Implementace |
|---|---|---|
| **PSD2** (směr. (EU) 2015/2366) + **RTS** (nař. (EU) 2018/389) | Toto *je* SCA stroj — dvoufaktorový step-up + dynamické provázání jsou povinné pro elektronické platby a přístup k účtu | životní cyklus výzvy; **dynamické provázání** váže podpis na částku + příjemce (RTS čl. 5); push/biometrika se **nikdy neschválí automaticky** (ADR-0021) |
| **AMLD** (směrnice proti praní peněz) | Autentizační důkazy podporují monitoring transakcí a vyšetřování SAR | 5letá retence výzev/rozhodnutí; `evidenceExported: true` |
| **GDPR** | `party_id`, IBAN/jméno příjemce jsou PII | pseudonymní party id; tajemství (OTP) pouze v Redisu a přechodné; PII nikdy nelogováno v plain textu |
| **DORA** (nař. (EU) 2022/2554) | Provozní odolnost kritické autentizační funkce | health probes, fault tolerance outboxu (bulkhead/CB/retry/timeout), metriky, runbooky, T0 (žádné škálování na nulu) |
| **NIS2** | Síťová a informační bezpečnost | mTLS v clusteru (Istio), security hlavičky (CSP/HSTS/X-Frame-Options), OIDC, OPA authz |

## ADR-0021 — decoupled schválení zařízení (jádro kontroly)

Kritické audit finding **K2**: push/biometrické `verify` dříve bezpodmínečně vracelo `true` — úplné obejití SCA a přímé porušení PSD2 RTS. Rozhodnutí:

1. **Fail closed.** Decoupled metody se nikdy neschválí automaticky; bez rozhodnutí zůstává výzva `PENDING`. Nepoužitelný faktor je striktně bezpečnější než obejitelný.
2. **Explicitní out-of-band schvalovací kanál.** Zapsané zařízení podepíše výzvu hardwarově podloženým klíčem (Secure Enclave / Android Keystore). Server ověří podpis nad payloadem dynamického provázání a zaznamená rozhodnutí; `verify` jej konzultuje místo hádání.

Odolnost vůči replay: podepsaný payload je `id | decision | amount | currency | creditorIban | reference`, takže zachycený podpis nelze přehrát pro jinou částku, jiného příjemce ani převrátit DENIED→APPROVED. Rozhodnutí jsou **write-once**.

## PSD2 RTS dynamické provázání (čl. 5)

```
inicializace výzvy ──► dynamicLinkingData {amount, currency, creditorIban, creditorName, reference}
                       persistováno na výzvě

zařízení schválí   ──► podepíše bajty: id | decision | amount | currency | creditorIban | reference
záznam rozhodnutí  ──► verify(signature, devicePublicKey, payload)  ── fail-closed
verify (volající)  ──► COMPLETED jen pokud existuje platné APPROVED rozhodnutí
```

## Mapování GDPR

### Právní základ (čl. 6)
- **Právní povinnost** (čl. 6(1)(c)) — PSD2 nařizuje SCA; zpracování autentizačního kontextu je nutné pro soulad.
- **Smlouva** (čl. 6(1)(b)) — provedení autentizované bankovní akce, kterou zákazník požadoval.

### Práva subjektu údajů
| Právo | Aplikace |
|---|---|
| Přístup (čl. 15) | záznamy výzev/zařízení dohledatelné dle `party_id` |
| Oprava (čl. 16) | credentialy zařízení jsou znovu zapsatelné; výzvy jsou neměnný důkaz |
| Výmaz (čl. 17) | **omezeno** — autentizační důkazy se uchovávají 5 let dle PSD2/AML; výmaz nepřebíjí právní základ |
| Omezení / Námitka | N/A (žádné profilování ani marketing) |
| Přenositelnost (čl. 20) | N/A (autentizační stav není přenositelná data poskytnutá zákazníkem) |

### Toky dat ven
- → **Kafka** `openbank.sca.challenge.event` (`DEVICE_ENROLLED`): `deviceId`, `partyId`, `credentialId`, `algorithm`, `occurredAt` — intra-OpenBank, konzumuje onboarding read-model (ADR-0068). Žádné privátní klíče, OTP ani podpisy.
- → **audit/evidence pipeline**: export autentizačních důkazů (`evidenceExported: true`).
- Žádná data neopouštějí region EU/EHP.

## Mapování DORA (nař. (EU) 2022/2554)

| Článek | Téma | Implementace |
|---|---|---|
| čl. 9 | Identifikace | `BuildInfo` (gitCommit, buildTime, version) přes `/api/v1/info` |
| čl. 10 | Detekce | Prometheus metriky + alerting na error rate / latenci |
| čl. 11 | Reakce a obnova | runbooky v `05-operations.md`; fault tolerance outboxu; RTO 15 min / RPO 5 min |
| čl. 16 | Řízení incidentů | události do audit pipeline jako důkaz |
| čl. 28 | Riziko třetích stran | žádný third-party SaaS — Postgres/Redis/Kafka/Keycloak vše self-hosted |

## Bezpečnostní kontroly

- ✅ AuthN: Keycloak OIDC, RS256 JWT
- ✅ AuthZ: `@Authorize` → OPA sidecar (ADR-0034) + vynucení vlastnictví per-party na device endpointech
- ✅ Dynamické provázání + ověření podpisu (RTS čl. 5), fail-closed verifikátor
- ✅ Idempotence: `Idempotency-Key` / `X-Request-ID` + klíč odvozený z příkazu
- ✅ Rate limiting: `openbank.rate-limit` (100 souběžných)
- ✅ Security hlavičky: CSP, HSTS, X-Frame-Options DENY, X-Content-Type-Options nosniff, Referrer-Policy, Permissions-Policy
- ✅ Tajemství přechodná: OTP jen v Redisu, TTL 300 s, invalidováno při úspěchu
- ✅ TLS: mTLS v clusteru (Istio), TLS terminace na bráně
- ⚠️ Vynucení OPA je **ve výchozím stavu advisory** (`AUTHZ_ENFORCE=false`); kontroly vlastnictví v kódu poskytují defense-in-depth, než se enforce přepne (fázový rollout, ADR-0034).
- ⚠️ `openapi.yaml` není v souladu s implementovaným kontraktem — přegenerovat jako follow-up (viz `03-api.md`).
