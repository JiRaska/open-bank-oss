# SPDX-License-Identifier: Apache-2.0
# opa-test-with: openbank-infra/opa/policies/agents.rego
# opa-test-data: real
package openbank.rest_test

import rego.v1

import data.openbank.rest

reads := {
	"onboarding.records.inspect",
	"onboarding.record.inspect",
	"onboarding.funnel.inspect",
	"onboarding.business.cases.inspect",
	"onboarding.business.case.inspect",
	"onboarding.business.funnel.inspect",
}

test_staff_roles_may_read_every_onboarding_view if {
	every role in {"ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_KYC", "ROLE_COMPLIANCE"} {
		every action in reads {
			request := {
				"principal": {"service_account": false, "type": "HUMAN", "id": "staff", "roles": [role]},
				"action": action,
			}
			"staff-onboarding-read" in rest.allowed_reasons with input as request
			decision := rest.allow with input as request
			decision.allow
			decision.reason == "staff-onboarding-read"
		}
	}
}

test_non_staff_cannot_read_onboarding_views if {
	every principal in {
		{"service_account": false, "type": "HUMAN", "id": "customer", "roles": ["ROLE_CUSTOMER"]},
		{"service_account": false, "type": "AI_AGENT", "id": "agent", "roles": ["ROLE_OPERATOR"]},
		{"service_account": false, "type": "ANONYMOUS", "id": "anonymous", "roles": []},
	} {
		every action in reads {
			request := {"principal": principal, "action": action}
			not "staff-onboarding-read" in rest.allowed_reasons with input as request
			rest.allow == false with input as request
		}
	}
}

test_machine_and_unverified_staff_claims_cannot_read_onboarding_views if {
	every principal in {
		{"service_account": true, "client_id": "openbank-edge", "type": "HUMAN", "id": "service-account-openbank-edge", "roles": ["ROLE_OPERATOR"]},
		{"service_account": true, "client_id": "openbank-services", "type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_API"]},
		{"service_account": true, "client_id": "openbank-finrep", "type": "HUMAN", "id": "service-account-openbank-finrep", "roles": ["ROLE_API"]},
		{"service_account": true, "type": "HUMAN", "id": "machine", "roles": ["ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_COMPLIANCE"]},
		{"type": "HUMAN", "id": "unknown", "roles": ["ROLE_OPERATOR", "ROLE_ADMIN"]},
	} {
		every action in reads {
			request := {"principal": principal, "action": action}
			not "staff-onboarding-read" in rest.allowed_reasons with input as request
			rest.allow == false with input as request
		}
	}
}

test_unlisted_onboarding_action_is_not_granted if {
	request := {
		"principal": {"service_account": false, "type": "HUMAN", "id": "staff", "roles": ["ROLE_ADMIN"]},
		"action": "onboarding.record.override",
	}
	not "staff-onboarding-read" in rest.allowed_reasons with input as request
	rest.allow == false with input as request
}
