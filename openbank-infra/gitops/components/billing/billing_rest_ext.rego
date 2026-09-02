# SPDX-License-Identifier: Apache-2.0
# Billing-service REST extension (ADR-0034 Phase 5, issue #266).
# Extends openbank.rest with billing-domain allow reasons.
# Mounted alongside rest.rego in the same OPA bundle — OPA merges same-package rules.
#
# Actions gated (BillingResource):
#   billing.read — assess (dry-run) an account's product fees for a cycle
#                  (POST /api/v1/fees/assess; a `.read` verb by design, ADR-0034 D5 —
#                  the endpoint computes and returns, it cannot move money).
#
# Base rest.rego already grants: operator-read-any / compliance-read-any for the
# *.read family — billing.read is fully covered for the ops-console and compliance
# paths with no extension rule strictly required today.
#
# operator-billing-write below future-proofs the phase 2c-ii write verbs
# (e.g. billing.post once fee posting to the ledger lands) for the HUMAN
# operator/admin channel, mirroring the sca/consent/pid extensions.
#
# NO SERVICE (M2M) rule: a repo-wide sweep (gitops component env + *.kt REST
# clients) found NO in-repo M2M caller of billing-service — billing is an
# OUTBOUND actor (it reads account/balance/product-catalog and, in phase 2c-ii,
# posts to the ledger; ADR-0143). Deny-by-default stands for SERVICE principals;
# a future caller must add an action-scoped rule here, never a blanket allow.

package openbank.rest

import rego.v1

# Operators and admins may perform ANY billing lifecycle operation — the ops
# console path (re-run an assessment, and the phase 2c-ii posting verbs once
# they land behind the four-eyes gate).
allowed_reasons contains "operator-billing-write" if {
	input.principal.type == "HUMAN"
	not startswith(input.principal.id, "service-account-")
	some role in {"ROLE_OPERATOR", "ROLE_ADMIN"}
	role in input.principal.roles
	startswith(input.action, "billing.")
}

# 2026-08-05 (#3734): operator-billing-write was role-only, and rules.yaml's role_action_matrix
# grants ALL THREE billing writes (post, reverse, approval.decide) to ROLE_OPERATOR — which
# service-account-openbank-edge carries — so both the role-only path and matrix-allows admitted
# the customer-facing proxy to fee posting, reversal, and four-eyes approval decisions. Billing
# has NO in-repo M2M caller at all (verified 2026-08-05: no edge URL, no backend REST client —
# account-service's billing-discovery read is INBOUND from billing, and product-catalog fees are
# read via catalog, not billing), so there is no identity-scoped grant to preserve: the
# exclusion closes the role-only path and this veto closes the matrix path for the edge (base
# rest.rego gates its allow head on `not prohibited`).
prohibited if {
	input.principal.id == "service-account-openbank-edge"
	input.action in {
		"billing.post",
		"billing.reverse",
		"billing.approval.decide",
	}
}

# Ordinary billing.read stays reachable to M2M via base operator-read-any, but
# billing.approval.read is different: it exposes the maker, action and resource id of every
# pending four-eyes decision. Deny it to every service account; some client_credentials
# principals currently retain type HUMAN and ROLE_OPERATOR, so checking type or role alone is
# insufficient to enforce the staff-only boundary.
prohibited if {
	startswith(input.principal.id, "service-account-")
	input.action == "billing.approval.read"
}
