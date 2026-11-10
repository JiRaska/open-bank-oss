# SPDX-License-Identifier: Apache-2.0
package openbank.rest_test

import rego.v1

import data.openbank.rest

reads := {
	"onboarding.record.list",
	"onboarding.record.read",
	"onboarding.funnel.read",
	"onboarding.business.case.list",
	"onboarding.business.case.read",
	"onboarding.business.funnel.read",
}

shared := {"service_account": true, "client_id": "openbank-services", "type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_OPERATOR", "ROLE_ADMIN"]}

legacy_shared := {"type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_OPERATOR"]}

# Must-ALLOW: every role the resources' @RolesAllowed admits reaches every read.
test_staff_roles_may_read_every_onboarding_view if {
	every role in {"ROLE_VIEWER", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_KYC", "ROLE_COMPLIANCE"} {
		every action in reads {
			"staff-onboarding-read" in rest.allowed_reasons with input as {
				"principal": {"service_account": false, "type": "HUMAN", "id": "alice", "roles": [role]},
				"action": action,
			}
		}
	}
}

# Must-DENY: a customer, an agent and an unauthenticated caller get nothing from this file.
test_non_staff_get_no_onboarding_reason if {
	every p in {
		{"service_account": false, "type": "HUMAN", "id": "cust", "roles": ["ROLE_CUSTOMER"]},
		{"service_account": false, "type": "AI_AGENT", "id": "agent", "roles": ["ROLE_OPERATOR"]},
		{"service_account": false, "type": "ANONYMOUS", "id": "anon", "roles": []},
	} {
		every action in reads {
			not "staff-onboarding-read" in rest.allowed_reasons with input as {"principal": p, "action": action}
		}
	}
}

# A backend service holding ROLE_OPERATOR must not read the PII projection through this rule,
# with the PEP marker present or absent (absent fails closed as a machine).
test_service_accounts_get_no_onboarding_reason if {
	every p in {shared, legacy_shared} {
		every action in reads {
			not "staff-onboarding-read" in rest.allowed_reasons with input as {"principal": p, "action": action}
		}
	}
}

# Enumerated on purpose: an unlisted onboarding action (a future write) inherits nothing.
test_unlisted_onboarding_action_is_not_granted if {
	not "staff-onboarding-read" in rest.allowed_reasons with input as {
		"principal": {"service_account": false, "type": "HUMAN", "id": "alice", "roles": ["ROLE_ADMIN"]},
		"action": "onboarding.record.override",
	}
}
