# Runbook 0021: Purple-team cvičení — simulovaný incident přes reálnou alert pipeline

> ADR-0279 workstream 2, bod #22. Rozšiřuje runbook 0018 (CRA Art. 14
> tabletop) o **technickou polovinu**: tabletop cvičí lidi a procedury,
> purple-team cvičí detekci. Kadence: 2× ročně (střídá se s runbookem 0020).
> Tvrdé pravidlo: **útok musí projít reálnou alert pipeline end-to-end**
> (Loki/Prometheus rule pack → Alertmanager → on-call), jinak se nic neměří.

## Promotion criterion — known-positive

Před každým cvičením se ověří, že pipeline žije, **živou kontrolou, ne
předpokladem**:

1. Na sandboxu navštiv honeytoken endpoint (viz `HoneyEndpointHit` v
   `openbank-infra/gitops/components/observability/loki-rules-security-signals.yaml`)
   z testovací identity.
2. Alert MUSÍ dorazit na on-call kanál do 5 minut.
3. Nedorazí-li: cvičení se ruší, zakládá se incident na alert pipeline samotnou
   (pipeline mrtvá = banka slepá). Teprve po opravě se cvičení opakuje.

Teprve potom se spouští scénář. Known-positive se zapisuje do reportu
s časem — je to důkaz, že „nedetekováno" znamená opravdu nedetekováno.

## Scénáře (red hraje, blue sleduje; facilitator zná obě strany)

| # | Simulovaný útok | Detekce, která má zareagovat |
|---|---|---|
| P1 | Credential stuffing na login (burst z jedné /24) | Loki `AuthFailureBurst` / WAF signál |
| P2 | Enumerace party/IBAN přes IDOR probing | `AuthzDenySpike`, příp. 404-pattern rule |
| P3 | Zásah do honeytoken endpointu z běžné service identity | `HoneyEndpointHit` |
| P4 | Podezřelý proces v podu (simulace přes test container) | Falco → `prometheus-rules-security-signals.yaml` |
| P5 | Exfiltrace pattern: nezvyklý egress objem z reporting služby | Prometheus egress/bytes anomálie + NetPol audit |
| P6 | Replay idempotency-key / outbox manipulace na sandboxu | audit-anchor verifier (`verify-audit-anchors.py`) + outbox liveness |

Scénář se nikdy nehlásí blue týmu předem. Facilitátor má STOP slovo
(„kinetická událost na sandboxu" končí okamžitě, ne dotažením scénáře).

## Postup

1. **T-7 dní:** red tým vybere 2–3 scénáře, připraví payloady mimo sdílené
   kanály. Blue tým ví jen „v tomto týdnu bude okno".
2. **Provedení:** red útočí na sandbox; scribe (neutrální) zapisuje čas
   každé red akce. Blue reaguje dle běžného on-call — nikdo mu nepomáhá.
3. **Skóre na scénář:** čas injekce → čas alertu → čas lidského uznání
   (ack) → čas správné klasifikace. Správná klasifikace = blue pozná, *co*
   se děje, ne jen že „něco křičí".
4. **Debrief do 48 h:** pro každý scénář jeden z výstupů:
   (a) detekce fungovala → zapsat čas do baseline;
   (b) detekce pozdě → tuning PR na rule pack (window/threshold);
   (c) detekce chyběla → nový rule + gate `check-alert-metric-emitted.py`
       zajistí, že má emitter; finding nese label `detection-gap`.
5. **Report** do `docs/security/purple-team/YYYY-Hn.md` — vstup pro
   ADR-0279 review a pro CRA evidenci průběžného testování.

## Vazby

- Runbook 0018 — tabletop (procedura/lidé); tento runbook je strojová polovina.
- Runbook 0020 — chaos drill (degradace kontrol); střídání Q: purple/chaos/purple/chaos.
- Detekční skóre z runbooku 0019 (pentest) a z tohoto cvičení se sjednocuje
  do security excellence dashboardu v admin-ui (bod ADR-0279 WS4).
