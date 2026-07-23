# SPDX-License-Identifier: Apache-2.0
# statement-service REST extension (ADR-0034 Phase 5; issue #1797).
# Extends openbank.rest with statement-domain allow reasons.
# Mounted alongside rest.rego in the same OPA bundle — OPA merges same-package rules.
#
# WHY THIS FILE EXISTS: statement-service ships @Authorize on every endpoint with the app
# default AUTHZ_ENFORCE=true, but had NO OPA sidecar — so the interceptor's PDP call failed
# and every @Authorize endpoint failed closed (HTTP 422). Wiring the sidecar + this bundle
# restores service. This rollout keeps AUTHZ_ENFORCE=false (advisory) so the live decision
# log can confirm the "would DENY" population is empty before the enforce flip (a separate,
# deliberate follow-up per the rules.yaml four_eyes / AUTHZ_ENFORCE guardrail).
#
# statement-service is NOT a money_path_services scope, and none of close/export/trigger is a
# four_eyes.verbs verb (rules.yaml) — so no four-eyes flag is raised here, deliberately. Do
# NOT add four-eyes logic in this file; that is rest.rego's job.
#
# ---------------------------------------------------------------------------------------
# CALLER AUDIT (issue #1797). Every statement @Authorize action, its real in-repo caller,
# and where the grant comes from. Actions already covered by base rest.rego are listed for
# completeness but carry NO rule here (least privilege — do not restate a base grant):
#
#   statement.list   (GET /{accountId})                — customer-edge M2M
#   statement.read   (GET /{accountId}/{ccy}/{seq})    — customer-edge M2M
#       Caller: the customer app's "my statements" list + on-demand render, forwarded by
#       customer-edge with its own client_credentials token (client-id openbank-edge ->
#       principal.id service-account-openbank-edge), carrying ROLE_OPERATOR, plus an
#       X-Party-Id header that statement-service scopes by; per-request IDOR ownership is
#       enforced in CustomerEdgeResource (ownsAccount) before the call. Both verbs end in
#       .list/.read, so base rest.rego's operator-read-any already grants them — NO new rule.
#
#   statement.close-run.list / statement.close-run.read — admin-ui staff (ADMIN/OPERATOR)
#       via operator-read-any (base) for those two roles; the telemetry-read rule below adds
#       ROLE_VIEWER / ROLE_AUDITOR, whom CloseRunResource's @RolesAllowed explicitly lists for
#       these read-only close-cadence views and whom base rest.rego grants nothing.
#
# Actions NOT covered by base, granted below:
#   statement.close-run.trigger (POST /close-runs)     — admin-ui staff, closings:run
#   statement.close             (POST /{accountId}/close)
#   statement.export            (GET  /{accountId}/{ccy}/export)
# ---------------------------------------------------------------------------------------

package openbank.rest

import rego.v1

# Read-only close-cadence telemetry (ADR-0069 D3). CloseRunResource's @RolesAllowed lists
# ROLE_VIEWER/OPERATOR/ADMIN/AUDITOR for the GET views, and the admin-ui day-end page relays
# the signed-in staff member's OWN Keycloak bearer (closings/upstream.ts) — never a service
# account. Base rest.rego's operator-read-any only covers OPERATOR/ADMIN, so viewers/auditors
# (a read-only oversight persona) would fail closed without this. Scoped to the two exact
# close-run read actions — NOT a "statement." family prefix — and excludes any service-account
# principal (no M2M caller reads close-runs; the only M2M caller, the edge, calls list/read).
allowed_reasons contains "statement-close-run-telemetry-read" if {
	input.principal.type == "HUMAN"
	not startswith(input.principal.id, "service-account-")
	some role in {"ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_AUDITOR"}
	role in input.principal.roles
	input.action in {"statement.close-run.list", "statement.close-run.read"}
}

# Manual catch-up close trigger (ADR-0069 D3). admin-ui's closings BFF gates this POST on the
# closings:run permission (ADMIN/OPERATOR) and relays the staff member's own bearer; upstream
# @RolesAllowed is ROLE_OPERATOR/ADMIN. No in-repo M2M caller — the scheduled cadence runs
# in-process (RunCloseUseCase), it does not POST this endpoint. The `service-account-` exclusion
# is load-bearing: the edge M2M identity carries ROLE_OPERATOR (see the caller audit above), so
# without it a role-only check would hand this operator action to the edge, which never calls it.
allowed_reasons contains "operator-statement-close-run-trigger" if {
	input.principal.type == "HUMAN"
	not startswith(input.principal.id, "service-account-")
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN"}
	role in input.principal.roles
	input.action == "statement.close-run.trigger"
}

# Operator-initiated manual month close for one account (StatementResource POST /{accountId}/close,
# @RolesAllowed ROLE_OPERATOR/ADMIN). No in-repo REST caller today — the monthly close runs
# in-process off the scheduler; this endpoint is the operator console's manual catch-up for a
# single account. ASSUMPTION for review: granted to human ROLE_OPERATOR/ADMIN only, excluding
# service accounts — matching the scheduled trigger above. If a future M2M orchestrator is meant
# to drive per-account close, add that caller here with matching evidence, do NOT widen this rule.
allowed_reasons contains "operator-statement-close" if {
	input.principal.type == "HUMAN"
	not startswith(input.principal.id, "service-account-")
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN"}
	role in input.principal.roles
	input.action == "statement.close"
}

# Ad-hoc informational export for an arbitrary date range (StatementResource GET
# /{accountId}/{ccy}/export, @RolesAllowed VIEWER/OPERATOR/ADMIN/AUDITOR/SERVICE). The verb
# "export" is outside operator-read-any's {list, read} verb set, so base rest.rego grants it
# nothing. No in-repo caller today (the customer app uses statement.read to render, not export).
# ASSUMPTION for review: treated as an operator/admin read-family action and granted to human
# ROLE_OPERATOR/ADMIN only, excluding service accounts. Deliberately conservative — if the
# customer app or another caller is meant to reach export, add that principal here with evidence
# rather than broadening to VIEWER/AUDITOR or to the edge M2M speculatively.
allowed_reasons contains "operator-statement-export" if {
	input.principal.type == "HUMAN"
	not startswith(input.principal.id, "service-account-")
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN"}
	role in input.principal.roles
	input.action == "statement.export"
}
