# SPDX-License-Identifier: Apache-2.0
# Unit tests for party_rest_ext.rego (#10486 batch 3).
#
# Run EXPLICITLY by file, WITH the base policy (the pair the sidecar mounts):
#   opa test openbank-libs/governance/policies/rest.rego \
#            openbank-infra/gitops/components/party/party_rest_ext.rego \
#            openbank-infra/gitops/components/party/party_rest_ext_test.rego

package openbank.rest

import rego.v1

kyb := {"type": "HUMAN", "id": "service-account-openbank-kyb", "roles": ["ROLE_API"]}

other_api_sa := {"type": "HUMAN", "id": "service-account-openbank-mcp-service", "roles": ["ROLE_API"]}

shared_api_only := {"type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_API"]}

kyc_analyst := {"type": "HUMAN", "id": "u-kyc", "roles": ["ROLE_KYC"]}

viewer := {"type": "HUMAN", "id": "u-view", "roles": ["ROLE_VIEWER"]}

# --- must-ALLOW: kyb's own principal, exactly its two actions ---

test_kyb_creates_entity_party if {
	allow.allow with input as {"principal": kyb, "action": "party.create"}
}

test_kyb_grants_mandate if {
	allow.allow with input as {"principal": kyb, "action": "party.mandate.grant"}
}

# --- must-DENY: every other party write for kyb ---

test_kyb_granted_no_other_party_write if {
	every action in {"party.update", "party.merge", "party.consent.update", "party.mandate.revoke", "party.approval.decide", "party:resolve"} {
		not allow.allow with input as {"principal": kyb, "action": action}
	}
}

# --- must-DENY: another ROLE_API service account, and the shared client once it is ROLE_API only ---

test_other_role_api_service_account_denied if {
	every action in {"party.create", "party.mandate.grant"} {
		not allow.allow with input as {"principal": other_api_sa, "action": action}
	}
}

test_shared_client_with_role_api_only_denied if {
	every action in {"party.create", "party.mandate.grant"} {
		not allow.allow with input as {"principal": shared_api_only, "action": action}
	}
}

# --- staff keep working ---

test_kyc_analyst_creates_party if {
	"kyc-party-create" in allowed_reasons with input as {"principal": kyc_analyst, "action": "party.create"}
}

test_kyc_analyst_rule_does_not_reach_other_writes if {
	reasons := allowed_reasons with input as {"principal": kyc_analyst, "action": "party.merge"}
	not reasons["kyc-party-create"]
}

test_viewer_may_not_create_party if {
	not allow.allow with input as {"principal": viewer, "action": "party.create"}
}

# --- the edge rule is unchanged ---

test_edge_keeps_consent_update if {
	"service-edge-party-m2m" in allowed_reasons with input as {"principal": {"type": "HUMAN", "id": "service-account-openbank-edge", "roles": ["ROLE_API"]}, "action": "party.consent.update"}
}

# The lending proof identity is narrower than the Party operator and shared-service roles.
lending_graph := {"type": "HUMAN", "id": "service-account-openbank-lending-graph", "roles": ["ROLE_LENDING_GRAPH_PROOF"]}

test_lending_graph_can_verify_guarantor_identity if {
    "service-lending-guarantor-identity" in allowed_reasons with input as {"principal": lending_graph, "action": "party.guaranteeIdentity.verify"}
}

test_lending_graph_cannot_use_other_party_writes if {
    not allow.allow with input as {"principal": lending_graph, "action": "party.update"}
}

test_shared_service_cannot_verify_guarantor_identity if {
    not allow.allow with input as {"principal": shared_api_only, "action": "party.guaranteeIdentity.verify"}
}

test_staff_and_edge_cannot_verify_guarantor_identity if {
    staff := {"type": "HUMAN", "id": "staff", "roles": ["ROLE_OPERATOR", "ROLE_ADMIN"]}
    edge := {"type": "HUMAN", "id": "service-account-openbank-edge", "roles": ["ROLE_OPERATOR"]}
    not allow.allow with input as {"principal": staff, "action": "party.guaranteeIdentity.verify"}
    not allow.allow with input as {"principal": edge, "action": "party.guaranteeIdentity.verify"}
}

test_lending_graph_without_narrow_role_is_denied if {
    no_role := {"type": "HUMAN", "id": "service-account-openbank-lending-graph", "roles": ["ROLE_API"]}
    not allow.allow with input as {"principal": no_role, "action": "party.guaranteeIdentity.verify"}
}
