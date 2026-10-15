# SPDX-License-Identifier: Apache-2.0
# Unit tests for product_catalog_rest_ext.rego (issue #12823).
#
# Run EXPLICITLY by file (the sibling *-opa-bundle.yaml is not valid rego):
#   opa test openbank-libs/governance/policies/rest.rego \
#            openbank-infra/gitops/components/accounts/product_catalog_rest_ext.rego \
#            openbank-infra/gitops/components/accounts/product_catalog_rest_ext_test.rego

package openbank.rest_test

import rego.v1

import data.openbank.rest

human(id, roles) := {"type": "HUMAN", "id": id, "roles": roles, "attributes": {}}

decide(principal, action) := d if {
	d := rest.allow with input as {
		"principal": principal,
		"action": action,
		"resource": {"id": "p1"},
	}
}

test_m2m_api_caller_reads_products if {
	decide(human("service-account-openbank-account", ["ROLE_API"]), "catalog.read").allow
	decide(human("service-account-openbank-lending", ["ROLE_API"]), "catalog.list").allow
}

test_viewer_reads if {
	decide(human("vera", ["ROLE_VIEWER"]), "catalog.read").allow
}

test_operator_reads_via_base if {
	decide(human("olga", ["ROLE_OPERATOR"]), "catalog.read").allow
}

test_synthetic_journey_reads if {
	decide(human("service-account-openbank-synthetic-catalog-read", ["CATALOG_SCOPE_READ"]), "catalog.list").allow
}

test_scope_read_alone_is_not_a_general_grant if {
	not decide(human("service-account-openbank-other", ["CATALOG_SCOPE_READ"]), "catalog.read").allow
}

test_anonymous_denied if {
	not rest.allow.allow with input as {
		"principal": {"type": "ANONYMOUS", "id": "anonymous", "roles": []},
		"action": "catalog.read",
	}
}

test_customer_denied if {
	not decide(human("cust", ["ROLE_CUSTOMER"]), "catalog.read").allow
}

test_staff_authors_and_publishes if {
	decide(human("olga", ["ROLE_OPERATOR"]), "catalog.author").allow
	decide(human("adam", ["ROLE_ADMIN"]), "catalog.create").allow
	decide(human("ann", ["CATALOG_SCOPE_AUTHOR"]), "catalog.update").allow
	decide(human("pete", ["CATALOG_SCOPE_PUBLISH"]), "catalog.publish").allow
}

test_author_scope_cannot_publish if {
	not decide(human("ann", ["CATALOG_SCOPE_AUTHOR"]), "catalog.publish").allow
}

test_api_caller_cannot_write if {
	not decide(human("service-account-openbank-account", ["ROLE_API"]), "catalog.update").allow
	not decide(human("vera", ["ROLE_VIEWER"]), "catalog.publish").allow
}

test_service_account_with_operator_cannot_write if {
	not decide(human("service-account-openbank-edge", ["ROLE_OPERATOR"]), "catalog.author").allow
	not decide(human("service-account-openbank-edge", ["ROLE_OPERATOR"]), "catalog.publish").allow
}

test_ai_agent_cannot_write if {
	not rest.allow.allow with input as {
		"principal": {"type": "AI_AGENT", "id": "agent:copilot", "roles": ["ROLE_OPERATOR"]},
		"action": "catalog.publish",
		"resource": {"id": "p1"},
	}
}
