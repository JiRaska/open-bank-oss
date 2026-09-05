# Runbook 0016: Security Excellence console

## Co to je

`/security/excellence` v admin-ui je jediný souhrnný pohled na bezpečnost celého
ekosystému. Agreguje read-only signály z osmi domén do jednoho skóre excelence
(0–100, známka A+–F) a doménových karet s drill-through na specializované
obrazovky, které zůstávají autoritativním zdrojem detailu.

## Doménové pilíře a zdroje

| Pilíř | Zdroj (read-only) | Detail |
|---|---|---|
| Security Posture | `/api/security` (CI scan report) | `/security` |
| ICT incidenty (DORA) | `/api/security/incidents` | `/security/incidents` |
| Fraud | `fraud-service` review queue | `/fraud` |
| AML | `aml-service` cases | `/aml` |
| Sankce | `/api/sanctions/approvals` | `/sanctions` |
| Maker-checker | `/api/approvals/pending` | `/approvals` |
| Auditní stopa | `audit-service` | `/audit` |
| Identita & KYC | `party-service` cases | `/identity-cases` |
| Supply chain (SBOM) | `/api/sbom/drift` (image↔GitOps shoda, ADR-0030 D5) | `/system/inventory` |
| Segmentace sítě | `/api/security/kpis` → `netpol` (gate `netpol-coverage-kpi`, ADR-0279 #17) | tato stránka |
| Čerstvost závislostí | `/api/security/kpis` → `freshness` (`deps-freshness.yml`, ADR-0279 #15) | tato stránka |
| Dlouhožijící credentials | `/api/security/kpis` → `credentials` (`credential-inventory.yml`, ADR-0279 #18) | tato stránka |
| DAST pokrytí | `/api/security/kpis` → `fuzz` (`api-fuzz.yml` aggregate → `fuzz-coverage` artifact, ADR-0279 #2) | tato stránka |
| Čerstvost threat modelů | `/api/security/kpis` → `threatModels` (git historie `docs/threat-models/`, money-path z rules.yaml, ADR-0279 #23) | tato stránka |
| CVE remediation | `/api/security/kpis` → `mttr` (Dependabot alerts critical+high: open count, oldest age, median fix — SLO S1 proxy, ADR-0279 #23) | tato stránka |

## KPI snapshot — kde se berou nové domény

`/api/security/kpis` servíruje `openbank-admin-ui/security-kpis.json`, CI-generovaný
snapshot z workflow `security-kpis.yml` (weekly pátek 09:23 UTC, refresh PR při změně),
který přepočítává čísla **ze stejných gate skriptů** (`check-netpol-coverage.py`,
`deps-freshness.py`, `credential-inventory.py`) — nikdy z reimplementace, aby se
konzole a gaty nemohly rozejít. Soubor se vpeče do image buildu (`COPY
openbank-admin-ui/ ./`); chybějící soubor = `not_deployed`, nikdy falešné OK.
Každý kolektor degraduje nezávisle — jedna nedostupná doména neshodí ostatních pět.

Doména **DAST pokrytí** se nepočítá ze skriptu, ale z artefaktu `fuzz-coverage`
posledního dokončeného běhu `api-fuzz.yml` (nightly 04:17 UTC od ADR-0279 #2):
agregační job slepí per-service exercised-surface záznamy (`*-ops.json`) se scope
záznamem z prepare kroku. Služba v job listu se NIKDY nepočítá jako otestovaná —
počítá se jen ta se skutečným záznamem `selected > 0`; leg, který zkolaboval před
fuzzingem, figuruje jako `no-evidence`. Excluded služby nesou důvod, ne ticho.

## Jak číst skóre

- Skóre je vážený průměr (posture 2×) **pouze z domén, které odpověděly**.
- Doména označená „Nedostupná" (nenasazená / neodpovídá / bez oprávnění) skóre
  nesnižuje — degradace je vidět na kartě, ne skrytá v čísle (ADR-0056).
- Známky: A+ ≥ 95, A ≥ 88, B ≥ 75, C ≥ 60, D ≥ 40, F < 40.

## Operativní postupy

### Doména hlásí „Nedostupné"

1. Rozliš důvod na kartě: `Nenasazeno` (v tomto prostředí služba neběží — ne
   znamená „žádné nálezy"), `Služba neodpovídá` (ověř zdraví zdrojové služby
   v `/system/health`), `Role bez oprávnění` (chybí `system:view` nebo
   doménové oprávnění).
2. Nikdy neinterpretuj nedostupnou doménu jako „v pořádku" — otevři její
   specializovanou obrazovku a ověř zdroj přímo.

### Skóre prudce kleslo

1. Seřaď podezřelé domény podle stavu karet (`Kritické` → `Degradováno`).
2. Posture doména: kritické/high nálezy vedou na `/security` s OWASP rozpisem.
3. Incidenty: otevřený kritický nebo nenahlášený incident → `/security/incidents`,
   DORA hlášení regulátorovi je povinnost s lhůtami — neodkládat.

## Monitoring

Stránka je hlídaná browser-synthetic workflow (`Admin UI browser synthetic`,
každé 2 hodiny, chromium + firefox): SSO boundary, Web Vitals rozpočty, build
attestation a auth-gate assert (neautentizovaný přístup musí skončit na
`/auth/login`; 200 = auth-bypass regrese). Evidence: journey
`admin-ui-security-excellence`, artefakty 30 dní.

## Rozšiřování

Novou doménu přidej jako: fetcher funkci (nikdy nehází, vrací typed envelope
patch), záznam v `DOMAIN_DEFS` (ikona, href, CZ/EN popis), a váhu v agregaci
skóre. Prahy ok/degraded jsou v každém fetcheru zvlášť a jsou záměrně
konzervativní.
