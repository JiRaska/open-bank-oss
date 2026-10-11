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

auditor := {"service_account": false, "type": "HUMAN", "id": "u-aud", "roles": ["ROLE_AUDITOR"]}

admin := {"service_account": false, "type": "HUMAN", "id": "u-admin", "roles": ["ROLE_ADMIN"]}

compliance := {"service_account": false, "type": "HUMAN", "id": "u-comp", "roles": ["ROLE_COMPLIANCE"]}

operator := {"service_account": false, "type": "HUMAN", "id": "u-op", "roles": ["ROLE_OPERATOR"]}

# An M2M identity — classified HUMAN — that even carries an oversight role (ROLE_AUDITOR) must
# STILL be kept out by the service-account exclusion; that is what makes the exclusion load-bearing.
service_m2m := {"service_account": true, "type": "HUMAN", "id": "service-account-openbank-ledger", "roles": ["ROLE_AUDITOR"]}

# --- audit.trail.inspect: auditor / admin / compliance allowed ---

test_auditor_reads_entries if {
	"auditor-audit-oversight-read" in allowed_reasons with input as {"principal": auditor, "action": "audit.trail.inspect"}
}

test_compliance_reads_entries if {
	"auditor-audit-oversight-read" in allowed_reasons with input as {"principal": compliance, "action": "audit.trail.inspect"}
}

# --- audit.verify: auditor / admin / compliance allowed (verb outside base read/list set) ---

test_auditor_verifies_integrity if {
	"auditor-audit-oversight-read" in allowed_reasons with input as {"principal": auditor, "action": "audit.verify"}
}

test_admin_verifies_integrity if {
	"auditor-audit-oversight-read" in allowed_reasons with input as {"principal": admin, "action": "audit.verify"}
}

# --- operator NOT granted by this rule (endpoint @RolesAllowed omits OPERATOR); this ext rule
#     does not fire for a bare operator. Base rest.rego grants nothing here either, since
#     neither verb is in its {list, read} set — that end-to-end deny is pinned in rest_test.rego. ---

test_operator_not_in_ext_rule if {
	count(allowed_reasons) == 0 with input as {"principal": operator, "action": "audit.verify"}
}

# The M2M identity carries ROLE_OPERATOR; even if it were an auditor the exclusion must keep it
# out of the audit-oversight surface — regression guard for the service-account exclusion.
test_service_m2m_cannot_read_audit if {
	count(allowed_reasons) == 0 with input as {"principal": service_m2m, "action": "audit.trail.inspect"}
}

# --- an unauthenticated/anonymous principal is denied everything ---

# The service-account exclusion also holds for the compliance role (operator console roles a
# realm could hand a client).
test_service_m2m_compliance_cannot_inspect_trail if {
	count(allowed_reasons) == 0 with input as {"principal": {"service_account": true, "type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_COMPLIANCE"]}, "action": "audit.trail.inspect"}
}

test_anonymous_denied if {
	count(allowed_reasons) == 0 with input as {"principal": {"service_account": false, "type": "ANONYMOUS", "id": "anon", "roles": []}, "action": "audit.trail.inspect"}
}

# --- audit.evidence.reconstruct (ADR-0214 D3, #11900) ---

credit_risk := {"service_account": false, "type": "HUMAN", "id": "u-risk", "roles": ["ROLE_CREDIT_RISK"]}

lending_m2m := {"service_account": true, "type": "HUMAN", "id": "service-account-openbank-lending", "roles": ["ROLE_COMPLIANCE", "ROLE_CREDIT_RISK"]}

test_credit_risk_reads_evidence_bundle if {
	"evidence-bundle-read" in allowed_reasons with input as {"principal": credit_risk, "action": "audit.evidence.reconstruct"}
}

test_compliance_reads_evidence_bundle if {
	"evidence-bundle-read" in allowed_reasons with input as {"principal": compliance, "action": "audit.evidence.reconstruct"}
}

test_credit_risk_does_not_gain_the_general_trail if {
	not "auditor-audit-oversight-read" in allowed_reasons with input as {"principal": credit_risk, "action": "audit.trail.inspect"}
	not "evidence-bundle-read" in allowed_reasons with input as {"principal": credit_risk, "action": "audit.trail.inspect"}
}

test_operator_cannot_read_evidence_bundle if {
	not "evidence-bundle-read" in allowed_reasons with input as {"principal": operator, "action": "audit.evidence.reconstruct"}
}

test_lending_service_account_cannot_read_evidence_even_with_roles if {
	not "evidence-bundle-read" in allowed_reasons with input as {"principal": lending_m2m, "action": "audit.evidence.reconstruct"}
}
