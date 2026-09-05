# Runbook 0017: CRA Article 14 — hlášení zranitelností a incidentů (ENISA/CSIRT)

> Povinnost platí od **2026-09-11** (Regulation (EU) 2024/2847, Art. 14; ADR-0278).
> Tento runbook je provozní procedura pro hlášení **aktivně zneužívaných
> zranitelností** a **závažných bezpečnostních incidentů** produktů s digitálními
> prvky. Systémem evidence zůstává DORA registr (`/security/incidents`) — tento
> runbook ho neduplikuje, jen přidává CRA hodiny a adresáty.

## Hodiny (nezpochybnitelné, běží od „uvědomění")

| Fáze | Deadline | Obsah |
|---|---|---|
| Early warning | **24 hodin** | Že incident/zranitelnost existuje, předmět, předpokládaný dopad |
| Full notification | **72 hodin** | Detaily, závažnost, IOC, mitigace |
| Final report | **14 dní** (po nápravě) | Root cause, aplikovaná náprava, dopady |

Adresát: **ENISA single reporting platform** + národní CSIRT (ČR: NÚKIB).
Kde: <https://www.enisa.europa.eu/topics/incident-reporting> — kanál a přístupy
drží owner (níže), přístup NESMÍ být jediný člověk (bus factor = 1 je finding).

## Owner

- Primární: security funkcionář on-call (rota dle `/security` runbooků)
- Zástup: platform lead
- Eskalace nad rámec: CISO/vedení — při každém hlášení informovat do 24 h

## Kdy hlásit — triage kritéria

Hlásí se **aktivně zneužívaná zranitelnost** (je důkaz zneužití v divočině /
proti nám) a **závažný incident** ovlivňující bezpečnost produktu. Rozhodovací
stroma:

1. **Je produkt s digitálními prvky v EU scope?** OpenBank platforma a
   customer-facing aplikace: ano.
2. **Zranitelnost**: existuje CVE/interní finding? Je aktivně zneužívána
   (exploit v přírodě, CISA KEV, pozorovaný útok)? → hlásit 24h/72h.
   Běžný nález bez zneužití → standardní vulnerability management, ne Art. 14.
3. **Incident**: bezpečnostní událost s dopadem na dostupnost/integritu/
   důvěrnost produktu nebo jeho zabezpečení → hlásit. Čistě provozní outage
   bez security složky → DORA režim, ne CRA.

Při pochybnostech: **hlásit early warning** — přehlášení je lehčí vysvětlit
než pozdní hlášení (sankce až €15M / 2,5 % obratu, Art. 64).

## Procedura (on-call)

1. **T=0 detekce** — zdroj: synthetic monitoring, security scanner, Trivy,
   externí report (security.txt kanál), zákazník.
2. **Do 1 h** — založit záznam v `/security/incidents` (kategorie, závažnost,
   dotčené služby). Záznam je trvalý důkaz — časová razítka jsou auditovatelné.
3. **Do 4 h** — triage dle kritérií výše. Rozhodnutí (hlásit/nehlásit + proč)
   zapsat do záznamu. „Nehlásit" bez zdůvodnění neexistuje.
4. **Do 24 h** — early warning přes ENISA platformu + NÚKIB. Obsah viz tabulka.
   Nesmí čekat na kompletní analýzu.
5. **Do 72 h** — full notification se závažností, IOC a mitigacemi.
6. **Do 14 dní po nápravě** — final report: root cause, náprava, dopady.
7. **Po uzavření** — post-incident review; pokud zranitelnost vedla k releasu,
   ověřit, že SBOM nového release je aktuální (sbom-drift gate, runbook 0016).

## Vztah k ostatním povinnostem

- **DORA**: incident může podléhat oběma režimům — jeden záznam, dvě hlášení,
  různé hodiny (DORA má vlastní lhůty dle klasifikace). Nesloučit hlášení,
  sloučit evidenci.
- **GDPR**: je-li v oběti osobní data, paralelně 72h hlášení ÚOOÚ — jiná lhůta
  běží od jiného „uvědomění", nesplést.

## Cvičení

Minimálně 1× ročně tabletop cvičení tohoto runbooku (scénář: aktivně zneužívaná
CVE v závislosti na money-path službě). První cvičení: do 2026-09-11 — runbook
bez cvičení je papír.
