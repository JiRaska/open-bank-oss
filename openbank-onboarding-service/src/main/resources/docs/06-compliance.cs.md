# Compliance

`openbank-onboarding-service` je **read-model projekce** onboardingového trychtýře. Nedrží peníze a není systémem záznamu, ale je **KYC-decision-adjacent**: materializuje stav party + KYC + SCA a je provozní plochou onboardingového cockpitu. Dle ADR-0068 proto dědí **money-path review rigour** (2 schválení + threat model), přestože není v `rules.yaml: money_path_services`.

## Regulatorní rámec

| Regulace | Vztah k této službě | Implementace / stav |
|---|---|---|
| **AMLD / ČNB AML-KYC** | Zviditelňuje onboarding/KYC trychtýř — „uvázlo na dokumentech“, „neúspěšný sankční/PEP screening“, „schváleno, ale bez passkey“ — na který musí compliance reagovat | Projektuje `kyc.events`; kontrola **override** sankce/PEP (čtyři oči, COMPLIANCE+SUPERVISOR) žije ve vlastnící službě dle ADR-0068 §5, zde pozorována. Retence 7 let (`governance.yaml`) |
| **GDPR** | `legal_name` a `email` jsou PII držené v projekci | Klasifikace confidential; maskování `PiiMask` dle role je cíl ADR-0068 §6 (zde TBD); výmaz je čtyřoký, step-up-gated akce ve vlastnící službě |
| **PSD2** | Neaplikuje se na úrovni cockpitu | Login/SCA jsou ADR-0021/0066; tato služba jen pozoruje dokončení enrollmentu SCA zařízení (`DEVICE_ENROLLED`) |
| **DORA** | Provozní odolnost čtecí plochy | health probes, OTel trasování, Prometheus metriky, projekce znovusestavitelná z logu událostí, runbooky (viz [05 — Provoz](./05-operations.md)) |
| **NIS2** | Síťová a informační bezpečnost | OIDC autentizace, bezpečnostní response hlavičky (`X-Content-Type-Options`, `X-Frame-Options: DENY`, HSTS), in-cluster mTLS na úrovni platformy |
| **PCI DSS** | Neaplikuje se | v onboardingovém read-modelu nejsou data držitelů karet (ADR-0068, dopad na compliance) |

## GDPR mapování

### Právní základ (Art. 6)

- **Právní povinnost** (Art. 6(1)(c)) — primární: projekce existuje pro provoz a dohled nad regulovaným procesem onboardingu KYC/AML.
- **Smlouva** (Art. 6(1)(b)) — sekundární: onboardingová cesta vede k zákaznické smlouvě.

### Držená osobní data

| Pole | Zdrojová událost | Klasifikace |
|---|---|---|
| `legal_name` | `PARTY_CREATED` | PII — přímý identifikátor |
| `email` | `PARTY_CREATED` | PII — přímý identifikátor |
| `party_id`, `kyc_case_id` | události party / kyc | pseudonymizované reference |

### Práva subjektu údajů

| Právo | Aplikace |
|---|---|
| Přístup (Art. 15) | `GET /api/v1/onboarding/records/{partyId}` vrací projektovaný záznam subjektu |
| Oprava (Art. 16) | zde se neopravuje — opravy tečou z událostí vlastnící služby a přeprojektují se |
| Výmaz (Art. 17) | řádek projekce je odstraněn/znovu sestaven v důsledku výmazu provedeného ve vlastnící službě; ADR-0068 činí výmaz **nevratnou, čtyřokou, operátorským step-upem gated** akcí. Omezeno AML 7letou retencí tam, kde existuje regulovaný záznam |
| Omezení (Art. 18) | odráží se přes `party_status=SUSPENDED` ⇒ `funnel_stage=BLOCKED` |
| Přenositelnost (Art. 20) | N/A — není systémem záznamu |
| Námitka (Art. 21) | N/A — žádné marketingové zpracování |

### Maskování PII dle role

ADR-0068 §6 předepisuje `PiiMask` dle role: `COMPLIANCE` vidí `legal_name`/`email` odmaskované; `OPERATOR`/`VIEWER` vidí maskované hodnoty. **Stav:** jde o dokumentovaný cíl. Současná REST vrstva ještě nenese gating dle rolí (viz [03 — API](./03-api.md)) — uzavření je známý follow-up před produkcí.

