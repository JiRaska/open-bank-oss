# SPDX-License-Identifier: Apache-2.0
# Unit tests for fraud_rest_ext.rego (#3734 — edge/M2M write tightening).
#
# Run EXPLICITLY by file (the sibling *-opa-bundle.yaml is not valid rego, and `opa test <dir>`
# would try to load it as data and die with a merge error):
#   opa test openbank-libs/governance/policies/rest.rego \
#            openbank-infra/gitops/components/fraud-service/fraud_rest_ext.rego \
#            openbank-infra/gitops/components/fraud-service/fraud_rest_ext_test.rego
#
# Do NOT add another service's *_rest_ext.rego to the same invocation: they all extend the
# same package and would cross-contaminate allowed_reasons (issue #1797 follow-up 2).
#
# The core cases assert the FINAL decision: the matrix mock deliberately grants ROLE_OPERATOR
# fraud.score, so for the edge principal a reason (matrix-allows) still FIRES — only the
# prohibition at the allow head vetoes it. Asserting count(allowed_reasons) == 0 would test
# the wrong layer.

package openbank.rest_test

import data.openbank.rest

# The mock GRANTS fraud.score to ROLE_OPERATOR on purpose: the prohibition must beat a live
# matrix grant, or it is decoration.
rules_mock := {
	"authz": {"role_action_matrix": {"ROLE_OPERATOR": {"grant": [
		"fraud.score",
	]}}},
	"money_path_services": [],
	"money_path_action_prefixes": {},
	"four_eyes": {"verbs": [], "actions": []},
	"feature_flags": {"prohibited_flag_combinations": [], "money_path_flags": []},
	"shared_m2m_write_prohibition": {"reasons": []},
}

operator := {"type": "HUMAN", "id": "u-op", "roles": ["ROLE_OPERATOR"]}

edge := {"type": "HUMAN", "id": "service-account-openbank-edge", "roles": ["ROLE_OPERATOR"]}

services_m2m := {"type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_OPERATOR"]}

# --- the regressions this file exists to prevent ---

test_edge_may_not_score_despite_matrix_grant if {
	rest.allow == false with input as {"principal": edge, "action": "fraud.score"}
		with data.rules as rules_mock
}

test_prohibition_fires_for_edge_on_score if {
	rest.prohibited with input as {"principal": edge, "action": "fraud.score"}
		with data.rules as rules_mock
}

# The prohibition must NOT catch the shared client: fx-service's scoring call is the whole
# reason the graduated identity-scoped rule exists — that is exactly why this veto is
# edge-scoped and not interest's all-service-accounts shape (#3734).
test_shared_m2m_may_score_via_identity_rule if {
	decision := rest.allow with input as {"principal": services_m2m, "action": "fraud.score"}
		with data.rules as rules_mock
	decision.allow == true
	"service-fraud-scoring" in rest.allowed_reasons with input as {"principal": services_m2m, "action": "fraud.score"}
		with data.rules as rules_mock
}

test_shared_m2m_no_longer_admitted_via_role_only_reason if {
	# The ext reason no longer fires for a service-account principal.
	not "operator-fraud-write" in rest.allowed_reasons with input as {"principal": services_m2m, "action": "fraud.score"}
		with data.rules as rules_mock
}

# --- what must keep working ---

test_operator_may_score if {
	decision := rest.allow with input as {"principal": operator, "action": "fraud.score"}
		with data.rules as rules_mock
	decision.allow == true
	"operator-fraud-write" in rest.allowed_reasons with input as {"principal": operator, "action": "fraud.score"}
		with data.rules as rules_mock
}

# Known-positive that the base policy and the extension are both loaded: a negative suite would
# pass vacuously without them. operator-fraud-write is the EXTENSION's reason, so its presence
# proves the ext; the matrix-mock wiring is proven by test_edge_may_not_score's red state under
# falsification (without the prohibition, matrix-allows admits the edge).
test_extension_is_loaded if {
	"operator-fraud-write" in rest.allowed_reasons with input as {"principal": operator, "action": "fraud.score"}
		with data.rules as rules_mock
}

# --- #12921 read side: the analyst review queue is staff-only ---

# The exact over-grant this file's header documented: GET /api/v1/fraud/review-queue is
# @Authorize(action = "fraud.review.read"), admitted by base operator-read-any's *.read
# suffix. Both M2M clients hold ROLE_OPERATOR in the realm (the deployed realm gives it to
# openbank-edge; the CI realm's shared openbank-services carries it), and
# AuthorizeInterceptor classifies every client_credentials token as HUMAN — so before the
# `service-account-` exclusion on base operator-read-any, any shared-client holder read the
# analyst queue through the role-only reason. #3734 closed only the WRITE side;
# #12921 closes the read side at the same base rule. Assert the final decision AND that
# the role-only reason no longer fires for the machine principal.
test_m2m_denied_review_queue_read if {
	rest.allow == false with input as {"principal": services_m2m, "action": "fraud.review.read"}
		with data.rules as rules_mock
	not "operator-read-any" in rest.allowed_reasons with input as {"principal": services_m2m, "action": "fraud.review.read"}
		with data.rules as rules_mock
}

# Same denial for the edge identity — the deployed realm's ROLE_OPERATOR service account.
# (fraud.score stays reachable for the shared client via service-fraud-scoring; the edge
# stays vetoed from fraud.score by the prohibition above — those #3734 tests pin it.)
test_edge_denied_review_queue_read if {
	rest.allow == false with input as {"principal": edge, "action": "fraud.review.read"}
		with data.rules as rules_mock
	not "operator-read-any" in rest.allowed_reasons with input as {"principal": edge, "action": "fraud.review.read"}
		with data.rules as rules_mock
}

# The human analyst keeps the queue: the exclusion keys on the `service-account-` identity
# prefix, which no staff session can hold (sessions authenticate through a different client).
# The extension's operator-fraud-write ALSO fires for fraud.review.read (any fraud.* action),
# so the audited decision.reason is the lexicographically smaller "operator-fraud-write" —
# assert the final decision plus operator-read-any's membership in the firing reasons.
test_human_operator_allowed_review_queue_read if {
	decision := rest.allow with input as {"principal": operator, "action": "fraud.review.read"}
		with data.rules as rules_mock
	decision.allow == true
	"operator-read-any" in rest.allowed_reasons with input as {"principal": operator, "action": "fraud.review.read"}
		with data.rules as rules_mock
}
