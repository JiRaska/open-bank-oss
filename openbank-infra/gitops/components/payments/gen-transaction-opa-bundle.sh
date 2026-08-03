#!/usr/bin/env bash
set -euo pipefail
REPO="$(git rev-parse --show-toplevel)"

REST_REGO=$REPO/openbank-libs/governance/policies/rest.rego
AGENTS_REGO=$REPO/openbank-infra/opa/policies/agents.rego
AGENTS_YAML=$REPO/openbank-libs/governance/agents.yaml
RULES_YAML=$REPO/openbank-libs/governance/rules-opa-data.yaml
MANIFEST=$REPO/openbank-infra/opa/bundle.manifest

# Transaction-service REST extension — saga-orchestrator allow reasons (ADR-0034 Phase 5, issue #266)
TRANSACTION_REST_EXT=$(cat << 'REGO'
# SPDX-License-Identifier: Apache-2.0
# Transaction-service REST extension (ADR-0034 Phase 5, issue #266).
# Extends openbank.rest with transaction (saga-orchestrator) allow reasons.
# Mounted alongside rest.rego in the same OPA bundle — OPA merges same-package rules.
#
# Actions gated (TransactionResource):
#   transaction.list    — list transactions for an account (resource = "")
#   transaction.search  — BIAN search: IBAN/BBAN/reference/counterparty/amount/date (resource = "")
#   transaction.read    — get transaction by id (#transactionId)
#   transaction.create  — initiate a transaction (resource = "")
#   transaction.reverse — reverse a completed transaction, R-transaction return path (resource = "")
#
# Base rest.rego already grants operator-read-any for *.list / *.read to any HUMAN with
# ROLE_OPERATOR/ROLE_ADMIN — covers transaction.list and transaction.read. This extension
# adds the three verbs the base rule does NOT cover (create/search/reverse are not
# list/read), all gated the same way: HUMAN + ROLE_OPERATOR/ROLE_ADMIN.
#
# ROLE_VIEWER read path: TransactionResource's own RBAC (@RolesAllowed) admits
# ROLE_VIEWER on ALL THREE read endpoints — list, search AND read. Base rest.rego's
# operator-read-any only covers ROLE_OPERATOR/ROLE_ADMIN and only the *.list/*.read verb
# suffix (not *.search), so without an explicit rule here, flipping AUTHZ_ENFORCE would
# silently 403 every viewer list/search/read call that RBAC admits today — a real
# regression a static @Authorize-annotation coverage check cannot see, only caught by
# cross-referencing @RolesAllowed. Strictly read-family; a viewer can never create or
# reverse a transaction (RBAC already excludes ROLE_VIEWER from both write endpoints).
#
# IMPORTANT — no SERVICE principal exists in this fleet's actual token model.
# AuthorizeInterceptor.principalType() (openbank-libs-runtime) classifies every
# non-agent authenticated caller as "HUMAN" — there is no code path that ever sets
# principal.type == "SERVICE" for an OIDC client_credentials caller. Every M2M caller
# of transaction-service uses the openbank-services (account-service, statement-service,
# agent-service) or openbank-edge (customer-edge) Keycloak service-account clients, and
# BOTH are provisioned with realm role ROLE_OPERATOR (see
# openbank-infra/docker/keycloak/realm/openbank-realm.json and
# openbank-infra/gitops/components/keycloak/realm-template.json), not ROLE_SERVICE.
# A rule gated on `input.principal.type == "SERVICE"` would therefore be DEAD CODE for
# every real caller here and enforce mode would silently 403 welcome-bonus grants
# (account-service), customer transfers/pocket-conversions (customer-edge), and
# statement/agent reads the moment AUTHZ_ENFORCE flips — so this extension deliberately
# does NOT add a service-transaction-m2m rule. The one operator-transaction-write rule
# below covers real human operators (admin-ui) and every verified in-repo M2M caller
# identically, because they present the same principal shape today. If a future PR
# introduces a genuine ROLE_SERVICE-bearing client, split this rule then — don't guess
# at it now.
#
# Verified in-repo callers (grep -rn TRANSACTION_SERVICE_URL / configKey =
# "transaction-service" / "transaction-api" across openbank-infra/gitops + service source):
#   - account-service   (TransactionServiceRestClient, openbank-services client): create
#     (grantWelcomeBonus -> POST /api/v1/transactions)
#   - customer-edge     (UpstreamClient, openbank-edge client): create (transfers, pocket
#     conversion/exchange -> POST /api/v1/transactions) + list (GET /api/v1/transactions,
#     ownership already IDOR-checked at the edge before proxying)
#   - statement-service (TransactionRestClient, openbank-services client): search only
#     (GET /api/v1/transactions/search — the booked-entries source, ADR-0035)
#   - agent-service     (TransactionServiceClient, openbank-services client): read + list
#     (GET /transactions/{id}, GET /transactions)
# None of these callers ever invoke transaction.reverse — no in-repo caller reverses a
# transaction over REST today (ADR-0109 R-transaction return path is operator/admin-only
# from the ops console). Left human-only on purpose; do not add a blanket M2M grant for it.
#
# RESIDUAL RISK — shared identity, no per-caller narrowing. Because every M2M caller
# authenticates as principal.type == "HUMAN" with ROLE_OPERATOR (see note above), and
# account-service/statement-service/agent-service all share the SAME Keycloak client
# (openbank-services -> JWT sub "service-account-openbank-services"), OPA cannot
# distinguish "account-service's welcome-bonus credit" from "a real human operator
# clicking initiate in admin-ui" from "statement-service's search" — they are
# indistinguishable principals at this layer. input.principal.id IS actually available
# and could narrow a rule to e.g. id == "service-account-openbank-services", but doing so
# here would not add real isolation: it would still bundle three different services
# (account/statement/agent) under one identity, and excluding real human operators from
# operator-transaction-write is not an option (they must be allowed too). Finer-grained
# per-service M2M identity (separate Keycloak clients per backend service, or mTLS
# workload identity) is a fleet-wide prerequisite this PR does not introduce — tracked
# by the existing gap in issue #395's principal-model discussion, not a new one.

package openbank.rest

import rego.v1

# Human + M2M-presenting-as-human (see note above) operator/admin write path: initiate,
# search and reverse a transaction. Every verified in-repo caller of these three actions
# (admin-ui operators, account-service, customer-edge, statement-service) authenticates
# with ROLE_OPERATOR; ROLE_ADMIN is included for parity with the resource's own
# @RolesAllowed (which already admits ROLE_ADMIN on every endpoint).
allowed_reasons contains "operator-transaction-write" if {
	input.principal.type == "HUMAN"
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN"}
	role in input.principal.roles
	input.action in {
		"transaction.create",
		"transaction.search",
		"transaction.reverse",
	}
}

# Read-only viewer path (see note above) — RBAC already admits ROLE_VIEWER on
# list/search/read; enforce must not silently disable it. Never create/reverse.
allowed_reasons contains "viewer-transaction-read" if {
	input.principal.type == "HUMAN"
	"ROLE_VIEWER" in input.principal.roles
	input.action in {
		"transaction.list",
		"transaction.search",
		"transaction.read",
	}
}
REGO
)

