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

# --- audit.evidence.reconstruct (ADR-0214 D3, #11900) ---

credit_risk := {"type": "HUMAN", "id": "u-risk", "roles": ["ROLE_CREDIT_RISK"]}

lending_m2m := {"type": "HUMAN", "id": "service-account-openbank-lending", "roles": ["ROLE_COMPLIANCE", "ROLE_CREDIT_RISK"]}

test_credit_risk_reads_evidence_bundle if {
	"evidence-bundle-read" in allowed_reasons with input as {"principal": credit_risk, "action": "audit.evidence.reconstruct"}
}

test_compliance_reads_evidence_bundle if {
	"evidence-bundle-read" in allowed_reasons with input as {"principal": compliance, "action": "audit.evidence.reconstruct"}
}

test_credit_risk_does_not_gain_the_general_trail if {
	not "auditor-audit-oversight-read" in allowed_reasons with input as {"principal": credit_risk, "action": "audit.read"}
	not "evidence-bundle-read" in allowed_reasons with input as {"principal": credit_risk, "action": "audit.read"}
}

test_operator_cannot_read_evidence_bundle if {
	not "evidence-bundle-read" in allowed_reasons with input as {"principal": operator, "action": "audit.evidence.reconstruct"}
}

test_lending_service_account_cannot_read_evidence_even_with_roles if {
	not "evidence-bundle-read" in allowed_reasons with input as {"principal": lending_m2m, "action": "audit.evidence.reconstruct"}
}
