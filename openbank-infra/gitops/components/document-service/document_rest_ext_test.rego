# SPDX-License-Identifier: Apache-2.0
# Unit tests for document_rest_ext.rego (external disclosure export + business onboarding agreement grant).
#
# Run EXPLICITLY by file (the sibling *-opa-bundle.yaml is not valid rego):
#   opa test openbank-libs/governance/policies/rest.rego \
#     openbank-infra/gitops/components/document-service/document_rest_ext.rego \
#     openbank-infra/gitops/components/document-service/document_rest_ext_test.rego

package openbank.rest_test

import rego.v1

import data.openbank.rest

disclosure_exporter := {
	"service_account": true,
	"type": "HUMAN",
	"id": "service-account-openbank-delegation-disclosure",
	"roles": ["ROLE_API"],
}

shared_backend := {
	"service_account": true,
	"type": "HUMAN",
	"id": "service-account-openbank-services",
	"roles": ["ROLE_OPERATOR", "ROLE_API"],
}

customer_edge := {
	"service_account": true,
	"type": "HUMAN",
	"id": "service-account-openbank-edge",
	"roles": ["ROLE_OPERATOR"],
}

operator := {"service_account": false, "type": "HUMAN", "id": "u-operator", "roles": ["ROLE_OPERATOR"]}

export_action := "document.disclosure.export"

test_allow_only_dedicated_disclosure_exporter if {
	rest.allow with input as {"principal": disclosure_exporter, "action": export_action}
}

test_allow_reason_names_dedicated_exporter if {
	"service-delegation-external-disclosure-export" in rest.allowed_reasons
		with input as {"principal": disclosure_exporter, "action": export_action}
}

test_deny_shared_backend_export if {
	not rest.allow with input as {"principal": shared_backend, "action": export_action}
}

test_deny_customer_edge_export if {
	not rest.allow with input as {"principal": customer_edge, "action": export_action}
}

test_deny_human_operator_export if {
	not rest.allow with input as {"principal": operator, "action": export_action}
}

test_veto_applies_to_every_other_subject if {
	rest.prohibited with input as {"principal": shared_backend, "action": export_action}
	rest.prohibited with input as {"principal": customer_edge, "action": export_action}
	rest.prohibited with input as {"principal": operator, "action": export_action}
}

rules_mock := {
	"authz": {"role_action_matrix": {}},
	"money_path_services": [],
	"money_path_action_prefixes": {},
	"four_eyes": {"verbs": [], "actions": []},
	"feature_flags": {"prohibited_flag_combinations": [], "money_path_flags": []},
	"shared_m2m_write_prohibition": {"reasons": []},
}

# The deployed realm gives the shared client ROLE_API only — the case this rule exists for.
services_m2m := {"service_account": true, "type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_API"]}

edge := {"service_account": true, "type": "HUMAN", "id": "service-account-openbank-edge", "roles": ["ROLE_OPERATOR"]}

customer := {"service_account": false, "type": "HUMAN", "id": "11111111-1111-4111-8111-111111111111", "roles": ["ROLE_CUSTOMER"]}

case_resource := {"service_account": false, "type": "document", "id": "22222222-2222-4222-8222-222222222222"}

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
	other := {"service_account": true, "type": "HUMAN", "id": "service-account-openbank-reporting", "roles": ["ROLE_API"]}
	not decision(other, "document.business-agreement.ensure").allow
	not decision(other, "document.business-agreement.read").allow
}

test_customer_token_denied if {
	not decision(customer, "document.business-agreement.ensure").allow
}