### Datové toky

- **Dovnitř:** `openbank.party.events`, `openbank.kyc.events`, `openbank.sca.events` (intra-OpenBank, stejný správce).
- **Ven:** pouze čtecí API do admin-UI cockpitu (operátor/compliance, přes Keycloak token skrze BFF). Žádní externí příjemci; žádná data neopouštějí platformu.

## DORA mapování (Reg. (EU) 2022/2554)

| Článek | Téma | Implementace |
|---|---|---|
| Art. 9 | Identifikace | `BuildInfo` (gitCommit, buildTime, version) v `/api/v1/info` |
| Art. 10 | Detekce | Micrometer/Prometheus metriky, OTel trasy, viditelnost consumer lagu |
| Art. 11 | Odezva & obnova | runbooky v [05 — Provoz](./05-operations.md); **rebuild projekce** ze zdrojového logu událostí je primitivum obnovy (RPO ohraničeno retencí Kafky) |
| Art. 17 | Řízení incidentů & rekonstrukce | každá onboardingová **akce** (ve vlastnící službě) je rekonstruovatelná z hash-řetězeného audit trailu; samotná projekce je znovusestavitelná z událostí (ADR-0068, dopad na compliance) |
| Art. 28 | Riziko třetích stran | žádné SaaS třetích stran — vše self-hosted |

## AML — kontrola override sankce / PEP

Nejcitlivější onboardingová akce vůči compliance — přepsání **neúspěšného** screeningu `SANCTIONS` či `PEP` z `FAILED → PASSED` — je dle ADR-0068 §5 samostatná auditovaná operace (`kyc.check.override`) vyžadující navrhovatele `COMPLIANCE` **a** potvrzovatele `SUPERVISOR` (čtyři oči) plus povinné volné textové odůvodnění. **Žadatel, který neuspěl ve screeningu, nemůže být nikdy posunut jediným kliknutím.** Tato kontrola je vynucena ve **vlastnící** službě (kyc-service) přes primitivum „čtyř očí“ z `openbank-libs`; onboarding-service jen **pozoruje** výsledný stav a (v cílovém návrhu) vykresluje frontu schvalování. Sama nemutuje KYC stav.

## Audit trail

Tato služba neprovádí žádné mutace stavu party/kyc/sca, a proto neemituje doménové události. Auditovatelné onboardingové **akce** emitují vlastnící služby do audit pipeline (hash-řetězené, ADR-0029), s `before`/`after`, povinným `reason` a `traceId`; aktéři typu AI agent jsou atribuováni `actorType=AI_AGENT` + `model_id` + `human_approver` (ADR-0031). Timeline cockpitu (admin-UI) tento trail vykresluje; tato služba přispívá čtecím kontextem na žadatele.

## Bezpečnostní kontroly

- ✅ AuthN: Keycloak OIDC, Bearer JWT (realm `openbank`); vypnuto jen v `%dev`/`%test`
- ✅ Bezpečnostní hlavičky: `X-Content-Type-Options=nosniff`, `X-Frame-Options=DENY`, `X-XSS-Protection`, `Referrer-Policy`, HSTS
- ✅ Rate limiting: `openbank.rate-limit.enabled=true`, max 100 souběžných requestů
- ✅ Pouze čtecí API plocha — žádné mutační endpointy ke zneužití
- ✅ Odolný příjem — poison-pill události jsou acknowledgnuté + zalogované, nikdy nezaseknou konzumenta
- ✅ Tajemství: dev placeholdery (`CHANGE_ME_LOCAL_DEV_ONLY`) musí být v ne-dev nahrazeny přes Vault (ADR-0017)
- ⚠️ AuthZ: `@Authorize`/OPA **enforce** dle rolí (ADR-0068 §7) a `PiiMask` dle role (§6) **ještě nejsou zadrátovány** — hlavní mezera před produkcí
- ⚠️ Operátorský step-up (Keycloak re-auth pro nevratné akce, ADR-0068 §8) je záležitost vlastnící služby/admin-UI, zde není přítomen
