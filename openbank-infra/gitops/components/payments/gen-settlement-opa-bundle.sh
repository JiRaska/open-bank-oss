#!/usr/bin/env bash
set -euo pipefail
REPO="$(git rev-parse --show-toplevel)"

REST_REGO=$REPO/openbank-libs/governance/policies/rest.rego
AGENTS_REGO=$REPO/openbank-infra/opa/policies/agents.rego
AGENTS_YAML=$REPO/openbank-libs/governance/agents-opa-data.yaml
RULES_YAML=$REPO/openbank-libs/governance/rules-opa-data.yaml
MANIFEST=$REPO/openbank-infra/opa/bundle.manifest

# Settlement REST extension — interbank settlement origination allow reasons
# (ADR-0034 Phase 5, issue #266)
SETTLEMENT_REST_EXT=$(cat << 'REGO'
# SPDX-License-Identifier: Apache-2.0
# Settlement-service REST extension (ADR-0034 Phase 5, issue #266).
# Extends openbank.rest with settlement-domain allow reasons.
# Mounted alongside rest.rego in the same OPA bundle — OPA merges same-package rules.
#
# Action gated (SettlementResource):
#   settlement.create — originate an interbank settlement and start its Temporal
#                        workflow (POST /api/v1/settlements). debit -> credit ->
#                        ledger-booking runs entirely inside the Temporal worker
#                        (activities, not REST) once originated, so this is the
#                        ONLY REST action this service exposes.
#
# openbank-settlement-service -> money_path_services derived scope is "settlement"
# (strip openbank- / -service) which already equals the real @Authorize prefix — no
# money_path_action_prefixes override entry needed (unlike sepa-payment/sepa-instant/
# domestic-payment/clearing/sca, issue #395/#396).

package openbank.rest

import rego.v1

# Operators and admins may originate a settlement — the ops/reconciliation console path
# (investigate a stuck payment, manually trigger a compensating settlement). This is the
# PRIMARY path: settlement-service has no genuine end-customer self-service surface and
# no verified in-repo M2M caller (see below), so almost every real call here is a human
# operator acting through admin-ui's BFF, which forwards the operator's OWN bearer token
# (not a service-to-service credential).
#
# settlement.create is also listed in rules.yaml four_eyes.actions (#10041): OPA flags it
# four_eyes_required and, where authz.four-eyes.enforce=true, the maker's request is parked with
# a durable approval bound to the exact instruction (endpoint + arguments, #11675).
#
# The `service-account-` exclusion makes "human operator" true: Keycloak client_credentials
# tokens are classified HUMAN and the realm M2M clients carry ROLE_OPERATOR in at least one
# realm (#3734), while this action has no verified M2M caller (see below).
allowed_reasons contains "operator-settlement-write" if {
	input.principal.type == "HUMAN"
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN"}
	role in input.principal.roles
	not principal_is_machine
	input.action == "settlement.create"
}

# Checker side of the settlement four-eyes flow (ApprovalResource, PATCH
# /api/v1/settlements/approvals/{id}). Deciding an approval is as sensitive as originating the
# settlement it gates, so it is the identical human-operator grant. Self-approval is refused by
# the approval store, not here. settlement.approval.read (the queue and one record) is reached
# by base operator-read-any for human operators.
allowed_reasons contains "operator-settlement-approval-decide" if {
	input.principal.type == "HUMAN"
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN"}
	role in input.principal.roles
	not principal_is_machine
	input.action == "settlement.approval.decide"
}

# The operator-approval queue exposes the maker, the action and the full bound instruction of
# every pending settlement. Base operator-read-any is role-only, and both realm M2M clients
# (service-account-openbank-services, service-account-openbank-edge) are classified HUMAN with
# ROLE_OPERATOR in at least one realm, so without this veto they could read it. No M2M consumer
# exists (admin-ui forwards the operator's own token). Identity, never principal.type, decides.
prohibited if {
	input.principal.service_account == true
	input.action in {"settlement.approval.read", "settlement.approval.decide"}
}

# NO service-to-service (SERVICE/ROLE_SERVICE) allow rule exists here on purpose.
# SettlementResource's own @RolesAllowed(SERVICE, OPERATOR, ADMIN) admits a SERVICE
# principal at the coarse RBAC layer, but a fleet-wide audit of every service's
# rest-client config, gitops NetworkPolicy ingress-allow-list, and application.yaml
# (issue #266 settlement rollout) found NO in-repo caller that actually invokes
# POST /api/v1/settlements as a service-to-service action: no service declares a
# settlement-api rest-client, and settlement-service-ingress-allow-list only admits
# admin-ui (human operator token, not M2M) plus same-namespace pods. The Temporal
# workflow that runs the actual debit/credit/ledger-booking saga is internal
# orchestration inside this service's own worker — it never calls back in over REST,
# so it needs no allow rule here at all.
# A blanket SERVICE allow without verified caller evidence is exactly the anti-pattern
# rules.yaml / ADR-0034 forbids on a money-path rail; if a real M2M caller for
# settlement.create is ever added and verified, add it here narrowly the way
# service-domestic-payment-m2m / service-sca-m2m do it — do not widen this comment
# into a rule speculatively.
REGO
)

