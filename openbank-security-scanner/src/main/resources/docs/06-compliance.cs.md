# Compliance

## Regulatorní rámec

| Nařízení | Vztah k této službě | Implementace |
|---|---|---|
| **DORA (EU 2022/2554)** | Primární regulatorní mandát: řízení ICT rizik, detekce incidentů, reportování | ICT incident lifecycle API, P1/P2 reportování ČNB, `PlatformSecurityReport` jako ICT risk evidence |
| **EBA ICT Risk Guidelines (EBA/GL/2019/04)** | Požadavky na ICT bezpečnostní testování | OWASP Top 10 automatické kontroly každých 30 minut; příznak souladu `EBA_ICT_RISK` v platformovém reportu |
| **PSD2 RTS (EU 2018/389)** | Přísné bezpečnostní požadavky pro platební infrastrukturu | Příznak souladu `PSD2_SCA`: všechny služby dosažitelné; bezpečnostní zjištění ve službách platební cesty spouštějí alerty |
| **NIS2 (EU 2022/2555)** | Bezpečnost sítí a informačních systémů pro základní subjekty | Fleet-wide monitorování zdraví, detekce incidentů a reportovací pipeline |
| **NIST SP 800-53** | Rámec bezpečnostních kontrol | Mapování kontrol na NIST (viz krizový odkaz OWASP / NIST níže) |
| **GDPR (EU 2016/679)** | Provozní data v ICT incidentech (email assigned_to) | Pouze data interního zaměstnance; ne zákaznické PII; žádná povinnost výmazu |
| **Vyhláška ČNB 163/2014** | Domácí bezpečnostní požadavky České národní banky | Příznak `CNB_SECURITY` (skóre platformy ≥ 70); DORA reporty ČNB |

## DORA mapping (Nařízení (EU) 2022/2554)

Tato služba přímo implementuje několik povinností DORA:

| Článek | Téma | Implementace v security-scanner |
|---|---|---|
| čl. 5 | Řízení ICT rizik | služba je ICT risk monitoring nástroj pro platformu |
| čl. 9 | Identifikace ICT aktiv | `GET /api/v1/security/services` vypisuje všechna monitorovaná ICT aktiva |
| čl. 10 | Detekce anomálií | Naplánované 30minutové skeny detekují regrese; CRITICAL zjištění spouštějí alerty |
| čl. 11 | Odezva & obnova | Životní cyklus `IctIncident` (OPEN→RESOLVED), sledování RTO/RPO |
| čl. 17 | Reportování ICT incidentů | Kompletní workflow reportování incidentů: `POST /ict-incidents` → `PATCH /status` → `POST /regulatory-report` |
| čl. 23 | Reportování dohledovým orgánům | `regulatoryReportId` propojuje s podáním ČNB; audit trail přes outbox |
| čl. 24 | ICT risk testing | OWASP Top 10 automated test suite jako test digitální operační odolnosti |
| čl. 28 | Riziko třetích stran | Sondy skeneru zahrnují služby s integrací třetích stran (Keycloak, Kafka health) |

### SLA reportování ICT incidentů dle DORA

| Závažnost | Úvodní hlášení | Průběžné hlášení | Závěrečné hlášení |
|---|---|---|---|
| `P1_CRITICAL` | ČNB do 4 hodin | Každých 24 hodin do vyřešení | Do 1 měsíce od vyřešení |
| `P2_HIGH` | ČNB do 24 hodin | Každé 3 dny do vyřešení | Do 1 měsíce od vyřešení |
| `P3_MEDIUM` | Pouze interně | — | — |
| `P4_LOW` | Pouze interně | — | — |

## OWASP Top 10 2021 — mapování souladu

| Kategorie OWASP | Prováděné kontroly | NIST SP 800-53 |
|---|---|---|
| A01 — Broken Access Control | Neautentizované aktuátorové endpointy (`/q/metrics`, `/q/info`, `/q/dev`) na API portu | AC-3, AC-17 |
| A02 — Cryptographic Failures | Citlivá data (řetězce password/secret) v odpovědích health endpointu | SC-8, SC-28 |
| A03 — Injection | Nekontrolováno (pouze HTTP-level black-box) | — |
| A04 — Insecure Design | Nekontrolováno | — |
| A05 — Security Misconfiguration | Chybějící security headery (7 kontrol), CORS wildcard, expozice OpenAPI, nedosažitelná služba | CM-6, CM-7 |
| A06 — Vulnerable Components | Nekontrolováno (SCA je v CI pipeline přes CycloneDX SBOM) | SI-2 |
| A07 — Authentication Failures | Přímo nekontrolováno (autentizace pokryta vrstvou OIDC) | IA-2, IA-8 |
| A08–A10 | Mimo rozsah pro HTTP black-box kontroly | — |

## Mapování EBA ICT Risk Guidelines

Příznak souladu `EBA_ICT_RISK` v `PlatformSecurityReport.complianceStatus` je `true` pokud ≥ 80 % služeb skóruje ≥ 70.

EBA/GL/2019/04 Sekce 3.3 (ICT bezpečnostní testování) vyžaduje:
- Periodická hodnocení zranitelností IT systémů (pokryto: naplánované OWASP kontroly)
- Testování na základě scénářů (částečně: HTTP-level; penetrační testování je separátní)
- Monitorování bezpečnostních událostí (pokryto: zjištění publikována do audit-service)

## Bezpečnostní kontroly samotného skeneru

- OIDC vypnuto (interní nástroj; žádná externí attack surface pro bypass autentizace)
- Network policy: scanner je dosažitelný pouze z admin-ui a cluster-interních služeb
- Žádná zákaznická data nejsou ukládána ani zpracovávána
- Záruka outboxu: všechny bezpečnostní eventy dosáhnou audit-service i při dočasné nedostupnosti Kafky
- BootstrapVerifier blokuje dev DB hesla v produkčním profilu

## Audit trail

Každý výsledek skenu a každá změna životního cyklu ICT incidentu je:
1. Zapsána do `security_outbox`
2. Publikována do Kafka topics (`openbank.security.scan.event`, `openbank.security.ict.incident`)
3. Konzumována `audit-service` pro tamper-evident 10letou retenci

Tím vzniká regulatorní důkazní trail vyžadovaný DORA čl. 17 a požadavky inspekce ČNB.
