# SPDX-License-Identifier: Apache-2.0
# Sepa-instant REST extension (ADR-0034 Phase 5, issue #266).
# Extends openbank.rest with SCT Inst (sepa-instant)-domain allow reasons.
# Mounted alongside rest.rego in the same OPA bundle — OPA merges same-package rules.
#
# Actions gated (SctInstResource). NOTE: the real action prefix is `sctInstPayment`,
# not `sepa-instant` — confirmed from source (SctInstResource.kt), the existing
# `sctInstPayment.recall` annotation predates this change. `money_path_scopes` in the
# base rest.rego derives "sepa-instant" from the module name openbank-sepa-instant, so
# it does NOT match this prefix and four_eyes_required never fires for this rail today
# (recall isn't in the four_eyes verb list either). That mismatch is a fleet-wide
# concern tracked separately (issue #395 / PR #396) — out of scope here; this extension
# only adds the missing allow reasons under the prefix actually used in code.
#   sctInstPayment.create — submit a new SCT Inst payment (POST /)
#   sctInstPayment.read   — get payment by id (#paymentId)
#   sctInstPayment.list   — list all / list-by-debtor-account
#   sctInstPayment.recall — recall a settled payment (#paymentId) — already covered by
#                           the base operator-on-own-tenant rule; no extension needed.
#
# Base rest.rego already grants: operator-read-any / compliance-read-any for *.read +
# *.list, and operator-on-own-tenant (resource-scoped) for *.recall. Only *.create has
# no base coverage — that's the one gap this extension fills.

package openbank.rest

import rego.v1

# Operators, admins and the payments desk (ROLE_PAYMENTS, declared in the resource's own
# @RolesAllowed) may submit a new SCT Inst payment. The admin-ui payments console
# initiates with the signed-in operator's OWN bearer token (BFF pattern, ADR-0080 P1).
# This money-path rail's four-eyes / canary controls are independent platform
# safeguards; approval logic lives in the handlers, never in this policy.
allowed_reasons contains "operator-sepa-instant-write" if {
	input.principal.type == "HUMAN"
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_PAYMENTS"}
	role in input.principal.roles
	input.action == "sctInstPayment.create"
}

# No SERVICE (M2M) allow reason: at the time of writing, there is NO in-repo caller of
# any SctInstResource endpoint (grep across gitops/components and every service's
# src for SEPA_INSTANT_URL / sctInst / sepa-instant.payments.svc client code found none
# — @RolesAllowed already admits ROLE_SERVICE on listAll/listByDebtor at the RBAC layer,
# but that predates any real caller). Deliberately NOT granting a blanket SERVICE allow
# here without verified caller evidence (ADR-0034 Phase 5 safety rule) — if/when a real
# M2M caller (e.g. customer-edge for initiate, or clearing-service/clearing-simulator for
# an inbound return-equivalent flow) is wired up, add a narrowly-scoped SERVICE rule
# mirroring gen-sepa-payment-opa-bundle.sh's service-sepa-payment-m2m pattern — scoped to
# exactly the verified actions, never every sctInstPayment.* action.

# #10486 batch 7 — the first verified M2M caller, so the rule the note above asks for. agent-service's
# sepa_instant_list / sepa_instant_get / sepa_instant_list_by_debtor tools (SepaInstantServiceClient)
# used to reach these reads on the shared openbank-services principal's ROLE_OPERATOR (base
# operator-read-any). They now present service-account-openbank-agent (ROLE_API only), and this rule
# is that principal's whole grant here: the two read verbs, never create or recall. The tools map to
# query.payments.readonly, which only the compliance-officer charter holds; the agent gate refuses every
# other agent before the call is made. SctInstResource.getPayment admits ROLE_API at the RBAC layer for
# this caller (list/listByDebtor already did); OPA, enforced here, denies every other ROLE_API holder
# (sepa_instant_rest_ext_test.rego).
allowed_reasons contains "service-agent-sct-inst-read" if {
	input.principal.type == "HUMAN"
	input.principal.id == "service-account-openbank-agent"
	input.action in {"sctInstPayment.list", "sctInstPayment.read"}
}
