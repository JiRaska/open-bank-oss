# SPDX-License-Identifier: Apache-2.0
# Unit tests for sca_rest_ext.rego (#3734 — edge/M2M write tightening).
#
# Run EXPLICITLY by file (the sibling *-opa-bundle.yaml is not valid rego, and `opa test <dir>`
# would try to load it as data and die with a merge error):
#   opa test openbank-libs/governance/policies/rest.rego \
#            openbank-infra/gitops/components/sca/sca_rest_ext.rego \
#            openbank-infra/gitops/components/sca/sca_rest_ext_test.rego
#
# Do NOT add another service's *_rest_ext.rego to the same invocation: they all extend the
# same package and would cross-contaminate allowed_reasons (issue #1797 follow-up 2).
#
# SCA differs from interest/balance/ledger/fraud: the edge IS a legitimate SCA writer (the
# customer ceremony runs through it) and base rest.rego's edge-service-notification covers
# device.* — so the core cases here pin the identity-scoped M2M rules and the ROLE-ONLY path
# being closed, not a blanket M2M write denial. rules.yaml grants no scaChallenge.*/device.*
# write to ROLE_OPERATOR, so the matrix mock carries only device.list.

package openbank.rest_test

import data.openbank.rest

rules_mock := {
	"authz": {"role_action_matrix": {"ROLE_OPERATOR": {"grant": [
		"device.list",
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

test_shared_m2m_may_consume_via_identity_rule if {
	# delegation-service (grant-accept) and document-service (DOCUMENT_SIGNING, ADR-0169)
	# consume challenges via the shared client. Without this grant the operator-sca-write
	# exclusion would 403 both ceremonies — this is the test that pins the ordering.
	decision := rest.allow with input as {"principal": services_m2m, "action": "scaChallenge.consume"}
		with data.rules as rules_mock
	decision.allow == true
	"service-sca-shared-client-m2m" in rest.allowed_reasons with input as {"principal": services_m2m, "action": "scaChallenge.consume"}
		with data.rules as rules_mock
}

test_shared_m2m_may_read_challenge if {
	"service-sca-shared-client-m2m" in rest.allowed_reasons with input as {"principal": services_m2m, "action": "scaChallenge.read"}
		with data.rules as rules_mock
}

test_edge_ceremony_actions_still_work if {
	"service-sca-edge-m2m" in rest.allowed_reasons with input as {"principal": edge, "action": "scaChallenge.initiate"}
		with data.rules as rules_mock
	"service-sca-edge-m2m" in rest.allowed_reasons with input as {"principal": edge, "action": "scaChallenge.decide"}
		with data.rules as rules_mock
	"service-sca-edge-m2m" in rest.allowed_reasons with input as {"principal": edge, "action": "scaChallenge.consume"}
		with data.rules as rules_mock
}

test_edge_may_enroll_device_via_base_rule if {
	# Base edge-service-notification (device.* family) — NOT the sca ext — carries this.
	# The test pins the assumption the ext's non-duplication stance relies on.
	decision := rest.allow with input as {"principal": edge, "action": "device.enroll"}
		with data.rules as rules_mock
	decision.allow == true
	"edge-service-notification" in rest.allowed_reasons with input as {"principal": edge, "action": "device.enroll"}
		with data.rules as rules_mock
}

# --- the role-only hole, now closed ---

test_edge_may_not_verify_via_role_only_path if {
	# scaChallenge.verify is human-channel-only by design; no identity rule covers it for
	# any M2M principal, and the matrix grants nothing. Base default-deny must answer.
	rest.allow == false with input as {"principal": edge, "action": "scaChallenge.verify"}
		with data.rules as rules_mock
}

test_shared_m2m_may_not_decide if {
	rest.allow == false with input as {"principal": services_m2m, "action": "scaChallenge.decide"}
		with data.rules as rules_mock
}

test_shared_m2m_no_longer_admitted_via_role_only_reason if {
	not "operator-sca-write" in rest.allowed_reasons with input as {"principal": services_m2m, "action": "scaChallenge.verify"}
		with data.rules as rules_mock
}

test_edge_no_longer_admitted_via_role_only_reason if {
	not "operator-sca-write" in rest.allowed_reasons with input as {"principal": edge, "action": "scaChallenge.verify"}
		with data.rules as rules_mock
}

# --- what must keep working ---

test_operator_may_verify if {
	decision := rest.allow with input as {"principal": operator, "action": "scaChallenge.verify"}
		with data.rules as rules_mock
	decision.allow == true
	"operator-sca-write" in rest.allowed_reasons with input as {"principal": operator, "action": "scaChallenge.verify"}
		with data.rules as rules_mock
}

test_operator_may_enroll_on_behalf if {
	"operator-sca-write" in rest.allowed_reasons with input as {"principal": operator, "action": "device.enroll"}
		with data.rules as rules_mock
}

# Known-positive that the extension is loaded (operator-sca-write is its reason); the base
# wiring is proven by test_edge_may_enroll_device_via_base_rule above.
test_extension_is_loaded if {
	"operator-sca-write" in rest.allowed_reasons with input as {"principal": operator, "action": "scaChallenge.initiate"}
		with data.rules as rules_mock
}
