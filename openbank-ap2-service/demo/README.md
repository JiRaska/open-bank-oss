<!-- SPDX-License-Identifier: AGPL-3.0-only -->
# AP2 mandate verification — sandbox demo

A runnable, end-to-end demonstration of the bank-side **AP2** (Agent Payments Protocol)
mandate verifier (ADR-0193, issue #1923). An agent presents a **signed Payment Mandate**;
`openbank-ap2-service` verifies its signature chain **and** its constraints against a
presented payment; a **human-in-the-loop (HITL)** threshold decides auto-eligible vs
step-up. **No funds move** — the verifier returns *authorization evidence* only (ADR-0193):
a valid verdict is an input to the SCA/payment decision, never a payment.

> Complements the MCP server (ADR-0181): **MCP = the agent talks to the bank; AP2 = the
> agent pays *through* the bank.**

## What it shows

| # | Scenario | Expected |
|---|----------|----------|
| 1 | Valid mandate, in-bounds payment (250.00 CZK) | `valid=true`, evidence, **auto-eligible** |
| 2 | Valid mandate, large payment (750.00 CZK, within cap) | `valid=true`, **HITL step-up** (over threshold) |
| 3 | Payment over the mandate cap (1500.00 CZK) | `valid=false` — `amount exceeds cap` |
| 4 | Tampered signature | `valid=false` — `signature invalid` |

Both verification stages are exercised: the **Ed25519 signature chain** (JCA, the primitive
`openbank-sca-service` already trusts) and the **pure-domain constraint check** (payee,
amount cap, currency, expiry).

## The demo key (test only, no secret committed)

The demo issuer key is **derived deterministically** from a fixed, human-readable 32-byte
seed (`DEMO_SEED = openbank-ap2-demo-issuer-seed-01`) inside the script — there is **no
private-key file** in the repo, and the seed is a label, not a secret. Because it is
deterministic the public key is fixed, so it can be trust-listed once. It is **not** a
production key and authorizes nothing real.

The sandbox `ap2-service` trust-lists its public key so the demo's signatures verify — on the
`ap2-service` Deployment (`openbank-infra/gitops/components/ap2/ap2-service.yaml`):

```yaml
- name: AP2_TRUST_LIST
  value: "demo-issuer=MCowBQYDK2VwAyEApZUA0ryfjfNx1Xy49S8m5v/Ckii44uzt2r+Z5dOtzCk="
```

Print that value at any time with:

```bash
python3 ap2-mandate-demo.py --print-trust
```

## Run it

```bash
# 1) port-forward the live sandbox service (separate shell):
kubectl port-forward -n platform svc/ap2-service 8151:8151

# 2) after a real agent is onboarded with a charter allowing verify.mandate, provide its
#    audience-bound OAuth bearer token without writing it to a file:
pip install cryptography            # if needed
AP2_BEARER_TOKEN="$AP2_BEARER_TOKEN" AP2_URL=http://localhost:8151 python3 ap2-mandate-demo.py

# HITL threshold is configurable (minor units):
HITL_THRESHOLD_MINOR=50000 AP2_BEARER_TOKEN="$AP2_BEARER_TOKEN" AP2_URL=http://localhost:8151 python3 ap2-mandate-demo.py
```

## Agent onboarding required

The phase-1 `ap2-anonymous` charter and `X-Agent-Id` acting-identity header are retired.
There is currently no onboarded AP2 demo agent. The verifier requires an OAuth bearer token
whose validated subject is `agent:<id>` and audience is `openbank-ap2-service`; the shared PDP
also requires a separate charter for that exact agent with `verify.mandate` capability. The
script exits before making requests when no token is supplied. An authenticated caller without
a matching charter is denied; unavailable PDP also fails closed.
