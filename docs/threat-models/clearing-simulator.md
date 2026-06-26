# Threat model — Clearing Simulator (ADR-0104)

**Surface:** `openbank-clearing-simulator` — a NON-PRODUCTION scheme/clearing counterparty. Receives
a real ISO 20022 `pacs.008`, validates it against the XSD, decides settle/reject deterministically,
and returns a `pacs.002` status report plus, on settlement, a `camt.054` credit notification.
**Posture:** stateless; no datastore; no posting authority; contacts no real payment network; moves
no money. It stands in for the real CSM behind the rail's `SchemeGatewayPort` (the licence swap-point).

## Assets
- The integrity of the payment-rail test/sandbox: the simulator must behave like a faithful CSM so
  rail behaviour proven against it transfers to the real gateway.
- The **boundary guarantee**: nothing the simulator emits may be mistaken for real-network settlement,
  and the simulator must **never** be deployed to, or reachable from, a production money path.
- The cluster (the simulator must never be a pivot into internal services).

## Trust boundaries
- Payment rail (in-cluster) → simulator pod (service-to-service, OIDC bearer, `ROLE_SERVICE`).
- The simulator has **no** outbound trust relationship: it calls no ledger, no balance, no network.
- Non-production environments only (sandbox); excluded from any production overlay.

## Threats & mitigations (STRIDE-ish)
| Threat | Mitigation |
| --- | --- |
| **Mistaken for a real rail** (the headline risk) | Name, OpenAPI, logs and ADR-0104 all state "non-production simulator, no money moves". It holds no posting authority and has no network egress to any scheme. Its only output is an ISO 20022 status/notification message — never a ledger posting. |
| **Deployed to production** | Released component but gated to sandbox overlays only; no production GitOps app references it. Egress NetworkPolicy denies all but DNS (no path to a real CSM even if misconfigured). *Follow-up: add an explicit admission/policy guard that blocks the image from any production namespace.* |
| **Spoofing the caller** | Endpoints require a valid OIDC bearer with `ROLE_SERVICE`/`ROLE_OPERATOR`/`ROLE_ADMIN`; unauthenticated calls are 401. |
| **Tampering with the verdict** | The decision is a pure, deterministic function of the message (ADR-0100) — no hidden state to tamper with; reproducible and auditable. |
| **Injection / XXE via the inbound XML** | The `pacs.008` reader and all builders use an XXE-hardened parser (DTD disallowed, external entities/schema access denied). Malformed input is rejected with `RJCT`/`FF01`, never executed. |
| **Information disclosure** | Stateless: persists nothing, logs no PAN/secret. Inbound messages carry the same debtor/creditor data the rail already holds; sandbox uses synthetic data only. |
| **DoS** | Stateless and cheap (in-memory XML build + XSD validate); rate-limit at the platform; horizontal scaling is trivial. No amplification sink (no DB, no downstream calls). |
| **Elevation / cluster pivot** | Restricted PSS, non-root, read-only rootfs, all caps dropped; egress NetworkPolicy = DNS only; dedicated namespace. |

## Residual risk / follow-ups
- **Production-isolation guard** (admission policy refusing the image outside sandbox) is the key
  remaining control — until it lands, isolation rests on GitOps overlay discipline + egress policy.
- When D3 wires the rail's `SchemeGatewayPort` to this simulator, the **rail** side (which holds
  posting authority via the Temporal settlement workflow) carries its own money-path threat model;
  this document covers the counterparty stub only.
- Swapping the simulator for a real test-sandbox or licensed gateway is a **new** trust boundary
  (real external network) and requires a fresh threat model before it ships.
