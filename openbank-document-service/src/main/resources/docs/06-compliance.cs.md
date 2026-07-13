# Compliance

> **Status platební cesty:** document-service **NENÍ** v `rules.yaml: money_path_services`. Generuje a
> ukládá dokumenty a orchestruje elektronický podpis; nikdy nepřesouvá peníze ani nebrání uvolnění
> prostředků. JE to **změna hranice důvěry** (vstup šablony + dat do generovaných právních dokumentů,
> držení `restricted` obsahu s 10letou retencí, orchestrace e-podpisu) ⇒ vyžaduje threat model
> (`docs/threat-models/document-service.md`, ADR-0030).

## Regulatorní rámec

| Regulace | Vztah k této službě | Implementace |
|---|---|---|
| **eIDAS** (Nař. (EU) 910/2014) | Ceremonie e-podpisu; dnes `ADVANCED`, `QUALIFIED` (QES) fáze 2 | `SignatureCeremony` + `SignatureSealPort` (PAdES pečetění fáze 2, EU DSS + QSeal/HSM) |
| **GDPR** (Nař. (EU) 2016/679) | Dokumenty obsahují reference na osoby a restricted obsah | klasifikace `restricted`, role-gated přístup, retenční okno |
| **DORA** (Nař. (EU) 2022/2554) | Provozní odolnost | health probes, outbox resilience stack, metriky, `/api/v1/info` |
| **NIS2** | Bezpečnost sítí a informací | OIDC auth, OPA authz, mTLS v clusteru, bezpečnostní hlavičky (CSP/HSTS/…) |
| **eArchiving / retence** | Právní dokumenty uchovávané jako důkaz | `governance.yaml: retentionPolicy: 10 years`; `retain_until` per dokument; WORM přes S3 Object Lock (ADR-0161, follow-up) |

## Mapování GDPR

### Právní základ (čl. 6)
- **Smlouva** (čl. 6(1)(b)) — generování a uchovávání smluv/výpisů je nezbytné pro plnění bankovní smlouvy.
- **Právní povinnost** (čl. 6(1)(c)) — uchovávání dokumentů/důkazů.

### Uchovávaná osobní data

| Data | Role | Zdroj |
|---|---|---|
| `party_ref` | reference na subjekt (pseudonymizovaná) | render požadavek |
| `metadata_json`, generovaný obsah | může obsahovat PII vložené z datové mapy | render požadavek |
| `signers_json` (party refs) | účastníci e-podpisu | požadavek ceremonie |

### Práva subjektu údajů
- **Přístup (čl. 15):** `GET /documents/{id}` + `/{id}/content`; dokumenty dle osoby přes repozitář.
- **Výmaz (čl. 17):** omezen 10letým retenčním oknem pro důkazy; archivace nastaví terminální stav, obsah
  je uchován ve WORM po dobu retence.
- **Omezení (čl. 18):** dokument lze archivovat a odebrat z aktivního užití bez smazání.

### Retence (čl. 5(1)(e))
`governance.yaml: retentionPolicy: 10 years`. `retain_until` je zachyceno per dokument; automatické
vynucení retence/výmazu je sledovaný follow-up (viz reziduální rizika v threat modelu).

## Autorizace (ADR-0034)
- Rozhodnutí deleguje na **OPA sidecar** přes `openbank-libs` `@Authorize`.
- `authz.enforce=${AUTHZ_ENFORCE:true}` — v tomto scaffoldu vynuceno ve výchozím stavu.
- Pokrytí dnes: `documentTemplate.publish` a `signatureCeremony.recordDecision` jsou anotované; dokončení
  pokrytí `@Authorize` na zbývajících endpointech je sledovaný follow-up.

## Bezpečnostní kontroly
- ✅ AuthN: Keycloak OIDC bearer (realm `openbank`).
- ✅ AuthZ: OPA sidecar (`@Authorize`); každý endpoint role-gated (reflexní guard test).
- ✅ Integrita obsahu: SHA-256 adresování obsahem; WORM úložiště plánováno (ADR-0161).
- ✅ Mitigace SSTI/XSS: bezlogický renderer + HTML-escapování dosazovaných hodnot.
- ✅ Transakční outbox s at-least-once doručením + resilience stack.
- ✅ Bezpečnostní hlavičky: CSP `default-src 'self'`, HSTS, X-Frame-Options DENY, nosniff, …
- ⚠️ Generování PDF + PAdES pečetění jsou zástupné — podepsaný artefakt zatím není kryptograficky
  ověřitelný (fáze 2, ADR-0162/0007).
- ⚠️ Vynucení retence zatím není automatizováno — TBD.
