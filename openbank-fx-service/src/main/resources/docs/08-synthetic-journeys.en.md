# Synthetic journeys — fx-rates-read

The `fx-rates-read` synthetic journey is the fx-service leg of the fleet's
synthetic-monitoring programme (runbook 0009). It answers one question
continuously, from an in-cluster CronJob: *can a scoped synthetic identity
actually read FX rates the way a customer-facing integration would?*

## Scope-derived identity (the load-bearing part)

The journey uses the dedicated client-credentials identity
`openbank-synthetic-fx-read`, with no realm roles and the `fx:read` client
scope. The token's `sub` and `azp` identify the service-account client;
they are not derived from the scope. `FxScopeIdentityAugmentor` maps the
verified token's scope claim to the read-only `FX_SCOPE_READ` role.

The CronJob reads the rate sheet through the in-cluster Service. Its
successful run can establish token issuance, scope mapping, authorization
and the rate projection for the deployed configuration. The HTTP integration
test separately exercises the real augmentor and RBAC with test OIDC claims;
its default advisory OPA configuration is not proof of enforced HTTP PDP
decisions. Rego tests cover the policy's read allow and write deny rules.
A new service image, policy bundle, realm configuration and provisioned
credential must be deployed before runtime journey success can be claimed.

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