CHECKSUM=$(printf '%s\n' \
    "$(cat "$REST_REGO")" \
    "$(echo "$SETTLEMENT_REST_EXT")" \
    "$(cat "$AGENTS_REGO")" \
    "$(cat "$AGENTS_YAML")" \
    "$(cat "$RULES_YAML")" \
    "$(cat "$MANIFEST")" | \
  (command -v sha256sum >/dev/null 2>&1 && sha256sum || shasum -a 256) | cut -c1-16)

OUT=$REPO/openbank-infra/gitops/components/payments/settlement-opa-bundle.yaml

{
  echo "# GENERATED by gen-settlement-opa-bundle.sh — do not hand-edit."
  echo "# Source: rest.rego + settlement_rest_ext.rego + agents.rego + agents-opa-data.yaml + rules-opa-data.yaml + bundle.manifest"
  echo "apiVersion: v1"
  echo "kind: ConfigMap"
  echo "metadata:"
  echo "  name: settlement-opa-bundle"
  echo "  namespace: payments"
  echo "  labels:"
  echo "    app.kubernetes.io/name: settlement-service"
  echo "    app.kubernetes.io/part-of: payments"
  echo "  annotations:"
  echo "    openbank.tech/policy-checksum: \"$CHECKSUM\""
  echo "data:"
  echo "  rest.rego: |"
  sed 's/^/    /' "$REST_REGO" | sed 's/[[:space:]]*$//'
  echo "  settlement_rest_ext.rego: |"
  echo "$SETTLEMENT_REST_EXT" | sed 's/^/    /' | sed 's/[[:space:]]*$//'
  echo "  agents.rego: |"
  sed 's/^/    /' "$AGENTS_REGO" | sed 's/[[:space:]]*$//'
  echo "  agents-data.yaml: |"
  sed 's/^/    /' "$AGENTS_YAML" | sed 's/[[:space:]]*$//'
  echo "  rules-data.yaml: |"
  sed 's/^/    /' "$RULES_YAML" | sed 's/[[:space:]]*$//'
  echo "  manifest.json: |"
  sed 's/^/    /' "$MANIFEST" | sed 's/[[:space:]]*$//'
  printf '\n'
} > "$OUT"

echo "wrote $OUT (checksum $CHECKSUM)"

# Sync the Rollout pod-roll annotation so a policy change always triggers a rollout
# (subPath mounts do NOT hot-reload — same pattern as gen-domestic-payment-opa-bundle.sh).
# payments-services.yaml holds SEVERAL payment-rail Rollouts in one file; the sed is
# anchored on the trailing "# settlement-opa-bundle" marker so it can never stomp
# another rail's checksum.
ROLLOUT=$REPO/openbank-infra/gitops/components/payments/payments-services.yaml
if [ -f "$ROLLOUT" ]; then
  sed -i.bak "s|openbank.tech/policy-checksum: \"[^\"]*\" # settlement-opa-bundle|openbank.tech/policy-checksum: \"$CHECKSUM\" # settlement-opa-bundle|" "$ROLLOUT"
  rm -f "${ROLLOUT}.bak"
  echo "patched $ROLLOUT settlement annotation → $CHECKSUM"
fi
