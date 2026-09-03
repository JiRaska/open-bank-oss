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
