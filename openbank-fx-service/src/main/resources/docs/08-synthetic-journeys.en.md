# Synthetic journeys — fx-rates-read

The `fx-rates-read` synthetic journey is the fx-service leg of the fleet's
synthetic-monitoring programme (runbook 0009). It answers one question
continuously, from outside the cluster: *can a scoped synthetic identity
actually read FX rates the way a customer-facing integration would?*

## Scope-derived identity (the load-bearing part)

The journey does not mint a bespoke synthetic user. It derives its identity
from the journey scope itself: `journey.fxrates.read` is the only granted
scope, and the token's `sub`/`azp` are derived from that scope. The
`FxScopeIdentityAugmentor` (infrastructure/security) maps the scope to a
read-only principal at the security layer, so the journey proves the whole
chain — token issue, scope mapping, authorization decision, repository read —
rather than a test-only back door.

Consequences:

- A scope regression (grant dropped, mapper renamed) turns the journey red,
  not a unit test that mocked the mapper.
- The journey carries no write capability by construction; a defect that
  turned it into a write client would fail closed at the authorization layer.

## What it does not prove

Settlement, conversion, and any write path are out of scope by design. Those
are covered by `FxTraceContractIT` (trace contract over the conversion money
path) and the contract/IT suite; this journey is the read-side availability
and authorization probe.

## Vault seed

The synthetic client credential lives in Vault KV
`openbank/keycloak/synthetic-fx-read` and must match the realm client
`openbank/keycloak/realm/openbank-services.json` synthetic client definition.
Until the seed exists in an environment the journey runs red there
deliberately — an unseeded environment must not silently read as healthy.
