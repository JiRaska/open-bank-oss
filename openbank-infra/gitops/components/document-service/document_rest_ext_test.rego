# SPDX-License-Identifier: Apache-2.0
# Unit tests for document_rest_ext.rego (business onboarding agreement grant).
#
# Run EXPLICITLY by file (the sibling *-opa-bundle.yaml is not valid rego):
#   opa test openbank-libs/governance/policies/rest.rego \
#            openbank-infra/gitops/components/document-service/document_rest_ext.rego \
#            openbank-infra/gitops/components/document-service/document_rest_ext_test.rego

package openbank.rest_test

import data.openbank.rest

rules_mock := {
	"authz": {"role_action_matrix": {}},
	"money_path_services": [],
	"money_path_action_prefixes": {},
	"four_eyes": {"verbs": [], "actions": []},
	"feature_flags": {"prohibited_flag_combinations": [], "money_path_flags": []},
	"shared_m2m_write_prohibition": {"reasons": []},
}

# The deployed realm gives the shared client ROLE_API only — the case this rule exists for.
services_m2m := {"type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_API"]}

edge := {"type": "HUMAN", "id": "service-account-openbank-edge", "roles": ["ROLE_OPERATOR"]}

customer := {"type": "HUMAN", "id": "11111111-1111-4111-8111-111111111111", "roles": ["ROLE_CUSTOMER"]}

case_resource := {"type": "document", "id": "22222222-2222-4222-8222-222222222222"}

decision(principal, action) := d if {
	d := rest.allow with input as {
		"principal": principal,
		"action": action,
		"resource": case_resource,
		"attributes": {},
	}
		with data.rules as rules_mock
}

test_kyb_service_may_ensure if {
	decision(services_m2m, "document.business-agreement.ensure").allow == true
}

test_kyb_service_may_read if {
	decision(services_m2m, "document.business-agreement.read").allow == true
}

# Must-deny control: the rule is scoped to its two actions, not the document family.
test_kyb_service_cannot_publish_templates if {
	not decision(services_m2m, "documentTemplate.publish").allow
}

# The edge holds the `document.` family through base rest.rego; the veto must beat that grant.
test_edge_vetoed_on_ensure if {
	not decision(edge, "document.business-agreement.ensure").allow
}

test_edge_vetoed_on_read if {
	not decision(edge, "document.business-agreement.read").allow
}

# Must-allow control for the veto: the edge keeps the rest of its document family.
test_edge_still_reads_document_content if {
	decision(edge, "document.readContent").allow == true
}

test_other_service_account_denied if {
	other := {"type": "HUMAN", "id": "service-account-openbank-reporting", "roles": ["ROLE_API"]}
	not decision(other, "document.business-agreement.ensure").allow
	not decision(other, "document.business-agreement.read").allow
}

test_customer_token_denied if {
	not decision(customer, "document.business-agreement.ensure").allow
}
