# SPDX-License-Identifier: Apache-2.0
# Run: opa test openbank-libs/governance/policies/rest.rego \
#   openbank-infra/gitops/components/party/party_rest_ext.rego \
#   openbank-infra/gitops/components/party/party_rest_ext_test.rego
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

resource := {"type": "party", "id": "22222222-2222-4222-8222-222222222222"}

decision(principal, action) := d if {
    d := rest.allow with input as {
        "principal": principal,
        "action": action,
        "resource": resource,
        "attributes": {},
    } with data.rules as rules_mock
}

proof := "party.guaranteeIdentity.verify"
graph := {"type": "HUMAN", "id": "service-account-openbank-lending-graph", "roles": ["ROLE_LENDING_GRAPH_PROOF"]}

test_dedicated_graph_client_gets_only_identity_check if {
    decision(graph, proof).allow == true
    not decision(graph, "party.update").allow
}

test_shared_backend_cannot_check_guarantor_identity if {
    shared := {"type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_API"]}
    not decision(shared, proof).allow
}

test_staff_and_edge_cannot_check_guarantor_identity if {
    staff := {"type": "HUMAN", "id": "staff", "roles": ["ROLE_OPERATOR", "ROLE_ADMIN"]}
    edge := {"type": "HUMAN", "id": "service-account-openbank-edge", "roles": ["ROLE_OPERATOR"]}
    not decision(staff, proof).allow
    not decision(edge, proof).allow
}

test_exact_principal_without_narrow_role_is_denied if {
    no_role := {"type": "HUMAN", "id": "service-account-openbank-lending-graph", "roles": ["ROLE_API"]}
    not decision(no_role, proof).allow
}

test_existing_edge_consent_grant_survives if {
    edge := {"type": "HUMAN", "id": "service-account-openbank-edge", "roles": ["ROLE_OPERATOR"]}
    decision(edge, "party.consent.update").allow == true
}
