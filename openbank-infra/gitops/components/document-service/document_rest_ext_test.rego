# SPDX-License-Identifier: Apache-2.0
#
#   opa test openbank-libs/governance/policies/rest.rego \
#     openbank-infra/gitops/components/document-service/document_rest_ext.rego \
#     openbank-infra/gitops/components/document-service/document_rest_ext_test.rego

package openbank.rest_test

import rego.v1

import data.openbank.rest

disclosure_exporter := {
	"type": "HUMAN",
	"id": "service-account-openbank-delegation-disclosure",
	"roles": ["ROLE_API"],
}

shared_backend := {
	"type": "HUMAN",
	"id": "service-account-openbank-services",
	"roles": ["ROLE_OPERATOR", "ROLE_API"],
}

customer_edge := {
	"type": "HUMAN",
	"id": "service-account-openbank-edge",
	"roles": ["ROLE_OPERATOR"],
}

operator := {"type": "HUMAN", "id": "u-operator", "roles": ["ROLE_OPERATOR"]}

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
