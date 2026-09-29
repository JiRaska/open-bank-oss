# Runbook 0017: CRA Article 14 — hlášení zranitelností a incidentů (ENISA/CSIRT)

> Pro výrobce produktů s digitálními prvky uvedených na trh EU platí čl. 14
> od **2026-09-11**; pro open-source software stewardy platí čl. 24 odst. 3
> od **2027-12-11** (Regulation (EU) 2024/2847). Podle `SECURITY.md` OpenBank
> zatím na trh uveden není. Před použitím tohoto postupu ověřit působnost
> konkrétního produktu a roli odpovědného subjektu.
> Tento runbook je provozní procedura pro hlášení **aktivně zneužívaných
> zranitelností** a **závažných bezpečnostních incidentů** produktů s digitálními
> prvky. DORA registr (`/security/incidents`) zatím neukládá oddělené CRA
> rozhodnutí, čas uvědomění ani časy jednotlivých podání; ze samotného
> registru proto nelze prokázat splnění CRA lhůt.

## Hodiny

| Fáze | Deadline | Obsah |
|---|---|---|
| Early warning | **24 hodin od uvědomění** | Že incident/zranitelnost existuje, předmět, předpokládaný dopad |
| Full notification | **72 hodin od uvědomění** | Detaily, závažnost, IOC, mitigace |
| Final report — aktivně zneužívaná zranitelnost | **14 dní od zpřístupnění nápravného opatření** | Závažnost, dopad, náprava |
| Final report — závažný incident | **1 měsíc od 72hodinového hlášení** | Root cause, dopady, náprava |

Jedno hlášení přes **ENISA Single Reporting Platform** je adresováno
příslušnému koordinujícímu CSIRT a zpravidla současně zpřístupněno ENISA.
Postup registrace, aktuální seznam CSIRT a pokyny:
<https://www.enisa.europa.eu/topics/product-security/single-reporting-platform-srp>.
Tento dokument nepotvrzuje, že má pověřený zástupce zřízený přístup.

## Owner

- Primární: security funkcionář on-call (rota dle `/security` runbooků)
- Zástup: platform lead
- Eskalace nad rámec: CISO/vedení — při každém hlášení informovat do 24 h

## Kdy hlásit — triage kritéria

Hlásí se **aktivně zneužívaná zranitelnost** (je důkaz zneužití v divočině /
proti nám) a **závažný incident** ovlivňující bezpečnost produktu. Rozhodovací
stroma:

1. **Spadá produkt a odpovědný subjekt do působnosti CRA?** Ověřit uvedení
   na trh EU v rámci obchodní činnosti a roli výrobce nebo open-source stewarda.
   Podle `SECURITY.md` beta OpenBank zatím na trh uveden není.
2. **Zranitelnost**: je obsažena v konkrétním produktu a je aktivně
   zneužívána? CVE nebo záznam v CISA KEV sám o sobě přítomnost v produktu
   neprokazuje. Běžný nález bez aktivního zneužití patří do standardního
   vulnerability managementu, ne do hlášení podle čl. 14.
3. **Incident**: splňuje kritéria závažnosti podle čl. 14 odst. 5 (dopad na
   schopnost chránit citlivá či důležitá data nebo funkce, případně zavedení
   či spuštění škodlivého kódu)? Zapsat důvod klasifikace. Čistě provozní
   výpadek bez bezpečnostní složky není hlášením podle CRA.

Při pochybnostech o klasifikaci události po potvrzení působnosti CRA zahájit
včasné hlášení; nejistotu a zdůvodnění zaznamenat.

## Procedura (on-call)

1. **T=0 uvědomění o kvalifikované události** — odlišit od času prvotní
   detekce; zdrojem může být monitoring, security scanner, externí report či
   zákazník. Zaznamenat obě časová razítka a podklad klasifikace.
2. **Do 1 h** — založit záznam v `/security/incidents` (kategorie, závažnost,
   dotčené služby). Čas vytvoření záznamu není časem uvědomění podle CRA.
3. **Do 4 h** — triage dle kritérií výše. Rozhodnutí (hlásit/nehlásit + proč)
   a skutečné časy uchovat v zabezpečené incidentní evidenci propojené s ID
   záznamu; registr nemá samostatná CRA pole. „Nehlásit" bez zdůvodnění
   neexistuje.
4. **Do 24 h od uvědomění** — early warning přes SRP koordinujícímu CSIRT;
   informace je zpravidla zároveň dostupná ENISA. Nečekat na kompletní analýzu.
5. **Do 72 h od uvědomění** — notification se závažností, IOC a mitigacemi.
6. **Final report** — při aktivně zneužívané zranitelnosti do 14 dní od
   zpřístupnění nápravného opatření, při závažném incidentu do 1 měsíce
   od 72hodinového oznámení; doložit příčinu, nápravu a dopady.
7. **Po uzavření** — post-incident review; pokud zranitelnost vedla k releasu,
   ověřit, že SBOM nového release je aktuální (sbom-drift gate, runbook 0016).

## Vztah k ostatním povinnostem

- **DORA**: incident může podléhat oběma režimům — společné ID incidentu,
  oddělená rozhodnutí, časová razítka a hlášení podle každého režimu. DORA má
  vlastní lhůty dle klasifikace; nelze jimi nahrazovat CRA hodiny.
- **GDPR**: je-li v oběti osobní data, paralelně 72h hlášení ÚOOÚ — jiná lhůta
  běží od jiného „uvědomění", nesplést.

## Cvičení

Minimálně 1× ročně tabletop cvičení tohoto runbooku (scénář: aktivně zneužívaná
CVE v závislosti na money-path službě). Doklad o prvním cvičení (scénář,
naměřené časy, výsledek facilitátora) připojit k #8488 podle runbooku 0018;
bez doloženého výsledku nelze postup považovat za prověřený.
