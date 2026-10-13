<!-- SPDX-License-Identifier: AGPL-3.0-only -->
# Threat model — AP2 mandate verifier authentication

**Status:** Draft for #12511. This describes the candidate source change, not a deployed control.
**Scope:** `POST /api/v2/ap2/verify` and its authentication and authorization path. ADR-0193 keeps the
verifier evidence-only: it does not execute a payment, choose an SCA exemption, or move funds.

## Assets and trust boundaries

- The caller supplies a signed mandate and proposed payment. Both are untrusted until the
  verifier checks signature and constraints; a valid response is authorization evidence only.
- The identity provider signs an OAuth access token. Quarkus OIDC validates its issuer, signature,
  expiry, and `openbank-ap2-service` audience. The endpoint accepts its `sub` only in the
  `agent:<id>` namespace. A client certificate authenticates transport, not the agent identity.
- The shared ADR-0034 PDP independently checks `verify.mandate` against a charter for that exact
  agent. A missing charter, denied decision, or unavailable PDP stops verification.
- The configured mandate issuer trust list is separate from the caller's OAuth identity. A
  valid caller token cannot make an untrusted mandate issuer valid.

## Threats and controls

| Threat | Control in this candidate | Remaining proof |
|---|---|---|
| Spoofed agent through `X-Agent-Id` or a client certificate | The endpoint ignores the header and derives the PDP principal only from validated token `sub`; the route requires bearer authentication. | Hosted production-profile CI and rollout checks. |
| Token for another service or non-agent subject | OIDC audience check rejects the former; `agent:<id>` check rejects the latter before PDP. | Real issuer integration and token onboarding. |
| Former anonymous grant used with a real token whose subject is `agent:ap2-anonymous` | Retire that charter from the authoritative registry and generated OPA bundles. | Staged bundle rollout and health checks. |
| Agent without an approved `verify.mandate` charter | Shared PDP defaults to deny. No AP2 demo or production agent is provisioned in this change. | Separate reviewed onboarding with agent-specific charter. |
| PDP outage or invalid mandate | PDP failure returns a closed error; signature and constraints remain separate fail-closed checks. | Runtime alerting and end-to-end deployment evidence. |

## Rollout and rollback

Retiring the shared charter regenerates the fleet OPA projection and 54 service bundles, with
pod-roll checksums. Deploy this through normal reviewed GitOps with staged health checks. The
candidate tests prove local bearer handling and evaluate the generated AP2 OPA bundle; they do
not prove a live rollout. A rollback must restore the prior application and policy together;
restoring the old anonymous grant by itself would reopen the spoofable caller path.

The AP2 sandbox demo stays closed until a genuine agent is onboarded with a validated token and
an independently reviewed charter. No demonstration client or credential is provisioned here.
The former unversioned `/ap2/verify` route is removed in this pre-onboarding cutover; there is no
caller to migrate and no compatibility alias that could preserve the spoofable phase-1 behavior.
