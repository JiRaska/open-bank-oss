# SPDX-License-Identifier: Apache-2.0
# opa-test-with: openbank-infra/opa/policies/agents.rego
# opa-test-data: real
package openbank.rest_test

import rego.v1

import data.openbank.rest

inspections := {
	"finrep.template.inspect",
	"finrep.corep.inspect",
	"finrep.periods.inspect",
	"finrep.preflight.inspect",
}

test_staff_can_inspect_regulatory_reports if {
	every role in {"ROLE_ADMIN", "ROLE_OPERATOR"} {
		every action in inspections {
			decision := rest.allow with input as {
				"principal": {"service_account": false, "type": "HUMAN", "id": "staff", "roles": [role]},
				"action": action,
			}
			decision.allow
			decision.reason == "staff-finrep-inspection"
		}
	}
}

test_machine_and_unverified_claims_cannot_inspect_regulatory_reports if {
	every principal in {
		{"service_account": true, "client_id": "openbank-edge", "type": "HUMAN", "id": "service-account-openbank-edge", "roles": ["ROLE_OPERATOR"]},
		{"service_account": true, "client_id": "openbank-services", "type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_API"]},
		{"service_account": true, "client_id": "openbank-finrep", "type": "HUMAN", "id": "service-account-openbank-finrep", "roles": ["ROLE_API"]},
		{"service_account": true, "type": "HUMAN", "id": "machine", "roles": ["ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_COMPLIANCE"]},
		{"type": "HUMAN", "id": "unknown", "roles": ["ROLE_OPERATOR", "ROLE_ADMIN"]},
	} {
		every action in inspections {
			rest.allow == false with input as {"principal": principal, "action": action}
		}
	}
}

test_non_staff_cannot_inspect_regulatory_reports if {
	every principal in {
		{"service_account": false, "type": "HUMAN", "id": "viewer", "roles": ["ROLE_VIEWER"]},
		{"service_account": false, "type": "HUMAN", "id": "customer", "roles": ["ROLE_CUSTOMER"]},
		{"service_account": false, "type": "AI_AGENT", "id": "agent", "roles": ["ROLE_OPERATOR"]},
		{"service_account": false, "type": "ANONYMOUS", "id": "anonymous", "roles": []},
	} {
		every action in inspections {
			rest.allow == false with input as {"principal": principal, "action": action}
		}
	}
}

test_unlisted_inspection_and_submission_actions_get_no_grant if {
	every action in {"finrep.unknown.inspect", "finrep.submission.send"} {
		rest.allow == false with input as {
			"principal": {"service_account": false, "type": "HUMAN", "id": "staff", "roles": ["ROLE_ADMIN"]},
			"action": action,
		}
	}
}
