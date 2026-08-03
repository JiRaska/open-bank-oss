#!/usr/bin/env bash
set -euo pipefail
REPO="$(git rev-parse --show-toplevel)"

REST_REGO=$REPO/openbank-libs/governance/policies/rest.rego
AGENTS_REGO=$REPO/openbank-infra/opa/policies/agents.rego
AGENTS_YAML=$REPO/openbank-libs/governance/agents.yaml
RULES_YAML=$REPO/openbank-libs/governance/rules-opa-data.yaml
MANIFEST=$REPO/openbank-infra/opa/bundle.manifest

# Clearing REST extension — settlement/clearing allow reasons (ADR-0034 Phase 5, issue #266)
CLEARING_REST_EXT=$(cat << 'REGO'
# SPDX-License-Identifier: Apache-2.0
# Clearing-service REST extension (ADR-0034 Phase 5, issue #266).
# Extends openbank.rest with clearing-domain allow reasons.
# Mounted alongside rest.rego in the same OPA bundle — OPA merges same-package rules.
#
# Actions gated (ClearingResource). Namespace is `clearingBatch.` — the PRE-EXISTING
# convention from the one already-annotated endpoint (settleBatch), kept as-is rather
# than renamed to the `clearing.` money-path scope rest.rego's money_path_scopes would
# derive from rules.yaml (openbank-clearing-service -> clearing). That mismatch means
# the base four_eyes_required rule does NOT currently fire for any clearingBatch.* verb
# (including .settle / .triggerCycle) — a fleet-wide prefix normalisation is tracked
# separately (issue #395/#396) and out of scope here; see Residual risk in the PR.
#   clearingBatch.submit             — submit a payment for clearing (POST /submit)
#   clearingBatch.list               — list clearing batches
#   clearingBatch.read               — get a clearing batch by id (#id)
#   clearingBatch.readItems          — list items in a clearing batch (#id)
#   clearingBatch.settle             — settle a clearing batch (#id) — high blast radius
#   clearingBatch.triggerCycle       — trigger a clearing cycle for a payment rail
#   clearingBatch.readPositions      — get settlement positions for a cycle
#   clearingBatch.readItem           — get a clearing item by id (#id)
#   clearingBatch.readItemsByPayment — get clearing items by payment id (#paymentId)
#   clearingBatch.reconcile          — run reconciliation check for a settled batch (#id)
#
# Base rest.rego already grants: operator-read-any / compliance-read-any for any
# *.read / *.list action (covers clearingBatch.read and clearingBatch.list only —
# readItems/readItem/readItemsByPayment/readPositions do NOT end in .read or .list,
# so they need an explicit reason below or they would be silently 403'd the moment
# enforce flips on, even though RBAC already admits viewers on those GETs today).

package openbank.rest

import rego.v1

# Operators, admins and the payment-ops role may perform the FULL clearing lifecycle —
# resolve a stuck batch, settle, trigger a cycle, reconcile (the ops-console path).
# ROLE_PAYMENTS is included because the resource's own RBAC (@RolesAllowed) already
# treats it as an equal alternative to operator/admin on every endpoint — enforcing
# OPA must not silently disable a legitimate human role.
allowed_reasons contains "operator-clearing-write" if {
	input.principal.type == "HUMAN"
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_PAYMENTS"}
	role in input.principal.roles
	startswith(input.action, "clearingBatch.")
}

# Read-only viewer path: the resource's RBAC (@RolesAllowed) admits ROLE_VIEWER on every
# GET endpoint (admin-ui read-only console). Base rest.rego's operator-read-any /
# compliance-read-any only cover OPERATOR/ADMIN/COMPLIANCE, so without this rule flipping
# enforce would silently 403 every viewer read that RBAC admits today. Strictly the read
# family — a viewer can never submit, settle, trigger a cycle, or reconcile.
allowed_reasons contains "viewer-clearing-read" if {
	input.principal.type == "HUMAN"
	"ROLE_VIEWER" in input.principal.roles
	input.action in {
		"clearingBatch.list",
		"clearingBatch.read",
		"clearingBatch.readItems",
		"clearingBatch.readPositions",
		"clearingBatch.readItem",
		"clearingBatch.readItemsByPayment",
	}
}

# M2M caller (single verified in-repo caller, read-only):
#   - agent-service (ClearingServiceClient, client_credentials SERVICE token): the
#     assistant's read-only tool tier (ADR-0031) lists batches, reads a batch, and
#     lists a batch's items to answer operator questions — it never submits, settles,
#     triggers a cycle, or reconciles. Deliberately narrow to exactly the three actions
#     the client interface exposes; a blanket SERVICE allow on a money-path settlement
#     rail is forbidden (rules.yaml / ADR-0034). The resource's own @RolesAllowed also
#     admits ROLE_SERVICE on getItem/getItemsByPayment/getPositions, but there is NO
#     in-repo M2M caller for those today — they stay human-only until one exists.
# NOTE (found post-merge, issue tracked separately): AuthorizeInterceptor never
# emits principal.type == "SERVICE" — M2M callers authenticate via Keycloak
# client_credentials JWTs, which the interceptor classifies as HUMAN.
# agent-service shares the `openbank-services` client (like nearly every other
# backend service) — gate on that identity instead. Not unique to
# agent-service; documented inline. (Separate, more severe known issue: this
# same shared-client token is also misclassified in a way that over-grants
# agent-service full HUMAN+ROLE_OPERATOR access via the base operator rule
# already — tracked independently, not fixed here; this rule's narrow scoping
# is still correct in its own right.)
allowed_reasons contains "service-clearing-m2m" if {
	input.principal.type == "HUMAN"
	input.principal.id == "service-account-openbank-services"
	input.action in {
		"clearingBatch.list",
		"clearingBatch.read",
		"clearingBatch.readItems",
	}
}
REGO
)

