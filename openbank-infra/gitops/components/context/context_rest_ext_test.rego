# SPDX-License-Identifier: Apache-2.0
package openbank.context_rest_ext_test

import data.openbank.rest
import rego.v1

bundle := {"version": "v0.0.0-test"}

test_aml_case_read_requires_root_assignment_even_for_admin if {
	not rest.allow with input as {
		"principal": {"id": "admin-1", "type": "HUMAN", "roles": ["ROLE_ADMIN"]},
		"action": "context.aml-case.read",
		"attributes": {"assignmentVerified": true, "purpose": "AML_INVESTIGATION"},
	}
		with data.openbank.bundle as bundle
}

test_aml_case_read_allows_assigned_compliance if {
	decision := rest.allow with input as {
		"principal": {"id": "analyst-1", "type": "HUMAN", "roles": ["ROLE_COMPLIANCE"]},
		"action": "context.aml-case.read",
		"attributes": {"assignmentVerified": true, "rootScopeVerified": true, "purpose": "AML_INVESTIGATION"},
	}
		with data.openbank.bundle as bundle
	decision.allow
}

test_aml_case_read_denies_operator_and_other_purpose if {
	every example in [
		{"roles": ["ROLE_OPERATOR"], "purpose": "AML_INVESTIGATION"},
		{"roles": ["ROLE_ADMIN"], "purpose": "AUTHORIZATION_REVIEW"},
	] {
		not rest.allow with input as {
			"principal": {"id": "analyst-1", "type": "HUMAN", "roles": example.roles},
			"action": "context.aml-case.read",
			"attributes": {"assignmentVerified": true, "rootScopeVerified": true, "purpose": example.purpose},
		}
			with data.openbank.bundle as bundle
	}
}

test_authority_history_requires_scoped_assignment_even_for_admin if {
	not rest.allow with input as {
		"principal": {"id": "admin-1", "type": "HUMAN", "roles": ["ROLE_ADMIN"]},
		"action": "context.authorization.read",
		"attributes": {"assignmentVerified": true, "purpose": "AUTHORIZATION_REVIEW"},
	}
		with data.openbank.bundle as bundle
}

test_authority_history_allows_root_assigned_compliance if {
	decision := rest.allow with input as {
		"principal": {"id": "analyst-1", "type": "HUMAN", "roles": ["ROLE_COMPLIANCE"]},
		"action": "context.authorization.read",
		"attributes": {"assignmentVerified": true, "rootScopeVerified": true, "purpose": "AUTHORIZATION_REVIEW"},
	}
		with data.openbank.bundle as bundle
	decision.allow
}

test_authority_history_denies_operator_and_wrong_purpose if {
	every example in [
		{"roles": ["ROLE_OPERATOR"], "purpose": "AUTHORIZATION_REVIEW"},
		{"roles": ["ROLE_ADMIN"], "purpose": "PAYMENT_COMPLAINT"},
	] {
		not rest.allow with input as {
			"principal": {"id": "analyst-1", "type": "HUMAN", "roles": example.roles},
			"action": "context.authorization.read",
			"attributes": {"assignmentVerified": true, "rootScopeVerified": true, "purpose": example.purpose},
		}
			with data.openbank.bundle as bundle
	}
}

test_context_assignment_allows_human_admin if {
	decision := rest.allow with input as {
		"principal": {"id": "admin-7", "type": "HUMAN", "roles": ["ROLE_ADMIN"]},
		"action": "context.assignment.decide",
		"resource": {"type": "context", "id": "proposal-1"},
	}
		with data.openbank.bundle as bundle
	decision.allow
	decision.reason == "context-assignment-admin"
}

test_context_assignment_denies_service_account_even_with_admin if {
	not rest.allow with input as {
		"principal": {
			"id": "service-account-openbank-services",
			"type": "HUMAN",
			"roles": ["ROLE_ADMIN", "ROLE_OPERATOR"],
		},
		"action": "context.assignment.read",
	}
		with data.openbank.bundle as bundle
}

test_context_complaint_read_requires_exact_purpose if {
	not rest.allow with input as {
		"principal": {"id": "analyst-1", "type": "HUMAN", "roles": ["ROLE_COMPLIANCE"]},
		"action": "context.complaint.read",
		"attributes": {"assignmentVerified": true, "purpose": "INCIDENT_IMPACT"},
	}
		with data.openbank.bundle as bundle
}

test_context_complaint_read_allows_assigned_compliance_purpose if {
	decision := rest.allow with input as {
		"principal": {"id": "analyst-1", "type": "HUMAN", "roles": ["ROLE_COMPLIANCE"]},
		"action": "context.complaint.read",
		"attributes": {"assignmentVerified": true, "rootScopeVerified": true, "purpose": "PAYMENT_COMPLAINT"},
	}
		with data.openbank.bundle as bundle
	decision.allow
}

test_context_complaint_read_denies_unscoped_assignment_even_for_admin if {
	not rest.allow with input as {
		"principal": {"id": "admin-1", "type": "HUMAN", "roles": ["ROLE_ADMIN"]},
		"action": "context.complaint.read",
		"attributes": {"assignmentVerified": true, "purpose": "PAYMENT_COMPLAINT"},
	}
		with data.openbank.bundle as bundle
}

test_context_incident_read_denies_unscoped_assignment_even_for_admin if {
	not rest.allow with input as {
		"principal": {"id": "admin-1", "type": "HUMAN", "roles": ["ROLE_ADMIN"]},
		"action": "context.incident.aggregate.read",
		"attributes": {"assignmentVerified": true, "purpose": "INCIDENT_IMPACT"},
	}
		with data.openbank.bundle as bundle
}

test_context_incident_read_denies_service_account_even_with_assignment if {
	not rest.allow with input as {
		"principal": {"id": "service-account-openbank-services", "type": "HUMAN", "roles": ["ROLE_OPERATOR"]},
		"action": "context.incident.aggregate.read",
		"attributes": {"assignmentVerified": true, "purpose": "INCIDENT_IMPACT"},
	}
		with data.openbank.bundle as bundle
}
