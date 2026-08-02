#!/usr/bin/env bash
set -euo pipefail
REPO="$(git rev-parse --show-toplevel)"

REST_REGO=$REPO/openbank-libs/governance/policies/rest.rego
AGENTS_REGO=$REPO/openbank-infra/opa/policies/agents.rego
AGENTS_YAML=$REPO/openbank-libs/governance/agents.yaml
RULES_YAML=$REPO/openbank-libs/governance/rules.yaml
MANIFEST=$REPO/openbank-infra/opa/bundle.manifest

# Lending REST extension — loan lifecycle allow reasons (ADR-0034 Phase 5, issue #266)
LENDING_REST_EXT=$(cat << 'REGO'
# SPDX-License-Identifier: Apache-2.0
# Lending-service REST extension (ADR-0034 Phase 5, issue #266).
# Extends openbank.rest with lending-domain allow reasons.
# Mounted alongside rest.rego in the same OPA bundle — OPA merges same-package rules.
#
# Actions gated (LendingResource):
#   lending.create              — submit a loan application (maker)
#   lending.approve             — approve/reject an application (checker; four-eyes in the handler)
#   lending.intake              — customer self-service application via customer-edge (ADR-0211)
#   lending.read                — get application/loan/schedule/collateral/IFRS-9 snapshot (#id)
#   lending.list                — list a party's applications/loans
#   lending.disburse            — disburse an approved application (books the loan)
#   lending.repay                — record a repayment against an installment (#id)
#   lending.writeoff             — write off an uncollectible loan (#id)
#   lending.collateralRegister  — register collateral against a loan (maker; four-eyes in the
#                                 handler + four_eyes.verbs, issue #621; PENDING until decided)
#   lending.collateralDecide    — approve/reject a pending collateral registration (checker; must
#                                 differ from the registrant)
#
# Base rest.rego already grants: operator-read-any (OPERATOR/ADMIN on *.read/*.list),
# compliance-read-any (*.read), party-self-service (reads where the JWT sub equals the
# path id — inert here: lending resources are keyed by loan/application id, not partyId),
# and operator-on-own-tenant (tenant-matched writes).
#
# The desk rules below mirror the @RolesAllowed matrix in LendingResource exactly
# (action-level union), so flipping AUTHZ_ENFORCE=true is behaviour-preserving for the
# roles RBAC already admits — OPA never grants what @RolesAllowed rejects (RBAC stays
# the outer gate) and never denies a desk flow RBAC intends. The four-eyes maker-checker
# on lending.approve/disburse/collateralDecide is enforced in the application service
# from the JWT subject, not in rego (same stance as pid's identity.case.decide).

package openbank.rest

import rego.v1

# Ops-console path: operators and admins may perform ANY lending lifecycle operation
# (resolve a stuck application, service-desk correction — the pid/sca/consent pattern).
allowed_reasons contains "operator-lending-write" if {
	input.principal.type == "HUMAN"
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN"}
	role in input.principal.roles
	startswith(input.action, "lending.")
}

# Lending officers originate and service loans: apply (maker), disburse, record
# repayments, register collateral (maker), read/list the book. NOT approve or
# collateralDecide (the checker for both is credit-risk/admin per @RolesAllowed) and
# NOT writeoff.
allowed_reasons contains "lending-officer-desk" if {
	input.principal.type == "HUMAN"
	"ROLE_LENDING_OFFICER" in input.principal.roles
	input.action in {
		"lending.create",
		"lending.read",
		"lending.list",
		"lending.repay",
		"lending.disburse",
		"lending.collateralRegister",
		"lending.approval.read",
	}
}

# Credit-risk decides applications and collateral registrations (the checker leg of
# each four-eyes control; maker != checker is enforced in the handler) and writes off
# uncollectible exposure. The create/read/list/repay/collateralRegister grants mirror
# the class-level @RolesAllowed. NOT disburse (lending-officer/admin).
allowed_reasons contains "credit-risk-desk" if {
	input.principal.type == "HUMAN"
	"ROLE_CREDIT_RISK" in input.principal.roles
	input.action in {
		"lending.create",
		"lending.read",
		"lending.list",
		"lending.repay",
		"lending.approve",
		"lending.writeoff",
		"lending.collateralRegister",
		"lending.collateralDecide",
		"lending.approval.read",
		"lending.approval.decide",
	}
}

# Compliance may write off (regulatory workout) and, per the class-level @RolesAllowed,
# reaches the same origination/servicing surface as the desk; reads also ride on the
# base compliance-read-any. NOT approve, NOT disburse, NOT collateralDecide.
allowed_reasons contains "compliance-lending-desk" if {
	input.principal.type == "HUMAN"
	"ROLE_COMPLIANCE" in input.principal.roles
	input.action in {
		"lending.create",
		"lending.read",
		"lending.list",
		"lending.repay",
		"lending.writeoff",
		"lending.collateralRegister",
	}
}

# customer-edge submits customer self-service loan applications (ADR-0211's "Customer
# intake" row; CustomerIntakeResource). This is the named, action-scoped M2M rule the note
# below anticipated — the first in-repo service to call a lending @Authorize endpoint.
#
# Identified by principal.id, NOT by principal.type: AuthorizeInterceptor classifies a
# client_credentials JWT as HUMAN, so `input.principal.type == "SERVICE"` can never fire
# (issue #266), and edge's only role is ROLE_OPERATOR, which real staff also carry.
#
# This rule is NOT the control. `operator-lending-write` above already admits any
# lending.* action for a ROLE_OPERATOR principal, so rego cannot be what stops a person
# at a desk from filing an application in a customer's name — CustomerIntakeResource
# checks the principal name against `lending.intake.caller-principal` in Kotlin and
# refuses when it is unset. This rule states the intent, and narrows the day the blanket
# operator grant is tightened.
allowed_reasons contains "edge-customer-intake" if {
	input.principal.id == "service-account-openbank-edge"
	input.action == "lending.intake"
}

# NO BLANKET SERVICE (M2M) rule on purpose: the only in-repo M2M caller is the one named
# above (the admin-ui BFF reaches only the unauthenticated /api/v1/info discovery, the
# observability/security scanners use the management port, and ledger posting is an
# OUTBOUND call from lending). A blanket SERVICE allow would open every endpoint to any
# M2M client. If a future caller lands (e.g. anacredit moving from Kafka to REST), add
# another named, action-scoped rule here.
REGO
)

