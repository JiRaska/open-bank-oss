# SPDX-License-Identifier: Apache-2.0
# Unit tests for consent_rest_ext.rego (ADR-0206).
#
# Run EXPLICITLY by file (the sibling *-opa-bundle.yaml is not valid rego, and `opa test <dir>`
# would try to load it as data and die with a merge error):
#   opa test openbank-libs/governance/policies/rest.rego \
#            openbank-infra/gitops/components/consent/consent_rest_ext.rego \
#            openbank-infra/gitops/components/consent/consent_rest_ext_test.rego
#
# Do NOT add another service's *_rest_ext.rego to the same invocation: they all extend the
# same package and would cross-contaminate allowed_reasons (issue #1797 follow-up 2).

package openbank.rest

import rego.v1

operator := {"type": "HUMAN", "id": "u-op", "roles": ["ROLE_OPERATOR"]}

admin := {"type": "HUMAN", "id": "u-admin", "roles": ["ROLE_ADMIN"]}

viewer := {"type": "HUMAN", "id": "u-view", "roles": ["ROLE_VIEWER"]}

# The shared M2M identity — classified HUMAN, carries ROLE_OPERATOR (openbank-realm.json). This
# is the exact regression consent_rest_ext.rego had until ADR-0206 D5's fix: operator-consent-write
# was role-only, so this identity could call ANY consent.* action unconditionally despite the
# rule's own header comment claiming consent.grant/consent.revoke were M2M-unreachable.
services_m2m := {"type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_OPERATOR"]}

marketing_resource := {"type": "consent", "id": "party-service:marketing-comms"}

other_resource := {"type": "consent", "id": "some-other-tpp"}

# --- operators/admins: unrestricted, as before ---

test_operator_grants_consent if {
	"operator-consent-write" in allowed_reasons with input as {"principal": operator, "action": "consent.grant"}
}

test_admin_revokes_consent if {
	"operator-consent-write" in allowed_reasons with input as {"principal": admin, "action": "consent.revoke"}
}

test_viewer_cannot_grant_consent if {
	count(allowed_reasons) == 0 with input as {"principal": viewer, "action": "consent.grant"}
}

# --- the regression: shared M2M identity must NOT ride operator-consent-write ---

test_services_m2m_cannot_grant_via_operator_rule if {
	not "operator-consent-write" in allowed_reasons with input as {"principal": services_m2m, "action": "consent.grant"}
}

test_services_m2m_cannot_revoke_via_operator_rule if {
	not "operator-consent-write" in allowed_reasons
		with input as {"principal": services_m2m, "action": "consent.revoke"}
}

test_services_m2m_cannot_grant_arbitrary_grantee if {
	count(allowed_reasons) == 0 with input as {
		"principal": services_m2m,
		"action": "consent.grant",
		"resource": other_resource,
	}
}

test_services_m2m_cannot_revoke_arbitrary_grantee if {
	count(allowed_reasons) == 0 with input as {
		"principal": services_m2m,
		"action": "consent.revoke",
		"resource": other_resource,
	}
}

test_services_m2m_cannot_grant_with_no_resource if {
	count(allowed_reasons) == 0 with input as {"principal": services_m2m, "action": "consent.grant"}
}

# --- the sanctioned exception: scoped to the marketing grantee only (ADR-0206 D2) ---

test_services_m2m_grants_marketing_consent if {
	"service-consent-m2m-marketing" in allowed_reasons with input as {
		"principal": services_m2m,
		"action": "consent.grant",
		"resource": marketing_resource,
	}
}

test_services_m2m_revokes_marketing_consent if {
	"service-consent-m2m-marketing" in allowed_reasons with input as {
		"principal": services_m2m,
		"action": "consent.revoke",
		"resource": marketing_resource,
	}
}

# --- unaffected M2M actions (unchanged by this fix) ---

test_services_m2m_reads_consent if {
	"service-consent-m2m" in allowed_reasons with input as {"principal": services_m2m, "action": "consent.read"}
}

test_services_m2m_activates_consent if {
	"service-consent-m2m" in allowed_reasons with input as {"principal": services_m2m, "action": "consent.activate"}
}

# --- operators are still unrestricted for every other consent.* action ---

test_operator_lists_consents if {
	"operator-consent-write" in allowed_reasons with input as {"principal": operator, "action": "consent.list"}
}

# --- 2026-08-05 (#3734): the edge client (customer-facing M2M, ROLE_OPERATOR) ---

edge := {"type": "HUMAN", "id": "service-account-openbank-edge", "roles": ["ROLE_OPERATOR"]}

# After the prefix widening, the edge identity no longer rides operator-consent-write on any
# consent.* write.
test_edge_cannot_grant_via_operator_rule if {
	not "operator-consent-write" in allowed_reasons with input as {"principal": edge, "action": "consent.grant"}
}

test_edge_cannot_activate_via_operator_rule if {
	not "operator-consent-write" in allowed_reasons with input as {"principal": edge, "action": "consent.activate"}
}

test_edge_denied_entirely_on_grant if {
	count(allowed_reasons) == 0 with input as {
		"principal": edge,
		"action": "consent.grant",
		"resource": marketing_resource,
	}
}

# The edge's legitimate consent access — {consent.list, consent.revoke} — is BASE
# edge-service-consent's grant, not this ext's. Pinned here so a future base regression is
# caught on the consent bundle's own test suite.
test_edge_revokes_via_base_edge_service_consent if {
	"edge-service-consent" in allowed_reasons with input as {"principal": edge, "action": "consent.revoke"}
}

test_edge_lists_via_base_edge_service_consent if {
	"edge-service-consent" in allowed_reasons with input as {"principal": edge, "action": "consent.list"}
}

# ...but the edge must not gain the other consent actions through any rule in this bundle.
test_edge_denied_entirely_on_consent_validate if {
	count(allowed_reasons) == 0 with input as {
		"principal": edge,
		"action": "consent.validate",
		"resource": marketing_resource,
	}
}
