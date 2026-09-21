# SPDX-License-Identifier: Apache-2.0
# Unit tests for ledger_rest_ext.rego (#3734 — edge/M2M write tightening).
#
# Run EXPLICITLY by file (the sibling *-opa-bundle.yaml is not valid rego, and `opa test <dir>`
# would try to load it as data and die with a merge error):
#   opa test openbank-libs/governance/policies/rest.rego \
#            openbank-infra/gitops/components/ledger/ledger_rest_ext.rego \
#            openbank-infra/gitops/components/ledger/ledger_rest_ext_test.rego
#
# Do NOT add another service's *_rest_ext.rego to the same invocation: they all extend the
# same package and would cross-contaminate allowed_reasons (issue #1797 follow-up 2).
#
# The core cases assert the FINAL decision: the matrix mock deliberately grants ROLE_OPERATOR
# the ledger writes, so for the edge principal a reason (matrix-allows) still FIRES — only the
# prohibition at the allow head vetoes it. Asserting count(allowed_reasons) == 0 would test
# the wrong layer.

package openbank.rest_test

import data.openbank.rest

# The mock GRANTS the matrix-covered ledger writes to ROLE_OPERATOR on purpose: the prohibition
# must beat a live matrix grant, or it is decoration. ledger.approve is deliberately ABSENT —
# the matrix does not grant it in production either, so its edge denial rides the rule
# exclusion alone (asserted separately).
rules_mock := {
	"authz": {"role_action_matrix": {"ROLE_OPERATOR": {"grant": [
		"ledger.create",
		"ledger.reverse",
		"ledger.trigger",
		"ledger.replay",
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

test_edge_may_not_create_despite_matrix_grant if {
	rest.allow == false with input as {"principal": edge, "action": "ledger.create"}
		with data.rules as rules_mock
}

test_edge_may_not_reverse_despite_matrix_grant if {
	rest.allow == false with input as {"principal": edge, "action": "ledger.reverse"}
		with data.rules as rules_mock
}

test_edge_may_not_trigger_despite_matrix_grant if {
	rest.allow == false with input as {"principal": edge, "action": "ledger.trigger"}
		with data.rules as rules_mock
}

test_edge_may_not_replay_despite_matrix_grant if {
	rest.allow == false with input as {"principal": edge, "action": "ledger.replay"}
		with data.rules as rules_mock
}

test_edge_may_not_attest_year_close if {
	# No matrix grant for ledger.approve (mirrors production): the denial rides the
	# service-account exclusion on operator-year-close-attest — and the prohibition backstops
	# it against any future matrix edit.
	rest.allow == false with input as {"principal": edge, "action": "ledger.approve"}
		with data.rules as rules_mock
	rest.prohibited with input as {"principal": edge, "action": "ledger.approve"}
		with data.rules as rules_mock
}

test_prohibition_fires_for_edge_on_write if {
	rest.prohibited with input as {"principal": edge, "action": "ledger.create"}
		with data.rules as rules_mock
}

# The prohibition must NOT catch the shared client: transaction/lending/settlement posting is
# the whole reason the graduated identity-scoped rules exist — that is exactly why this veto
# is edge-scoped and not interest's all-service-accounts shape (#3734).
test_shared_m2m_may_post_via_identity_rule if {
	decision := rest.allow with input as {"principal": services_m2m, "action": "ledger.create"}
		with data.rules as rules_mock
	decision.allow == true
	"service-ledger-post" in rest.allowed_reasons with input as {"principal": services_m2m, "action": "ledger.create"}
		with data.rules as rules_mock
}

test_shared_m2m_may_reverse_via_identity_rule if {
	decision := rest.allow with input as {"principal": services_m2m, "action": "ledger.reverse"}
		with data.rules as rules_mock
	decision.allow == true
	"service-ledger-reverse" in rest.allowed_reasons with input as {"principal": services_m2m, "action": "ledger.reverse"}
		with data.rules as rules_mock
}

test_shared_m2m_may_not_create_close_draft if {
	rest.allow == false with input as {"principal": services_m2m, "action": "ledger.close.draft"}
		with data.rules as rules_mock
}

test_shared_m2m_no_longer_admitted_via_role_only_reason if {
	# The ext reason no longer fires for a service-account principal. (The shared client can
	# still be admitted on matrix-granted actions via base matrix-allows — a pre-existing
	# rules.yaml grant this PR does not touch; its write path is the identity-scoped rules
	# asserted above.)
	not "operator-ledger-write" in rest.allowed_reasons with input as {"principal": services_m2m, "action": "ledger.create"}
		with data.rules as rules_mock
}

# --- what must keep working ---

test_operator_may_create if {
	decision := rest.allow with input as {"principal": operator, "action": "ledger.create"}
		with data.rules as rules_mock
	decision.allow == true
	"operator-ledger-write" in rest.allowed_reasons with input as {"principal": operator, "action": "ledger.create"}
		with data.rules as rules_mock
}

test_operator_may_attest_year_close if {
	decision := rest.allow with input as {"principal": operator, "action": "ledger.approve"}
		with data.rules as rules_mock
	decision.allow == true
	"operator-year-close-attest" in rest.allowed_reasons with input as {"principal": operator, "action": "ledger.approve"}
		with data.rules as rules_mock
}

test_operator_may_create_close_draft if {
	decision := rest.allow with input as {"principal": operator, "action": "ledger.close.draft"}
		with data.rules as rules_mock
	decision.allow == true
	"operator-ledger-close-draft" in rest.allowed_reasons with input as {"principal": operator, "action": "ledger.close.draft"}
		with data.rules as rules_mock
}

# Known-positive that the base policy and the extension are both loaded: a negative suite would
# pass vacuously without them. operator-ledger-write is the EXTENSION's reason, so its presence
# proves the ext; the matrix-mock wiring is proven by the edge denials flipping red under
# falsification (without the prohibition, matrix-allows admits the edge).
test_extension_is_loaded if {
	"operator-ledger-write" in rest.allowed_reasons with input as {"principal": operator, "action": "ledger.replay"}
		with data.rules as rules_mock
}

# --- #10486: interest-service's own machine identity (ROLE_API only) ---
# The identity carries ROLE_API and nothing else, exactly as the realm template grants it. The
# matrix mock grants ROLE_OPERATOR only, so no role-based reason can admit these principals: the
# identity rule is the whole grant, and everything else must deny.

interest_m2m := {"type": "HUMAN", "id": "service-account-openbank-interest", "roles": ["ROLE_API"]}

# Any OTHER service account holding ROLE_API. LedgerResource.postJournal now admits ROLE_API at the
# RBAC layer, so OPA is the only thing between this principal and a book-of-record write.
other_role_api_sa := {"type": "HUMAN", "id": "service-account-openbank-mcp-service", "roles": ["ROLE_API"]}

no_role := {"type": "HUMAN", "id": "u-nobody", "roles": []}

test_interest_may_create_via_its_identity_rule if {
	decision := rest.allow with input as {"principal": interest_m2m, "action": "ledger.create"}
		with data.rules as rules_mock
	decision.allow == true
	"service-interest-ledger-post" in rest.allowed_reasons with input as {"principal": interest_m2m, "action": "ledger.create"}
		with data.rules as rules_mock
}

test_interest_may_not_perform_any_other_ledger_action if {
	every action in {"ledger.reverse", "ledger.trigger", "ledger.replay", "ledger.approve", "ledger.close.draft"} {
		rest.allow == false with input as {"principal": interest_m2m, "action": action}
			with data.rules as rules_mock
	}
}

test_interest_may_not_create_a_transaction if {
	rest.allow == false with input as {"principal": interest_m2m, "action": "transaction.create"}
		with data.rules as rules_mock
}

# The must-DENY that proves the RBAC widening stays narrow: remove the principal.id line from
# service-interest-ledger-post and this goes red.
test_other_role_api_service_account_may_not_create if {
	rest.allow == false with input as {"principal": other_role_api_sa, "action": "ledger.create"}
		with data.rules as rules_mock
}

test_no_role_principal_may_not_create if {
	rest.allow == false with input as {"principal": no_role, "action": "ledger.create"}
		with data.rules as rules_mock
}

test_interest_identity_rule_does_not_admit_the_shared_client if {
	not "service-interest-ledger-post" in rest.allowed_reasons with input as {"principal": services_m2m, "action": "ledger.create"}
		with data.rules as rules_mock
}