CHECKSUM=$(printf '%s\n' \
    "$(cat "$REST_REGO")" \
    "$(echo "$CLEARING_REST_EXT")" \
    "$(cat "$AGENTS_REGO")" \
    "$(cat "$AGENTS_YAML")" \
    "$(cat "$RULES_YAML")" \
    "$(cat "$MANIFEST")" | \
  (command -v sha256sum >/dev/null 2>&1 && sha256sum || shasum -a 256) | cut -c1-16)

OUT=$REPO/openbank-infra/gitops/components/payments/clearing-opa-bundle.yaml

{
  echo "# GENERATED by gen-clearing-opa-bundle.sh — do not hand-edit."
  echo "# Source: rest.rego + clearing_rest_ext.rego + agents.rego + agents.yaml + rules-opa-data.yaml + bundle.manifest"
  echo "apiVersion: v1"
  echo "kind: ConfigMap"
  echo "metadata:"
  echo "  name: clearing-opa-bundle"
  echo "  namespace: payments"
  echo "  labels:"
  echo "    app.kubernetes.io/name: clearing-service"
  echo "    app.kubernetes.io/part-of: payments"
  echo "  annotations:"
  echo "    openbank.tech/policy-checksum: \"$CHECKSUM\""
  echo "data:"
  echo "  rest.rego: |"
  sed 's/^/    /' "$REST_REGO" | sed 's/[[:space:]]*$//'
  echo "  clearing_rest_ext.rego: |"
  echo "$CLEARING_REST_EXT" | sed 's/^/    /' | sed 's/[[:space:]]*$//'
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
# anchored on the trailing "# clearing-opa-bundle" marker so it can never stomp another
# rail's checksum.
ROLLOUT=$REPO/openbank-infra/gitops/components/payments/payments-services.yaml
if [ -f "$ROLLOUT" ]; then
  sed -i.bak "s|openbank.tech/policy-checksum: \"[^\"]*\" # clearing-opa-bundle|openbank.tech/policy-checksum: \"$CHECKSUM\" # clearing-opa-bundle|" "$ROLLOUT"
  rm -f "${ROLLOUT}.bak"
  echo "patched $ROLLOUT clearing annotation → $CHECKSUM"
fi
