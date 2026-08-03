#!/usr/bin/env bash
set -euo pipefail
REPO="$(git rev-parse --show-toplevel)"

REST_REGO=$REPO/openbank-libs/governance/policies/rest.rego
AGENTS_REGO=$REPO/openbank-infra/opa/policies/agents.rego
AGENTS_YAML=$REPO/openbank-libs/governance/agents.yaml
RULES_YAML=$REPO/openbank-libs/governance/rules-opa-data.yaml
MANIFEST=$REPO/openbank-infra/opa/bundle.manifest

# Fx REST extension — FX rate/conversion allow reasons (ADR-0034 Phase 5, issue #266)
FX_REST_EXT=$(cat << 'REGO'
# SPDX-License-Identifier: Apache-2.0
# Fx-service REST extension (ADR-0034 Phase 5, issue #266).
# Extends openbank.rest with FX-domain allow reasons.
# Mounted alongside rest.rego in the same OPA bundle — OPA merges same-package rules.
#
# Actions gated (FxResource / CnbResource):
#   fx.list    — current FX rate sheet (GET /rates)
#   fx.read    — a rate / rate history / conversion by id (GET, no resource on the rate
#                endpoints; #id on GET /conversions/{id})
#   fx.convert — execute an FX conversion (POST /convert; renamed from fx.create and
#                four-eyes gated, rules.yaml four_eyes.verbs, issue #938 follow-up)
#   fx.trigger — ingest the CNB fixing for a day (POST /cnb/ingest)
#
# Actions gated (ApprovalResource, ADR-0155):
#   fx.approval.decide — a DIFFERENT operator decides a paused fx.convert request (#id)
#
# Base rest.rego already grants: operator-read-any / compliance-read-any for
# *.read + *.list (fx-service is in rules.yaml money_path_services, so operators,
# admins and compliance already read today) — this extension only needs to add the
# roles/callers the base rules don't cover.

package openbank.rest

import rego.v1

# Read-only viewer path: FxResource/CnbResource's own RBAC (@RolesAllowed) admits
# ROLE_VIEWER on every GET. Base rest.rego's operator-read-any covers only
# OPERATOR/ADMIN, so without this rule flipping enforce would silently 403 every
# viewer read that RBAC admits today. Strictly read/list.
allowed_reasons contains "viewer-fx-read" if {
	input.principal.type == "HUMAN"
	"ROLE_VIEWER" in input.principal.roles
	input.action in {"fx.read", "fx.list"}
}

# Human FX-desk writes: operators, admins and the dedicated payments role may execute a
# conversion (POST /convert) — ROLE_PAYMENTS is included because the resource's own
# RBAC already treats it as an equal alternative to operator on that endpoint; enforcing
# OPA must not silently disable a legitimate human role. Ingesting the CNB fixing
# (fx.trigger) is a narrower ops action restricted to OPERATOR/ADMIN by the resource's
# own RBAC — mirrored here rather than granted via the wider payments-desk set.
allowed_reasons contains "operator-fx-write" if {
	input.principal.type == "HUMAN"
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_PAYMENTS"}
	role in input.principal.roles
	input.action == "fx.convert"
}

allowed_reasons contains "operator-fx-trigger" if {
	input.principal.type == "HUMAN"
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN"}
	role in input.principal.roles
	input.action == "fx.trigger"
}

# M2M callers on the rate sheet (three verified in-repo callers, all read-only):
#   - ledger-service (FxServiceClient): reads the CNB fixing for FX revaluation postings
#     (ADR feature-flagged fx-revaluation-enabled).
#   - customer-edge (UpstreamClient service token): proxies the published rate sheet and
#     a single-pair rate/history to the customer app (GET /fx/rates, /fx/rates/{b}/{q},
#     /fx/rates/{b}/{q}/history) — all `customer.fx.read` at the edge, `fx.list`/`fx.read`
#     downstream.
#   - agent-service (MCP read-only tools, ADR-0031 D5): fx_list_rates / fx_get_rate.
# Deliberately narrow: fx.convert (the money-moving conversion, renamed from fx.create,
# issue #938 follow-up — four-eyes gated per rules.yaml four_eyes.verbs) and fx.trigger
# (CNB ingest) have NO in-repo M2M caller today and stay human-only. No blanket SERVICE
# allow on a money-path service (rules.yaml money_path_services includes openbank-fx-service).
#
# NOTE (found post-merge, issue tracked separately): AuthorizeInterceptor never
# emits principal.type == "SERVICE" — M2M callers authenticate via Keycloak
# client_credentials JWTs, which the interceptor classifies as HUMAN. Split into
# two rules since the callers use different Keycloak clients (both granting the
# same read-only action set):
#   - customer-edge has its own dedicated client, identity
#     `service-account-openbank-edge`.
#   - ledger-service and agent-service share the `openbank-services` client
#     (like nearly every other backend service), identity
#     `service-account-openbank-services` — not unique to either, documented
#     inline.
allowed_reasons contains "service-fx-edge-m2m" if {
	input.principal.type == "HUMAN"
	input.principal.id == "service-account-openbank-edge"
	input.action in {"fx.read", "fx.list"}
}

allowed_reasons contains "service-fx-shared-client-m2m" if {
	input.principal.type == "HUMAN"
	input.principal.id == "service-account-openbank-services"
	input.action in {"fx.read", "fx.list"}
}

# ADR-0155 checker gate: a DIFFERENT operator/admin decides a paused four-eyes approval on
# fx.convert (issue #938 follow-up).
allowed_reasons contains "operator-fx-approval-decide" if {
	input.principal.type == "HUMAN"
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN"}
	role in input.principal.roles
	input.action == "fx.approval.decide"
}
REGO
)

