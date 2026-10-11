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
	"scoped_sca_consumers": [{
		"principal": "service-account-openbank-pension",
		"actions": ["scaChallenge.consume"],
		"purposes": ["APPROVAL"],
		"approval_request_prefix": "pension-",
	}],
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

revocation_rules := object.union(rules_mock, {
	"money_path_services": ["openbank-sca-service"],
	"money_path_action_prefixes": {"sca": ["device", "scaChallenge"]},
	"four_eyes": {"verbs": ["revoke"], "actions": []},
})

# Credential revocation grants are owner scoped and retain the money-path obligation.
test_customer_can_request_own_device_revocation if {
	decision := rest.allow with input as {
		"principal": {"id": "party-owner", "type": "HUMAN", "roles": ["ROLE_CUSTOMER"]},
		"action": "device.revoke",
		"resource": {"id": "party-owner"},
	}
		with data.rules as revocation_rules with data.openbank.bundle as {"version": "test"}
	decision.allow == true
}

test_customer_cannot_request_foreign_device_revocation if {
	not rest.allow with input as {
		"principal": {"id": "party-owner", "type": "HUMAN", "roles": ["ROLE_CUSTOMER"]},
		"action": "device.revoke",
		"resource": {"id": "party-other"},
	}
		with data.rules as revocation_rules
}

test_customer_device_revocation_requires_four_eyes if {
	rest.four_eyes_required with input as {
		"principal": {"id": "party-owner", "type": "HUMAN", "roles": ["ROLE_CUSTOMER"]},
		"action": "device.revoke",
		"resource": {"id": "party-owner"},
	}
		with data.rules as revocation_rules
}

test_service_principal_cannot_revoke_customer_device_via_self_rule if {
	not rest.allow with input as {
		"principal": {"id": "service-account-openbank-services", "type": "HUMAN", "roles": ["ROLE_API"]},
		"action": "device.revoke",
		"resource": {"id": "party-owner"},
	}
		with data.rules as revocation_rules
}

# --- operator-approval queue is human-operator only (#10041 slice 10) ---

test_operator_may_read_approval_queue if {
	decision := rest.allow with input as {"principal": operator, "action": "scaChallenge.approval.read"}
		with data.rules as rules_mock
	decision.allow == true
}

test_operator_may_decide_approval if {
	decision := rest.allow with input as {"principal": operator, "action": "scaChallenge.approval.decide", "resource": {"type": "scaChallenge", "id": "a-1"}}
		with data.rules as rules_mock
	decision.allow == true
}

test_shared_m2m_may_not_read_approval_queue if {
	rest.allow == false with input as {"principal": services_m2m, "action": "scaChallenge.approval.read"}
		with data.rules as rules_mock
}

test_edge_may_not_read_approval_queue if {
	rest.allow == false with input as {"principal": edge, "action": "scaChallenge.approval.read"}
		with data.rules as rules_mock
}

test_shared_m2m_may_not_decide_approval if {
	rest.allow == false with input as {"principal": services_m2m, "action": "scaChallenge.approval.decide", "resource": {"type": "scaChallenge", "id": "a-1"}}
		with data.rules as rules_mock
}

test_edge_may_not_decide_approval if {
	rest.allow == false with input as {"principal": edge, "action": "scaChallenge.approval.decide", "resource": {"type": "scaChallenge", "id": "a-1"}}
		with data.rules as rules_mock
}

# --- ADR-0335 D1: pension-service is a consume-only scoped consumer ---

pension := {"type": "HUMAN", "id": "service-account-openbank-pension", "roles": ["ROLE_API"]}

other_sa := {"type": "HUMAN", "id": "service-account-openbank-billing", "roles": ["ROLE_API"]}

agent := {"type": "AI_AGENT", "id": "service-account-openbank-pension", "roles": ["ROLE_API"]}

test_pension_may_consume if {
	decision := rest.allow with input as {"principal": pension, "action": "scaChallenge.consume", "resource": {"type": "scaChallenge", "id": "c-1"}}
		with data.rules as rules_mock
	decision.allow == true
	"service-sca-scoped-consumer" in rest.allowed_reasons with input as {"principal": pension, "action": "scaChallenge.consume"}
		with data.rules as rules_mock
}

test_pension_may_do_nothing_else if {
	every action in {
		"scaChallenge.initiate", "scaChallenge.read", "scaChallenge.verify", "scaChallenge.decide",
		"scaChallenge.approval.read", "scaChallenge.approval.decide",
		"device.enroll", "device.list", "device.revoke",
	} {
		rest.allow == false with input as {"principal": pension, "action": action, "resource": {"type": "party", "id": "p-1"}}
			with data.rules as rules_mock
	}
}

test_other_service_account_may_not_consume if {
	rest.allow == false with input as {"principal": other_sa, "action": "scaChallenge.consume"}
		with data.rules as rules_mock
}

test_pension_id_as_ai_agent_may_not_consume if {
	# The rule keys on a HUMAN-classified client_credentials token; an agent presenting the
	# same id is a different principal type and gets nothing.
	rest.allow == false with input as {"principal": agent, "action": "scaChallenge.consume"}
		with data.rules as rules_mock
}

test_pension_denied_when_not_declared if {
	# The grant exists only because the data declares it: an empty register denies.
	rest.allow == false with input as {"principal": pension, "action": "scaChallenge.consume"}
		with data.rules as object.union(rules_mock, {"scoped_sca_consumers": []})
}
