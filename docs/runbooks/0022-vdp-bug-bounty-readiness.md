# Runbook 0022: Vulnerability Disclosure Program — provozní readiness

> ADR-0279 workstream 4, bod #25. SECURITY.md (veřejný reporting kanál) je
> *právní a komunikační* polovina VDP; tento runbook je *provozní* polovina —
> co se děje s reportem po přijetí, jaké SLA platí, a kdy se VDP stane
> bug bounty. Dokud tento runbook není projetý tabletop cvičením, VDP je
> „publikováno, neprovozováno".

## SLA a tok reportu

| Krok | SLA | Majitel |
|---|---|---|
| Přijetí + potvrzení reportérovi | 3 pracovní dny | security on-call |
| Triage + severita (CVSS 4.0) | 5 pracovních dnů | security lead |
| Hotfix na main (critical/high) | dle SECURITY.md safe-harbor okna | vedení služby |
| Veřejné zveřejnění (GHSA) | po fixu + 14 dnů grace, koordinovaně s reportérem | security lead |

Tok: report (security.txt kanál / GitHub Security Advisory) → **privátní**
Security Advisory (nikdy veřejný issue — AGENTS.md to vynucuje a toto je
provozní kontrola téhož) → triage → fix PR (linkuje advisory, ne veřejné
issue) → GHSA publish → credit reportérovi.

## Kontroly před spuštěním (checklist readiness)

- [ ] Security.txt kanál je monitorovaný — testovací zpráva dostane odpověď
      do SLA (měřit kvartálně, zapsat čas).
- [ ] Privátní advisory workflow projet tabletop style: jeden cvičný report
      ročně projde celý tok vč. GHSA draftu (publikuje se jen „credit"
      cvičného reportéra, draft se smaže).
- [ ] Duplicita s interním chytrostí: reportérův finding se porovná s
      backlogem — pokud ho interní kontrola (gate/fuzz/pentest) už zná,
      zapisuje se, *proč* ho gate nechytil dřív. To je zpětná vazba do
      ADR-0279, ne ostuda.
- [ ] Safe-harbor formulace v SECURITY.md odpovídá právnímu review
      (aktualizace při změně jurisdikce nasazení).

## Bug bounty — aktivační kritéria

Bounty program se zapíná, teprve když platí VŠECHNO:

1. VDP běží ≥ 2 kvartály se splněnými SLA (měřeno z advisory časosběrných dat).
2. Median time-to-triage ≤ 3 dny za poslední 2 kvartály.
3. Trvalé kontroly z runbooku 0019 pokrývají poslední 4 kvartály findingů
   (žádný otevřený `needs-permanent-control` starší 30 dnů).
4. Vyhrazený rozpočet + právní rámec (scope, safe harbor, payout tabulka)
   schválen vedením.

Do té doby: kredit a thanks, ne peníze. Bounty bez provozní zralosti kupuje
šum, ne signál — duplicitní reporty známých dluhů by stály víc než samotné
zranitelnosti.

## Vazby

- SECURITY.md — veřejná fasáda; tento runbook ji nesmí rozporovat (při změně
  SLA měnit obojí v jednom PR).
- Runbook 0019 — interní pentest; externí reporty se řadí do stejné
  `pentest-finding` evidence s origin labelem `external-report`.
- CRA Art. 14 (runbooky 0017/0018) — aktivně zneužívaná zranitelnost z VDP
  reportu spouští Art. 14 hodiny; to je důvod, proč triage SLA je v dnech,
  ne týdnech.
