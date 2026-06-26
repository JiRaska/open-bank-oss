# Přehled

## Co služba dělá

`openbank-onboarding-service` je **read-model projekce onboardingového trychtýře** (ADR-0068). Onboarding zákazníka je distribuovaný stavový automat bez orchestrátoru, choreografovaný napříč třemi službami, z nichž každá vlastní jeden výsek pravdy (party, KYC, SCA). Tato služba pozoruje všechny tři a materializuje jednotný dotazovatelný pohled pro onboardingový cockpit v admin-UI. Drží:

- **OnboardingRecord** — jeden řádek na party: `legalName`, `email`, `partyStatus`, `kycCaseId`, `kycStatus`, `scaEnrolled`, `deviceCount`, odvozenou `funnelStage` a `blockedReason`.
- **Čistou, otestovanou funkci odvození fáze trychtýře** (`FunnelStage.derive`), která kombinuje dimenze party + KYC + SCA do jediné kanonické fáze pro sloupce cockpit boardu.

Záznam je sestaven z příchozích doménových událostí; **nikdy není zdrojem pravdy**.

## Co služba **NEDĚLÁ**

- ❌ Nevlastní stav party — to je `party-service`.
- ❌ Nevlastní KYC případy ani nerozhoduje o schválení/zamítnutí — to je `kyc-service`.
- ❌ Neprovádí enrollment SCA zařízení — to je `sca-service` (ADR-0066).
- ❌ Nezapisuje zpět do party/kyc/sca — pouze projektuje jejich události.
- ❌ Nepublikuje události — nemá outbox; je čistým konzumentem.
- ❌ (Zatím) nehostuje frontu schvalování „čtyř očí“ ani operátorský step-up. ADR-0068 tyto primitivy umisťuje do `openbank-libs` a vynucuje je ve vlastnící službě; projekce je pozoruje. V této verzi existuje jen čtecí API.

## Pozice v doméně

```
   ┌──────────────┐  party.events   ┌──────────────────────┐
   │ party-service│ ──────────────► │                      │
   └──────────────┘                 │                      │
   ┌──────────────┐  kyc.events     │ onboarding-service   │
   │ kyc-service  │ ──────────────► │ (read-model)         │
   └──────────────┘                 │                      │
   ┌──────────────┐  sca.events     │                      │
   │ sca-service  │ ──────────────► │                      │
   └──────────────┘                 └─────────┬────────────┘
                                              │ GET /api/v1/onboarding/*
                                              ▼
                                    ┌──────────────────────┐
                                    │ admin-ui cockpit      │
                                    └──────────────────────┘
                                              │
                                              ▼
                                       PostgreSQL
                                  (db: openbank_onboarding,
                                   tabulka: onboarding_records)
```

## Klíčové případy užití

| Případ užití | API | Zdrojová událost / události |
|---|---|---|
| Registrace nového žadatele do trychtýře | — (projektováno) | `PARTY_CREATED` (`openbank.party.events`) |
| Posun žadatele při změně stavu party | — (projektováno) | `PARTY_STATUS_CHANGED` |
| Sledování otevření KYC případu | — (projektováno) | `KYC_CASE_OPENED` (`openbank.kyc.events`) |
| Sledování průběhu / rozhodnutí KYC | — (projektováno) | `KYC_CASE_STATUS_CHANGED` / `KYC_CASE_APPROVED` / `KYC_CASE_REJECTED` |
| Označení enrollmentu SCA passkey | — (projektováno) | `DEVICE_ENROLLED` (`openbank.sca.events`) |
| Výpis žadatelů, volitelně dle fáze | `GET /api/v1/onboarding/records?stage=…` | — |
| Detail žadatele | `GET /api/v1/onboarding/records/{partyId}` | — |
| KPI počty trychtýře dle fáze | `GET /api/v1/onboarding/funnel` | — |

## Volající

- **admin-ui** (přes Keycloak token, skrze BFF) — operátoři, compliance při vykreslování onboardingového cockpitu (KPI dlaždice, stage board, detail žadatele).

Čtecí API nemají žádné strojové konzumenty; navazující služby konzumují zdrojové události přímo z party/kyc/sca, nikoli z této projekce.

## Závislosti

- **PostgreSQL** (databáze `openbank_onboarding`, tabulka `onboarding_records`)
- **Kafka** — pouze příchozí, topiky `openbank.party.events`, `openbank.kyc.events`, `openbank.sca.events`
- **Keycloak** — OIDC autentizace (realm `openbank`, klient `openbank-services`)
- **openbank-libs** — sdílená runtime infrastruktura (`ServiceInfoResource` `/api/v1/info`, `DocsResource` pro tuto dokumentaci, build metadata, filtr verze API)

## Obchodní hodnota

- **Jednotný provozní pohled na onboarding** — levné počty dle fáze a „kde každý žadatel uvázl“ bez fan-outu napříč třemi službami.
- **Hygiena bounded contextu** — trychtýř je průřezový (party + KYC + SCA); oddělený read-model drží KYC zaměřené na rozhodnutí, které vlastní, a nezatěžuje money-path službu (ADR-0068, zvažované alternativy).
- **Znovusestavitelnost** — jako čistá projekce může být read-model znovu naplněn přehráním zdrojového logu událostí; nikdy nemůže porušit doménové invarianty.
- **Compliance-adjacent** — zviditelňuje konce „schváleno, ale bez passkey“, „neúspěšný sankční screening“ a „uvázlo na dokumentech“, na které musí operátoři a compliance reagovat.
