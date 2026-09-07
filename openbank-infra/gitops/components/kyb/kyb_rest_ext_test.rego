# SPDX-License-Identifier: Apache-2.0
package openbank.rest_test

import rego.v1

import data.openbank.rest

edge := {"type": "HUMAN", "id": "service-account-openbank-edge", "roles": ["ROLE_API"]}

staff := {"type": "HUMAN", "id": "alice", "roles": ["ROLE_KYC"]}

shared := {"type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_OPERATOR"]}

test_edge_may_start_a_case if {
	"edge-service-kyb" in rest.allowed_reasons with input as {"principal": edge, "action": "kyb.case.start"}
}

test_edge_may_not_resolve_a_review if {
	not "edge-service-kyb" in rest.allowed_reasons with input as {"principal": edge, "action": "kyb.case.review.resolve"}
}

test_staff_may_resolve_a_review if {
	"operator-kyb-review" in rest.allowed_reasons with input as {"principal": staff, "action": "kyb.case.review.resolve"}
}

test_shared_service_account_gets_no_kyb_reason_from_this_file if {
	not "operator-kyb-review" in rest.allowed_reasons with input as {"principal": shared, "action": "kyb.case.reject"}
	not "edge-service-kyb" in rest.allowed_reasons with input as {"principal": shared, "action": "kyb.case.reject"}
}

# ADR-0284 D5. A beneficial-ownership extract is personal data about third parties who are not the
# caller: the analyst working the review queue may read it, the customer edge may not. The second
# case is the one worth a test — it holds only because kyb.ubo.read is absent from an enumerated
# list, and an enumeration is exactly the thing a later edit widens by accident.
test_staff_may_read_beneficial_owners if {
	"operator-kyb-review" in rest.allowed_reasons with input as {"principal": staff, "action": "kyb.ubo.read"}
}

test_edge_may_not_read_beneficial_owners if {
	not "edge-service-kyb" in rest.allowed_reasons with input as {"principal": edge, "action": "kyb.ubo.read"}
}