CHECKSUM=$(printf '%s\n' \
    "$(cat "$REST_REGO")" \
    "$(echo "$TRANSACTION_REST_EXT")" \
    "$(cat "$AGENTS_REGO")" \
    "$(cat "$AGENTS_YAML")" \
    "$(cat "$RULES_YAML")" \
    "$(cat "$MANIFEST")" | \
  (command -v sha256sum >/dev/null 2>&1 && sha256sum || shasum -a 256) | cut -c1-16)

OUT=$REPO/openbank-infra/gitops/components/payments/transaction-opa-bundle.yaml

{
  echo "# GENERATED by gen-transaction-opa-bundle.sh — do not hand-edit."
  echo "# Source: rest.rego + transaction_rest_ext.rego + agents.rego + agents.yaml + rules-opa-data.yaml + bundle.manifest"
  echo "apiVersion: v1"
  echo "kind: ConfigMap"
  echo "metadata:"
  echo "  name: transaction-opa-bundle"
  echo "  namespace: payments"
  echo "  labels:"
  echo "    app.kubernetes.io/name: transaction-service"
  echo "    app.kubernetes.io/part-of: payments"
  echo "  annotations:"
  echo "    openbank.tech/policy-checksum: \"$CHECKSUM\""
  echo "data:"
  echo "  rest.rego: |"
  sed 's/^/    /' "$REST_REGO" | sed 's/[[:space:]]*$//'
  echo "  transaction_rest_ext.rego: |"
  echo "$TRANSACTION_REST_EXT" | sed 's/^/    /' | sed 's/[[:space:]]*$//'
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
# (subPath mounts do NOT hot-reload — same pattern as gen-sca-opa-bundle.sh /
# gen-domestic-payment-opa-bundle.sh). payments-services.yaml holds SEVERAL payment-rail
# Rollouts in one file; the sed is anchored on the trailing "# transaction-opa-bundle"
# marker so it can never stomp another rail's checksum.
ROLLOUT=$REPO/openbank-infra/gitops/components/payments/payments-services.yaml
if [ -f "$ROLLOUT" ]; then
  sed -i.bak "s|openbank.tech/policy-checksum: \"[^\"]*\" # transaction-opa-bundle|openbank.tech/policy-checksum: \"$CHECKSUM\" # transaction-opa-bundle|" "$ROLLOUT"
  rm -f "${ROLLOUT}.bak"
  echo "patched $ROLLOUT transaction-service annotation → $CHECKSUM"
fi
