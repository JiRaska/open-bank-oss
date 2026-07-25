# SPDX-License-Identifier: Apache-2.0
# Unit tests for kyc_rest_ext.rego (issue #1797).
#
# Run EXPLICITLY by file:
#   opa test kyc_rest_ext.rego kyc_rest_ext_test.rego

package openbank.rest

import rego.v1

admin := {"type": "HUMAN", "id": "u-admin", "roles": ["ROLE_ADMIN"]}

opener := {"type": "HUMAN", "id": "u-open", "roles": ["ROLE_KYC_OPENER"]}

reviewer := {"type": "HUMAN", "id": "u-rev", "roles": ["ROLE_KYC_REVIEWER"]}

compliance := {"type": "HUMAN", "id": "u-comp", "roles": ["ROLE_COMPLIANCE"]}

operator := {"type": "HUMAN", "id": "u-op", "roles": ["ROLE_OPERATOR"]}

# An M2M identity carrying a KYC role must still be excluded.
service_m2m := {"type": "HUMAN", "id": "service-account-openbank-onboarding", "roles": ["ROLE_KYC_REVIEWER"]}

# --- updateCheck: admin / opener allowed; reviewer NOT (not in the role set) ---

test_admin_updates_check if {
	"operator-kyc-case-update-check" in allowed_reasons with input as {"principal": admin, "action": "kycCase.updateCheck"}
}

test_opener_updates_check if {
	"operator-kyc-case-update-check" in allowed_reasons with input as {"principal": opener, "action": "kycCase.updateCheck"}
}

test_reviewer_cannot_update_check if {
	count(allowed_reasons) == 0 with input as {"principal": reviewer, "action": "kycCase.updateCheck"}
}

# --- pepRescreen: operator / compliance / opener allowed ---

test_compliance_pep_rescreens if {
	"operator-kyc-case-pep-rescreen" in allowed_reasons with input as {"principal": compliance, "action": "kycCase.pepRescreen"}
}

test_operator_pep_rescreens if {
	"operator-kyc-case-pep-rescreen" in allowed_reasons with input as {"principal": operator, "action": "kycCase.pepRescreen"}
}

# --- approve / reject: reviewer / operator / admin allowed; opener NOT (not a reviewer) ---

test_reviewer_approves if {
	"operator-kyc-case-review-disposition" in allowed_reasons with input as {"principal": reviewer, "action": "kyc.case.approve"}
}

test_operator_rejects if {
	"operator-kyc-case-review-disposition" in allowed_reasons with input as {"principal": operator, "action": "kyc.case.reject"}
}

test_opener_cannot_approve if {
	count(allowed_reasons) == 0 with input as {"principal": opener, "action": "kyc.case.approve"}
}

# The M2M identity carries ROLE_KYC_REVIEWER; the service-account exclusion must keep it out
# of the review disposition — regression guard for the exclusion.
test_service_m2m_cannot_approve if {
	count(allowed_reasons) == 0 with input as {"principal": service_m2m, "action": "kyc.case.approve"}
}

# --- anonymous denied everything ---

test_anonymous_denied if {
	count(allowed_reasons) == 0 with input as {"principal": {"type": "ANONYMOUS", "id": "anon", "roles": []}, "action": "kyc.case.approve"}
}
