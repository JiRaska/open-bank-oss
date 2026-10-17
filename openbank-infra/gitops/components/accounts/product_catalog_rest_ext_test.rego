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

human(id, roles) := {"type": "HUMAN", "id": id, "roles": roles, "attributes": {}, "service_account": false}

machine(id, roles) := object.union(human(id, roles), {
	"service_account": true,
	"client_id": trim_prefix(id, "service-account-"),
})

decide(principal, action) := d if {
	d := rest.allow with input as {
		"principal": principal,
		"action": action,
		"resource": {"id": "p1"},
	}
}

# Endpoint RBAC admitting ROLE_API is not an OPA grant. Preserve the shared policy's
# named caller + scope boundary instead of restoring the original blanket API intent.
test_api_role_alone_does_not_grant_catalog_reads if {
	not decide(machine("service-account-openbank-account", ["ROLE_API"]), "catalog.read").allow
	not decide(machine("service-account-openbank-lending", ["ROLE_API"]), "catalog.list").allow
	not decide(machine("service-account-openbank-services", ["ROLE_API", "CATALOG_SCOPE_READ"]), "catalog.read").allow
	not decide(machine("service-account-openbank-pension", ["ROLE_API"]), "catalog.read").allow
}

test_named_scoped_pension_read_keeps_shared_reason if {
	decision := decide(machine("service-account-openbank-pension", ["ROLE_API", "CATALOG_SCOPE_READ"]), "catalog.read")
	decision.allow
	decision.reason == "pension-catalog-m2m-read"
}

test_viewer_reads if {
	decide(human("vera", ["ROLE_VIEWER"]), "catalog.read").allow
}

test_machine_or_unknown_viewer_is_not_human_staff if {
	base := human("renamed-viewer", ["ROLE_VIEWER"])
	every actor in [
		object.union(base, {"service_account": true, "client_id": "openbank-other"}),
		object.union(base, {"service_account": null}),
		object.remove(base, ["service_account"]),
	] {
		not decide(actor, "catalog.read").allow
	}
}

test_operator_reads_via_base if {
	decide(human("olga", ["ROLE_OPERATOR"]), "catalog.read").allow
}

test_synthetic_journey_reads if {
	decide(machine("service-account-openbank-synthetic-catalog-read", ["CATALOG_SCOPE_READ"]), "catalog.list").allow
}

test_scope_read_alone_is_not_a_general_grant if {
	not decide(machine("service-account-openbank-other", ["CATALOG_SCOPE_READ"]), "catalog.read").allow
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
	not decide(machine("service-account-openbank-account", ["ROLE_API"]), "catalog.update").allow
	not decide(human("vera", ["ROLE_VIEWER"]), "catalog.publish").allow
}

test_service_account_with_operator_cannot_write if {
	not decide(machine("service-account-openbank-edge", ["ROLE_OPERATOR"]), "catalog.author").allow
	not decide(machine("service-account-openbank-edge", ["ROLE_OPERATOR"]), "catalog.publish").allow
}

test_ai_agent_cannot_write if {
	not rest.allow.allow with input as {
		"principal": {"type": "AI_AGENT", "id": "agent:copilot", "roles": ["ROLE_OPERATOR"]},
		"action": "catalog.publish",
		"resource": {"id": "p1"},
	}
}

# These drive the complete allow decision, including shared reasons and prohibitions.
test_named_synthetic_requires_verified_client if {
	base := machine("service-account-openbank-synthetic-catalog-read", ["CATALOG_SCOPE_READ"])
	every fields in [
		{"service_account": false},
		{"service_account": null},
		{"client_id": "openbank-other"},
		{"client_id": null},
	] {
		not decide(object.union(base, fields), "catalog.read").allow
	}
	not decide(object.remove(base, ["client_id"]), "catalog.read").allow
}

test_named_synthetic_absent_marker_retains_legacy_read if {
	legacy := object.remove(
		machine("service-account-openbank-synthetic-catalog-read", ["CATALOG_SCOPE_READ"]),
		["service_account", "client_id"],
	)
	decide(legacy, "catalog.read").allow
}

test_catalog_staff_rejects_renamed_machine_and_unknown_markers if {
	base := human("renamed-machine", ["CATALOG_SCOPE_AUTHOR", "CATALOG_SCOPE_PUBLISH"])
	every actor in [
		object.union(base, {"service_account": true, "client_id": "openbank-other"}),
		object.union(base, {"service_account": null}),
		object.remove(base, ["service_account"]),
	] {
		not decide(actor, "catalog.author").allow
		not decide(actor, "catalog.publish").allow
	}
}