CHECKSUM=$(printf '%s\n' \
    "$(cat "$REST_REGO")" \
    "$(echo "$LENDING_REST_EXT")" \
    "$(cat "$AGENTS_REGO")" \
    "$(cat "$AGENTS_YAML")" \
    "$(cat "$RULES_YAML")" \
    "$(cat "$MANIFEST")" | \
  (command -v sha256sum >/dev/null 2>&1 && sha256sum || shasum -a 256) | cut -c1-16)

OUT=$REPO/openbank-infra/gitops/components/lending/lending-opa-bundle.yaml

{
  echo "# GENERATED by gen-lending-opa-bundle.sh — do not hand-edit."
  echo "# Source: rest.rego + lending_rest_ext.rego + agents.rego + agents.yaml + rules.yaml + bundle.manifest"
  echo "apiVersion: v1"
  echo "kind: ConfigMap"
  echo "metadata:"
  echo "  name: lending-opa-bundle"
  echo "  namespace: lending"
  echo "  labels:"
  echo "    app.kubernetes.io/name: lending-service"
  echo "    app.kubernetes.io/part-of: lending"
  echo "  annotations:"
  echo "    openbank.tech/policy-checksum: \"$CHECKSUM\""
  echo "data:"
  echo "  rest.rego: |"
  sed 's/^/    /' "$REST_REGO" | sed 's/[[:space:]]*$//'
  echo "  lending_rest_ext.rego: |"
  echo "$LENDING_REST_EXT" | sed 's/^/    /' | sed 's/[[:space:]]*$//'
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
# (subPath mounts do NOT hot-reload — same pattern as gen-sca-opa-bundle.sh).
ROLLOUT=$REPO/openbank-infra/gitops/components/lending/lending-service.yaml
if [ -f "$ROLLOUT" ]; then
  sed -i.bak "s|openbank.tech/policy-checksum: \"[^\"]*\"|openbank.tech/policy-checksum: \"$CHECKSUM\"|" "$ROLLOUT"
  rm -f "${ROLLOUT}.bak"
  echo "patched $ROLLOUT annotation → $CHECKSUM"
fi
