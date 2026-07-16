# 03 — API

Contract: [`openapi.yaml`](../openapi.yaml), `info.version: 1.0.0`. Two independent version axes (ADR-0048): the API-contract version here is **not** the release version in `version.txt`.

Base path `/api/v1/vop`. Port 8149.

## `POST /api/v1/vop/verify`

```http
POST /api/v1/vop/verify
Authorization: Bearer <jwt>
Content-Type: application/json

{ "creditorIban": "CZ6508000000192000145399", "creditorName": "Jiří Raška" }
```

```json
{ "status": "match", "verifiedAt": "2026-07-16T10:00:00Z" }
```

**Why POST for a read?** The IBAN and payee name are personal data. They must never reach a URL, an access log, or a referer header. This is the only reason — the endpoint mutates nothing but the evidence log, and needs no `Idempotency-Key`.

### Request

| Field | Type | Rules |
|---|---|---|
| `creditorIban` | string | required, ≤34 chars, must pass IBAN check digits (`Iban.of`) |
| `creditorName` | string | required, ≤140 chars (the SEPA name length) |

Validation is explicit (`VerifyPayeeRequest.validated()`), not bean-validation — the fleet carries no `hibernate-validator`. Failures surface as `IllegalArgumentException` → **400** via libs-runtime's shared mapper. Note there is deliberately **no service-local `ExceptionMapper<IllegalArgumentException>`**: `openbank-libs-runtime` owns that type, and a second mapper for the same type is selected non-deterministically per request (issue #526, enforced by `check-exception-mapper-collision.sh`).

### Response

| Field | When | Notes |
|---|---|---|
| `status` | always | `match` \| `close_match` \| `no_match` \| `no_data` — the EPC scheme's `MTCH`/`CMTC`/`NMTC`/`NOAP`, and the wire values the admin UI's `VopStatus` union already expected |
| `matchedName` | **`close_match` only** | The real account-holder name. **Never present on `no_match`.** |
| `reason` | `no_data` only | `no_scheme_connectivity` \| `account_not_found` \| `name_not_available` \| `lookup_unavailable` |
| `verifiedAt` | always | |

## The four outcomes — and what each discloses

This table *is* the security model. See [the decision diagram](../diagrams/03-outcome-decision.mmd).

| Outcome | Meaning | Discloses a name? | Payer should |
|---|---|---|---|
| `match` | The same name. | **No** — the payer supplied it. | Proceed. |
| `close_match` | A near-miss the payer can plausibly correct: reordered tokens, an initial, a legal-form suffix, or a one-character typo. | **Yes** — but only to someone who already nearly knew it, which is the case the scheme requires us to let them correct. | Check the returned name, then decide. |
| `no_match` | Both names known, and they are not the same name. | **Never.** A wrong guess teaches an attacker nothing but that they were wrong. | Be warned. May still proceed (Art. 5c: warn, don't refuse). |
| `no_data` | No answer available. **Never treat as a match.** | No. | Be warned that we could not check. May proceed. |

### Matching rules (`VopNameMatchPolicy`)

Both names are normalised through `MatchKey.normalize` (NFD, strip diacritics, lowercase, collapse whitespace) — the fleet's single normaliser, not a third copy of it.

| Input | Result | Why |
|---|---|---|
| `Jiri Raska` vs `Jiří Raška` | `match` | Diacritics are presentation. A payer without a Czech keyboard is not making a mistake. |
| `Acme s.r.o.` vs `Acme` | `match` | A **trailing** legal form is presentation, not identity. |
| `SRO Praha` vs `Praha` | `no_match` | Only a *trailing* legal form is stripped — a company named "SRO Praha" keeps its leading token. |
| `Raška Jiří` vs `Jiří Raška` | `close_match` | Our field order is not every PSP's. |
| `J. Raška` vs `Jiří Raška` | `close_match` | One side abbreviates the given name. |
| `J. K.` vs `Jan Kovář` | `no_match` | Initials alone are far too weak to call a near-miss of a payee name. |
| `Jiří Raška` vs `Jiří Jan Raška` | `close_match` | A middle name one side omits; two tokens still corroborate. |
| `Acme Praha` vs `Praha` | `no_match` | The dropped-token rule needs ≥2 tokens left. With one, it degenerates into "shares any token" — two different payees. |
| `Jiri Raskb` vs `Jiří Raška` | `close_match` | One character — a typo, not a different name. |

`openbank.vop.max-edit-distance` (default 1) tunes the typo budget. **These thresholds are judgement calls with no production data behind them** — tune from outcome metrics, not taste. They are constructor parameters precisely so they can move without touching the algorithm.

## Status codes

| Code | When |
|---|---|
| 200 | A verdict — **including `no_data`**. An unknown IBAN is a 200, never a 404: a 404 would say "not our account", which is an enumeration primitive. |
| 400 | Malformed request or an IBAN failing its check digits. The message is echoed because it is a statement about the caller's own input. |
| 401 / 403 | Unauthenticated / denied by OPA (`vop.verify`). |
| 429 | Rate limit (60/min per requester), or the limit store is unreachable — it **fails closed**. Not a payment failure: render `no_data`. |

## Authorization

`@RolesAllowed("ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_PAYMENTS")` + OPA `@Authorize(action = "vop.verify")`. Both are required — coarse RBAC then the fine-grained PEP.

The action prefix is `vop`, matching the module name `openbank-vop-service`, so `money_path_scopes` in the base `rest.rego` derives `"vop"` and **actually matches**. Contrast `openbank-sepa-instant`, whose real prefix is `sctInstPayment` while the derived scope is `sepa-instant` — a mismatch that means `four_eyes_required` silently never fires for that rail (issue #395). Naming this `vop.create` would reintroduce that class of bug.

M2M callers are admitted by the Keycloak `service-account-*` `preferred_username` convention — **not** `principal.type == "SERVICE"`, which is unreachable dead code (`rules.yaml: authz_policy.principal_type_service_unreachable`). The rule is scoped to `vop.verify` alone, never a `vop.` family prefix, so a future write action is not silently pre-authorised.
