# Runbook 0024: Shadow incident-response swarm pilot (ADR-0244 P3)

> 7-denní / ~50 případů pilot nepeněžní incident-response swarmu. Cílem je shromáždit
> měřitelné důkazy pro lidské rozhodnutí o absolvování (graduation) do širšího HITL/
> shadow provozu. Peněžní služby do pilotu nepatří.

## Rozsah a předpoklady

- **Prostředí:** sandbox cluster (`openbank-sandbox`) výhradně.
- **Třída case:** `incident-response` v `SHADOW` delivery mode.
- **Přispěvatel:** pouze `rca-investigator`, filtrovaný OPA politikou pro SHADOW.
- **Kill-switch:** `agent.killswitch.set`/`cleared` na `agent-kill-switch-events-in`.
- **Předpoklady před startem:**
  - `agent-kill-switch-events-in` KafkaTopic a mTLS ACL jsou aplikované (PR #10602).
  - `case-coordinator-agent` běží a `delivery-mode=SHADOW` je nastavené v cílovém prostředí.
  - Repeatable smoke harness prošel: `perf/scripts/p3-shadow-kill-switch-smoke.mjs`.
  - Kill-switch je vymazáný — ověř přes `GET /api/v1/case-coordinator/cases` (žádný `CLOSED` bez `haltedAtEpochMs` není očekáván, ale pilot nesmí začít s aktivním `rca-investigator` kill-switchem).

## Role a odpovědnosti

| Role | Co smí | Co nesmí |
|---|---|---|
| Pilot runner (automatika) | Otevírat SHADOW case podle rozvrhu | Neprovádět kill-switch; neotevírat HITL/money-path case |
| On-call operátor | Vyvolat kill-switch drill, číst `/iaops/cases` | Ručně modifikovat DB stav case |
| Pilot owner (governance) | Schválit start/stop a graduation | Překročit denní tokenový/četnostní strop |

## Kadence a konvence

- **Cíl:** ~7 case/den po dobu 7 dnů = ~50 case celkem.
- **Rozvrh:** CronJob `p3-shadow-case-injector` každé **4 hodiny** (00:00, 04:00, 08:00, 12:00, 16:00, 20:00 UTC). To vejde do `maxOpensPerAgentPerHour=1` i při restartu podu.
- **subjectRef:** `p3-shadow-pilot-<ISO-DAY>-<short-uuid>` (např. `p3-shadow-pilot-20260923-a1b2`).
- **dispositionTarget:** `shadow pilot automated case`.
- **openedBy:** `case-coordinator` (vázáno na `ROLE_OPERATOR` via identity bindings).

## Denní kill-switch drill

Cíl: dokázat, že operátor dokáže zastavit běžící case bez manuálního zásahu do dat.

1. Vyber jeden aktivní (`OPEN`/`CONVERGING`) SHADOW case z `/iaops/cases`.
2. Pusť `perf/scripts/p3-shadow-kill-switch-smoke.mjs` s cílem zastavit konkrétní case, **nebo** publikuj `agent.killswitch.set` pro scope `rca-investigator` (stejný mechanismus jako smoke harness).
3. Ověř v `/api/v1/case-coordinator/cases/{caseId}`:
   - `status = CLOSED`
   - `haltedAtEpochMs > 0`
   - existuje `POLICY_DECISION` entry s `capability = case.halt`
4. Vrať pilot do provozu: `agent.killswitch.cleared`.
5. Zapiš do denního pilot logu: čas set, čas CLOSED, latence v ms, `haltReason`.

> **Co se měří:** Tato latence je **end-to-end z pohledu read-modelu** — od otevření case po okamžik, kdy `case-coordinator-agent` zapíše `POLICY_DECISION case.halt` do read-modelu. Zahrnuje Kafka job startup, zpracování signálu a refresh read-modelu; není to pouze doba zpracování signálu v jádře. Dokud neexistuje synchronní acknowledge (viz ADR-0244 P4), tato hodnota reflektuje skutečnou observabilní dobu zastavení.
>
> **Proč 240 s:** První drill 2026-09-23 naměřil ~218 s (`openedAtEpochMs=1790175651961`, `haltedAtEpochMs=1790175870000`). `POLICY_DECISION` timestamp odpovídá `haltedAtEpochMs`, tedy signál byl zpracován ve chvíli zápisu do read-modelu. Cíl <30 s by vyžadoval synchronní acknowledge nebo mnohem kratší read-model interval; pro P3 je důležitější mít reálné, opakovatelné měření než nereálný cíl.

## Měřené metriky

Pro každý case zaznamenej:

| Metrika | Zdroj | Limit / cíl |
|---|---|---|
| open → halt latency | `openedAtEpochMs`, `haltedAtEpochMs` | < 240 s (end-to-end read-model latency) |
| contested rate | `contestedRate` | < 0.35 (D9 threshold) |
| token spend | `budgetTokens - remaining` (pokud exposing) | < 200 000/case |
| contributions | `budgetContributions` | <= 40 |
| final status | `status` | CLOSED (halt) nebo SYNTHESIZED |

Každý den exportuj snapshot všech pilot case z read-modelu do `docs/runbooks/evidence/swarm-shadow-pilot/YYYY-MM-DD.json`.

## Bezpečnostní guardrails

- **Max kill-switch active time:** pokud `rca-investigator` kill-switch zůstane aktivní > 15 min, operátor musí ověřit, že drill skončil `cleared`.
- **Denní token cap:** pokud agregovaný token spend přesáhne 1 000 000/den, pozastavit injector (`kubectl -n platform patch cronjob p3-shadow-case-injector -p '{"spec":{"suspend":true}}'`).
- **Pod restart:** `case-coordinator-agent` má in-memory rate limit. Restart vymaže `openTimes`, proto se držíme 4hodinového rozvrhu — i po restartu nehrozí překročení hodinového stropu na jedné instanci.
- **No money-path:** jakýkoli pokus otevřít jinou `caseClass` než `incident-response` nebo HITL mode je mimo rozsah tohoto runbooku.

## Graduation checklist

Pilot je považovaný za úspěšný, pokud:

- [ ] ≥ 45 case bylo otevřeno podle plánu.
- [ ] ≥ 7 kill-switch drillů proběhlo úspěšně (halt i clear).
- [ ] 0 případů, kdy kill-switch nezastavil case do 60 s.
- [ ] Průměrný `contestedRate` < 0.20.
- [ ] 0 dní překročení denního token capu.
- [ ] Všechny case skončily CLOSED/SYNTHESIZED, žádný nezůstal OPEN po TTL.
- [ ] OPA audit rozhodnutí pro každý příspěvek `rca-investigator` byl zaznamenán.
- [ ] On-call operátor potvrdil, že kill-switch UI/flow je srozumitelný.

Pokud splněno → žádost o PR pro ADR-0244 P4 (HITL rozšíření). Pokud ne → prodloužit pilot nebo zpřísnit bounds.

## Vazby

- ADR-0244 — swarm koordinace a shadow/HITL režimy.
- PR #10602 — `agent-kill-switch-events-in` KafkaTopic wiring.
- PR #10606 — repeatable P3 kill-switch smoke harness.
- Issue #10607 — pilot tracking a evidence.
- Admin UI: `/iaops/cases` a `/iaops/cases/{caseId}` pro vizualizaci stavu a halt důkazu.

## Emergency stop

Okamžitě pozastaví celý pilot:

```bash
kubectl -n platform patch cronjob p3-shadow-case-injector \
  -p '{"spec":{"suspend":true}}'
# a pokud běží nějaký case, publikuj kill-switch:
# agent.killswitch.set / aggregateId = rca-investigator
```
