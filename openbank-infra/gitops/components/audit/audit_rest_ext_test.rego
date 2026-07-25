# SPDX-License-Identifier: Apache-2.0
# Unit tests for audit_rest_ext.rego (issue #1797).
#
# Run EXPLICITLY by file (the sibling *-opa-bundle.yaml is not valid rego, and `opa test <dir>`
# would try to load it):
#   opa test audit_rest_ext.rego audit_rest_ext_test.rego
#
# These tests exercise the audit extension rule in isolation via allowed_reasons; the base
# rest.rego allow head is verified in openbank-libs/governance/policies/rest_test.rego.

package openbank.rest

import rego.v1

auditor := {"type": "HUMAN", "id": "u-aud", "roles": ["ROLE_AUDITOR"]}

admin := {"type": "HUMAN", "id": "u-admin", "roles": ["ROLE_ADMIN"]}

compliance := {"type": "HUMAN", "id": "u-comp", "roles": ["ROLE_COMPLIANCE"]}

operator := {"type": "HUMAN", "id": "u-op", "roles": ["ROLE_OPERATOR"]}

# An M2M identity — classified HUMAN — that even carries an oversight role (ROLE_AUDITOR) must
# STILL be kept out by the service-account exclusion; that is what makes the exclusion load-bearing.
service_m2m := {"type": "HUMAN", "id": "service-account-openbank-ledger", "roles": ["ROLE_AUDITOR"]}

# --- audit.read: auditor / admin / compliance allowed ---

test_auditor_reads_entries if {
	"auditor-audit-oversight-read" in allowed_reasons with input as {"principal": auditor, "action": "audit.read"}
}

test_compliance_reads_entries if {
	"auditor-audit-oversight-read" in allowed_reasons with input as {"principal": compliance, "action": "audit.read"}
}

# --- audit.verify: auditor / admin / compliance allowed (verb outside base read/list set) ---

test_auditor_verifies_integrity if {
	"auditor-audit-oversight-read" in allowed_reasons with input as {"principal": auditor, "action": "audit.verify"}
}

test_admin_verifies_integrity if {
	"auditor-audit-oversight-read" in allowed_reasons with input as {"principal": admin, "action": "audit.verify"}
}

# --- operator NOT granted by this rule (endpoint @RolesAllowed omits OPERATOR); this ext rule
#     does not fire for a bare operator. (Base rest.rego may grant audit.read to OPERATOR, but
#     that head is not loaded in this isolated test — here we assert the ext rule's own scope.) ---

test_operator_not_in_ext_rule if {
	count(allowed_reasons) == 0 with input as {"principal": operator, "action": "audit.verify"}
}

# The M2M identity carries ROLE_OPERATOR; even if it were an auditor the exclusion must keep it
# out of the audit-oversight surface — regression guard for the service-account exclusion.
test_service_m2m_cannot_read_audit if {
	count(allowed_reasons) == 0 with input as {"principal": service_m2m, "action": "audit.read"}
}

# --- an unauthenticated/anonymous principal is denied everything ---

test_anonymous_denied if {
	count(allowed_reasons) == 0 with input as {"principal": {"type": "ANONYMOUS", "id": "anon", "roles": []}, "action": "audit.read"}
}
