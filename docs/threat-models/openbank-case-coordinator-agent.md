# Threat model — openbank-case-coordinator-agent collaboration

Status: proposed for ADR-0271 / issue #6426. No authorization grant is live from this
document alone.

## Scope and assets

The scoped change lets one AI principal contribute masked incident evidence to an
existing Temporal `incident-response` workflow in SHADOW mode. Assets are the integrity
of case history, contribution budget, synthesis inputs, policy/audit evidence and the
exactly-one terminal outcome invariant. Business records and money movement are out of
scope and remain unreachable.

## Trust boundaries

- OIDC bearer → case-coordinator REST ingress
- proved caller identity → asserted agent identity
- REST PEP → localhost OPA PDP
- case-coordinator → Temporal signal transport
- deterministic workflow history → persistence activity/read model
- case outbox → proposal topic (disabled for SHADOW outcomes)

## Threats and controls

| Threat | Control | Required evidence |
|---|---|---|
| Caller impersonates `rca-investigator` | Bind the proved principal to the asserted id before policy evaluation; never authorize a body claim alone | denial integration test with another principal |
| Charter drift grants more than intended | OPA requires both charter capability and rules matrix match | OPA matrix tests and generated-data differential proof |
| Pilot reaches money/AML/fraud cases | Server reads case class from persistence; matrix admits only `incident-response` | negative tests for every other class |
| HITL proposal escapes shadow | Matrix requires `SHADOW`; startup preflight validates rollout id; shadow outcome never enters proposal topic | outbox absence integration test |
| Signal replay duplicates influence | Typed signal id is idempotent in deterministic workflow state | Temporal replay and duplicate-signal tests |
| Signal storm exhausts budget | Eight-signal participant quota plus existing per-case contribution/token ceilings | storm test and quota metric |
| Authorized invocation is mistaken for consumption | Separate authorization, invocation, consumption and persistence evidence stages | correlated evidence test |
| Rejected payload leaks through audit/logs | Audit only ids, capability, class/mode, decision and result; no summary/evidence content | log/audit payload test |
| OPA outage silently permits | PDP and local gate fail closed for collaboration | unavailable-PDP denial test |
| Participant synthesizes or mutates | No `case.synthesize`, `case.preempt`, money, write or operator capability in either matrix | charter/schema and OPA deny tests |

## Rollout and rollback

Rollout requires an explicit authorization review for the exact principal/capabilities/
class/mode tuple, green policy and workflow tests, and a unique shadow rollout id. Rollback
sets the matrix kill switch false or removes the matrix entry; OPA then denies without a
service redeploy once the bundle is rolled. Existing case history remains append-only.

## Residual risk

An allowed RCA contribution may be factually wrong and influence the coordinator's
shadow synthesis. The pilot measures contested rate and never reaches business state or a
human approval queue. Promotion requires review of the seven-day evaluation report.
