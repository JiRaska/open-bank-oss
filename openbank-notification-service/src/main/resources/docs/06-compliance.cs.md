# Compliance

> **Peněžní cesta:** Ne. `openbank-notification-service` **není** uveden v `rules.yaml: money_path_services`. Nenese prostředky, zůstatky ani účetní zápisy, takže nevyžaduje gate 2 schválení + threat-model, který mají služby na peněžní cestě. Přesto je to procesor **confidential** dat (zpracovává PII příjemců a obsah událostí zákazníka) a výstupní bod, takže níže uvedené kontroly stále platí.

## Regulatorní rámec

| Regulace | Vztah k této službě | Implementace |
|---|---|---|
| **GDPR** | Zpracovává PII příjemce (e-mail/telefon), identifikátory party, push tokeny, těla zpráv | PiiMask v logu, token jen pro zápis přes REST, 2letá retence, oversight egress bez PII z principu |
| **DORA** (Reg. (EU) 2022/2554) | Provozní odolnost komunikačního kanálu | health probes, fault-tolerant outbox (circuit-breaker/retry/bulkhead/timeout), break-glass řízení (ADR-0047), audit trail, runbooky |
| **PSD2** (Reg. (EU) 2015/2366) | Doručuje notifikace související s transakcemi / SCA (např. OTP_CODE) jménem platebních toků | renderuje & doručuje; OTP/tajné šablony nikdy neegrešují do oversight kanálu |
| **AML / AMLD** | Přenáší AML výsledky (ACCOUNT_FROZEN, KYC_REJECTED) — screening **neprovádí** | oversight allow-list je vystaví jako anonymizované rizikové signály; žádná AML rozhodovací logika zde |
| **NIS2** | Síťová & informační bezpečnost výstupní služby | mTLS uvnitř clusteru (Istio), bezpečnostní response hlavičky (CSP/HSTS/X-Frame-Options), network policies, audit log |
| **eIDAS / doručení SCA** | Kanál pro doručení OTP pro silné ověření zákazníka | šablona OTP_CODE, text s 5min platností; tajemství opouští cluster jen směrem k zákazníkovi |

## GDPR mapování

### Role & právní základ

Služba je **zpracovatel** jednající za správce OpenBank; původní služba (account/transaction/kyc/consent) je správcem podkladové události.

- **Smlouva** (čl. 6(1)(b)) — transakční/servisní notifikace nutné k plnění smlouvy se zákazníkem.
- **Právní povinnost** (čl. 6(1)(c)) — bezpečnostní/SCA notifikace (OTP), regulatorní komunikace.
- Marketingové šablony (např. WELCOME) by vyžadovaly **souhlas** (čl. 6(1)(a)) spravovaný upstream — tato služba pouze renderuje & doručuje.

### Práva subjektů údajů

| Právo | Aplikace |
|---|---|
| Přístup (čl. 15) | `GET /api/v1/notifications?partyId=…`, `GET /api/v1/devices?partyId=…` vrací záznamy subjektu (tokeny vyloučeny) |
| Oprava (čl. 16) | opravy příjemce/kontaktu probíhají upstream (party-service); notifikace jsou neměnná historie |
| Výmaz (čl. 17) | aplikovatelný po 2letém retenčním okně; realizováno upstream výmazem party + purge (purge úloha **TBD**) |
| Omezení (čl. 18) | break-glass `halt` zastaví veškerou odchozí výpravu |
| Přenositelnost (čl. 20) | N/A — notifikace jsou odvozené záznamy, ne data poskytnutá subjektem |
| Námitka (čl. 21) | marketingové preference spravovány upstream (consent-service) |

### Záznamy o zpracování (čl. 30)

Každý přechod řízení výpravy emituje `AuditEvent` (aktér, operace, zdroj, výsledek, důvod) do `audit-service` — podpora čl. 30 a DORA čl. 17.

### Toky dat ven

| Cíl | Data | Pozn. |
|---|---|---|
| **SMTP mailer** | adresa příjemce + vyrenderované tělo | doručení EMAIL; stejná hranice správce, reálný egress do schránky zákazníka |
| **FCM / APNs** (Google / Apple) | push token poskytovatele + push text | **třetí strany jako zpracovatelé**, ve výchozím stavu vypnuté; jen když je push zapnutý. Podléhá smlouvám se zpracovateli |
| **Slack / Teams** (oversight) | jen anonymizovaný `OversightSignal` (název šablony, kanál, stav, čas) | **žádná zákaznická data** — pozitivní allow-list (ADR-0059) + IBAN/PAN/e-mail scrubber; ve výchozím stavu vypnuté |
| **audit-service** (Kafka) | metadata přechodu řízení výpravy | uvnitř OpenBank, stejný správce |

