# SPDX-License-Identifier: Apache-2.0
# Unit tests for aml_rest_ext.rego (issue #1797).
#
# Run EXPLICITLY by file, WITH the base policy (the pair the sidecar mounts):
#   opa test openbank-libs/governance/policies/rest.rego \
#            openbank-infra/gitops/components/aml/aml_rest_ext.rego \
#            openbank-infra/gitops/components/aml/aml_rest_ext_test.rego

package openbank.rest

import rego.v1

viewer := {"type": "HUMAN", "id": "u-view", "roles": ["ROLE_VIEWER"]}

compliance := {"type": "HUMAN", "id": "u-comp", "roles": ["ROLE_COMPLIANCE"]}

operator := {"type": "HUMAN", "id": "u-op", "roles": ["ROLE_OPERATOR"]}

admin := {"type": "HUMAN", "id": "u-admin", "roles": ["ROLE_ADMIN"]}

# An M2M identity — classified HUMAN, carrying an oversight role — must still be excluded.
service_m2m := {"type": "HUMAN", "id": "service-account-openbank-onboarding", "roles": ["ROLE_COMPLIANCE"]}

# --- case oversight read/list: viewer/compliance allowed (the gap base leaves) ---

test_viewer_reads_case if {
	"aml-case-oversight-read" in allowed_reasons with input as {"principal": viewer, "action": "amlCase.read"}
}

test_compliance_lists_cases if {
	"aml-case-oversight-read" in allowed_reasons with input as {"principal": compliance, "action": "amlCase.list"}
}

test_operator_reads_case if {
	"aml-case-oversight-read" in allowed_reasons with input as {"principal": operator, "action": "amlCase.read"}
}

# --- updateDecision: operator/admin/compliance allowed, viewer denied ---

test_operator_updates_decision if {
	"operator-aml-case-update-decision" in allowed_reasons with input as {"principal": operator, "action": "amlCase.updateDecision"}
}

test_compliance_updates_decision if {
	"operator-aml-case-update-decision" in allowed_reasons with input as {"principal": compliance, "action": "amlCase.updateDecision"}
}

test_viewer_cannot_update_decision if {
	count(allowed_reasons) == 0 with input as {"principal": viewer, "action": "amlCase.updateDecision"}
}

# --- admin allowed both families ---

test_admin_updates_decision if {
	"operator-aml-case-update-decision" in allowed_reasons with input as {"principal": admin, "action": "amlCase.updateDecision"}
}

# The M2M identity carries ROLE_COMPLIANCE; the service-account exclusion must keep it out of the
# aml-specific rules. Note the base policy deliberately still grants the READ via
# compliance-read-any ("how real M2M callers fetch cross-service data" — rest.rego's shared_m2m
# guard leaves reads untouched), so the invariant is asserted on the EXTENSION's reasons, not on
# the total count. Asserting count(allowed_reasons) == 0 here contradicted the base design and
# only ever passed under the header's old base-less invocation.
test_service_m2m_excluded_from_human_rules if {
	reasons := allowed_reasons with input as {"principal": service_m2m, "action": "amlCase.read"}
	not reasons["aml-case-oversight-read"]
}

test_service_m2m_excluded_from_aml_write if {
	reasons := allowed_reasons with input as {"principal": service_m2m, "action": "amlCase.updateDecision"}
	not reasons["operator-aml-case-update-decision"]
	count(reasons) == 0
}

# --- anonymous denied ---

test_anonymous_denied if {
	count(allowed_reasons) == 0 with input as {"principal": {"type": "ANONYMOUS", "id": "anon", "roles": []}, "action": "amlCase.list"}
}
