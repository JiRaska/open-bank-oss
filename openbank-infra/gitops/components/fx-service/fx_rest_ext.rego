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
	not startswith(input.principal.id, "service-account-")
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_PAYMENTS"}
	role in input.principal.roles
	input.action == "fx.convert"
}

allowed_reasons contains "operator-fx-trigger" if {
	input.principal.type == "HUMAN"
	not startswith(input.principal.id, "service-account-")
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
	not startswith(input.principal.id, "service-account-")
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN"}
	role in input.principal.roles
	input.action == "fx.approval.decide"
}

# 2026-08-05 (#3734): the three operator rules above were role-only, so both M2M service
# accounts (HUMAN-classified, ROLE_OPERATOR) rode them — and rules.yaml's role_action_matrix
# grants fx.convert to ROLE_OPERATOR, which matrix-allows re-admits regardless of the
# exclusion. Both M2M clients are verified READ-ONLY on this service (edge: rate sheet proxy;
# shared client: ledger FX revaluation + agent-service MCP read tools), so no identity-scoped
# write grant exists to preserve. The veto closes the matrix path for the edge
# (base rest.rego gates its allow head on `not prohibited`); fx.trigger / fx.approval.decide
# need no veto — absent from the matrix grant, the exclusion closes them outright.
prohibited if {
	input.principal.id == "service-account-openbank-edge"
	input.action == "fx.convert"
}

