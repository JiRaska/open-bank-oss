# Runbook 0020: Security chaos drill — odolnost detekce a degradace

> ADR-0279 workstream 2, bod #21. Navazuje na ADR-0242 (quarterly DR & chaos
> drill s měřeným RTO/RPO) — **toto je jeho security rameno**: ne „přežije
> služba výpadek", ale „přežije detekce útok a degraduje se bezpečně".
> Kadence: 2× ročně, vždy na sandboxu, vždy mimo money-path business hours.

## Proč zvlášť od DR drillu

DR drill měří RTO/RPO při výpadku infrastruktury. Security chaos měří něco
jiného: **zda bezpečnostní kontroly fungují, když je část systému mrtvá nebo
lže** — a zda služba při selhání bezpečnostní závislosti degraduje *bezpečně*
(fail-closed), ne *pohodlně* (fail-open).

## Scénářová matice (rotují se, min. 3 za drill)

| # | Chaos injekce | Co se měří | Očekávané chování |
|---|---|---|---|
| C1 | Zabij Keycloak pod na sandboxu | authz hrana | API odpovídá 401/503, NIKDY 200; alert `KeycloakDown` + `AuthzDenySpike` do 5 min |
| C2 | Zabij Falco daemonset | runtime detekce | alert na absence Falco heartbeatu; security rule pack stále detekuje Loki-signály (vrstvy jsou nezávislé) |
| C3 | Zabij Loki / Prometheus ruler | alert pipeline | `Watchdog`+ absent-alert detekce; on-call ví z dead-man switch, ne z náhodného ticha |
| C4 | Utni NetworkPolicy na jednom namespace | mTLS/izolace drift | Kyverno/NetPol gate zahlásí drift; služby v namespace fail-closed |
| C5 | Vraž outbox dispatcher (dispatch-enabled=false simulace) | event integrita | `WorkflowLiveness`-style alert na nedisponující outbox; žádný tichý `attempt_count=0` stav |
| C6 | Simuluj pomalý audit-anchor backend | evidentní integrita | audit píše dál, anchor lag roste → alert; NIKDY se neskipne anchoring potichu |

## Postup

1. **T-7 dní:** vyber 3 scénáře, zapiš očekávané chování (tabulka výše) do
   drill záznamu — *před* injekcí. Hodnotí se předpověď, ne improvizace.
2. **Game day:** injekce přes existující chaos nástroje DR drillu
   (ADR-0242). Scribe zapisuje čas injekce, čas prvního alertu, čas
   lidské reakce — stejná disciplína jako runbook 0018.
3. **Měření:** pro každý scénář: (a) detekováno alertem? čas; (b) fail-closed?
   důkaz z API odpovědí/logů; (c) recovery čistá? (žádný ruční zásah, který
   by nešel zdokumentovat jako runbook krok).
4. **Fail-open finding = critical.** Služba, která při mrtvém IdP/PDP
   odpovídá 200, má issue s `security` + `fail-open` a je blokována z release
   do opravy + gate (typicky contract/IT test na absent-PDP chování).
5. **Report** do `docs/security/chaos/YYYY-Hn.md` vč. porovnání detekčních
   časů s minulým drillem. Časy se mají zlepšovat; zhoršení je finding.

## Vazby

- ADR-0242 — společná chaos infrastruktura a kalendář (security drill běží
  vždy v týdnu po DR drillu, aby se nespojovaly vlivy).
- Runbook 0021 — purple-team drill; chaos drill testuje *degradaci*,
  purple-team testuje *detekci útoku*. Jeden rok: Q1 purple, Q2 chaos,
  Q3 purple, Q4 chaos.
- Honeytoken/HoneyEndpointHit (`loki-rules-security-signals.yaml`) je
  known-positive kontrola, že alert pipeline během drillu žije — pokud ani
  honeytoken zásah nez alerting, drill se STOPUJE a řeší se pipeline, ne scénáře.