EMAIL a PUSH doručení opouštějí cluster z nutnosti (míří k zákazníkovi); FCM/APNs zavádějí mimo-EU sub-procesory (Google/Apple) — relevantní pro posouzení přeshraničního přenosu, když je push zapnutý.

### Retence (čl. 5(1)(e))

| Data | Retence |
|---|---|
| `notifications` | 2 roky (governance manifest), poté purge — notifikace jsou komunikační záznamy, **nepodléhají** 10leté AML retenci |
| `device_tokens` | po dobu registrace; INVALID tokeny vypadnou z rozesílání |
| logy řízení výpravy | okno provozní evidence (append-only, DORA čl. 17) |

## DORA mapování (Reg. (EU) 2022/2554)

| Článek | Téma | Implementace |
|---|---|---|
| čl. 9 | Identifikace | `BuildInfo` (gitCommit, buildTime, version) v `/api/v1/info` |
| čl. 10 | Detekce | Prometheus metriky, alerting na consumer-lag |
| čl. 11 | Reakce & obnova | break-glass halt/resume (ADR-0047), runbooky v `05-operations.md` |
| čl. 16/17 | Řízení incidentů & logování | audit události při každém přechodu řízení; append-only log výpravy (rekonstruovatelný k bodu v čase) |
| čl. 28 | Riziko třetích stran | FCM/APNs jsou externí sub-procesory — ve výchozím stavu vypnuté, údaje ve Vaultu; SMTP self-hosted v sandboxu |

## ADR-0059 — oversight webhook (soukromí z principu)

Slack/Teams oversight side-channel je jediná cesta, kterou jakýkoli signál egrešuje do nezákaznického externího systému. Je navržen tak, aby únik vyžadoval selhání **dvou nezávislých kontrol**:

1. **Pozitivní allow-list schéma** — serializuje se jen `OversightSignal` (název enum šablony, kanál, stav, čas); PII nesoucí `variables`, `recipient`, surové `partyId`, jména, IBANy a částky jsou nedosažitelné. Egrešují jen rizikové šablony (`TRANSACTION_FAILED`, `KYC_REJECTED`, `ACCOUNT_FROZEN`, `CONSENT_REVOKED`) — úspěšné/tajné šablony (`WELCOME`, `OTP_CODE`, `TRANSACTION_COMPLETED`) záměrně chybí.
2. **Defense-in-depth scrubber** — `scrubPii` maskuje tokeny vypadající jako IBAN, PAN (13–19 číslic) a e-mail před odesláním, chrání proti budoucímu driftu schématu.

Ve výchozím stavu vypnuté; best-effort (selhání webhooku nikdy nezhodí ani neblokuje výpravu notifikací).

## ADR-0047 — řízený break-glass

Zastavení odchozích notifikací je bezpečný směr, takže **halt** je break-glass jednoho aktéra, který nabývá účinnosti okamžitě a označí povinnou odloženou revizi. **Resume** zvyšuje riziko a je gated přes **four-eyes**: schvalovatel se musí lišit od navrhovatele (vynuceno v `openbank-libs` governance — `MakerCheckerViolation` → HTTP 422), ne konvencí. Log žádaného stavu je append-only a verzovaný; každá replika konverguje na nejnovější snapshot bez per-pod RPC.

## Bezpečnostní kontroly

- ✅ Validace vstupu (kontroly povinných polí, parsování enum platformy) → `400`
- ✅ AuthN: Keycloak OIDC, RS256 JWT
- ✅ AuthZ: `@RolesAllowed` per endpoint; aktér řízení výpravy z JWT subjektu (ne z těla)
- ✅ Prevence IDOR: device `partyId` injektováno edgem ze zákaznického JWT
- ✅ Důvěrnost tokenu: push token jen pro zápis přes REST, maskovaný v logu
- ✅ Důvěrnost tajemství: těla OTP_CODE se doručí, ale neukládají — redakce při zápisu i při čtení (GDPR čl. 5 odst. 1 písm. c); uložené OTP by operátorovi umožnilo dokončit zákazníkovo SCA, ADR-0021)
- ✅ Minimalizace egresu: push + oversight ve výchozím stavu vypnuté; oversight bez PII z principu
- ✅ Odolnost: outbox circuit-breaker/retry/bulkhead/timeout; break-glass halt
- ✅ Bezpečnostní hlavičky: CSP, HSTS, X-Frame-Options DENY, X-Content-Type-Options nosniff, Referrer-Policy, Permissions-Policy
- ✅ Audit: každý přechod řízení výpravy → audit-service
- ✅ Secrets: jen dev placeholdery; prod secrets přes Vault ExternalSecret
- ⚠️ Automatizovaná retenční/purge úloha: **zatím neimplementováno** (TBD) — 2letá retence aktuálně vynucována provozně
