# SPDX-License-Identifier: Apache-2.0
# Unit tests for tpp_registry_rest_ext.rego (issue #1797).
#
# Run EXPLICITLY by file (the sibling *-opa-bundle.yaml is not valid rego, and `opa test <dir>`
# would try to load it):
#   opa test tpp_registry_rest_ext.rego tpp_registry_rest_ext_test.rego
#
# These tests exercise the tpp-registry extension rule in isolation via allowed_reasons; the base
# rest.rego allow head is verified in openbank-libs/governance/policies/rest_test.rego.

package openbank.rest

import rego.v1

operator := {"type": "HUMAN", "id": "u-op", "roles": ["ROLE_OPERATOR"]}

admin := {"type": "HUMAN", "id": "u-admin", "roles": ["ROLE_ADMIN"]}

viewer := {"type": "HUMAN", "id": "u-view", "roles": ["ROLE_VIEWER"]}

# An M2M identity — classified HUMAN, carries ROLE_OPERATOR — must be kept out by the exclusion.
service_m2m := {"type": "HUMAN", "id": "service-account-openbank-psd2", "roles": ["ROLE_OPERATOR"]}

# --- tppRegistry.blacklist: operator/admin allowed ---

test_operator_blacklists if {
	"operator-tpp-registry-blacklist" in allowed_reasons with input as {"principal": operator, "action": "tppRegistry.blacklist"}
}

test_admin_blacklists if {
	"operator-tpp-registry-blacklist" in allowed_reasons with input as {"principal": admin, "action": "tppRegistry.blacklist"}
}

# --- viewer denied (read-only persona, not a trust-and-safety actor) ---

test_viewer_cannot_blacklist if {
	count(allowed_reasons) == 0 with input as {"principal": viewer, "action": "tppRegistry.blacklist"}
}

# The M2M identity carries ROLE_OPERATOR; the service-account exclusion must keep it out
# (psd2-service only reads the registry, it never blacklists) — regression guard for the exclusion.
test_service_m2m_cannot_blacklist if {
	count(allowed_reasons) == 0 with input as {"principal": service_m2m, "action": "tppRegistry.blacklist"}
}

# --- an unauthenticated/anonymous principal is denied everything ---

test_anonymous_denied if {
	count(allowed_reasons) == 0 with input as {"principal": {"type": "ANONYMOUS", "id": "anon", "roles": []}, "action": "tppRegistry.blacklist"}
}