CHECKSUM=$(printf '%s\n' \
    "$(cat "$REST_REGO")" \
    "$(echo "$FX_REST_EXT")" \
    "$(cat "$AGENTS_REGO")" \
    "$(cat "$AGENTS_YAML")" \
    "$(cat "$RULES_YAML")" \
    "$(cat "$MANIFEST")" | \
  (command -v sha256sum >/dev/null 2>&1 && sha256sum || shasum -a 256) | cut -c1-16)

OUT=$REPO/openbank-infra/gitops/components/fx-service/fx-opa-bundle.yaml

{
  echo "# GENERATED by gen-fx-opa-bundle.sh — do not hand-edit."
  echo "# Source: rest.rego + fx_rest_ext.rego + agents.rego + agents.yaml + rules-opa-data.yaml + bundle.manifest"
  echo "apiVersion: v1"
  echo "kind: ConfigMap"
  echo "metadata:"
  echo "  name: fx-opa-bundle"
  echo "  namespace: fx"
  echo "  labels:"
  echo "    app.kubernetes.io/name: fx-service"
  echo "    app.kubernetes.io/part-of: fx"
  echo "  annotations:"
  echo "    openbank.tech/policy-checksum: \"$CHECKSUM\""
  echo "data:"
  echo "  rest.rego: |"
  sed 's/^/    /' "$REST_REGO" | sed 's/[[:space:]]*$//'
  echo "  fx_rest_ext.rego: |"
  echo "$FX_REST_EXT" | sed 's/^/    /' | sed 's/[[:space:]]*$//'
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
ROLLOUT=$REPO/openbank-infra/gitops/components/fx-service/fx-service.yaml
if [ -f "$ROLLOUT" ]; then
  sed -i.bak "s|openbank.tech/policy-checksum: \"[^\"]*\"|openbank.tech/policy-checksum: \"$CHECKSUM\"|" "$ROLLOUT"
  rm -f "${ROLLOUT}.bak"
  echo "patched $ROLLOUT annotation → $CHECKSUM"
fi
