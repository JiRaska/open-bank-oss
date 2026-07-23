# SPDX-License-Identifier: Apache-2.0
# Unit tests for statement_rest_ext.rego (issue #1797).
#
# Run EXPLICITLY by file (the sibling *-opa-bundle.yaml is not valid rego, and `opa test <dir>`
# would try to load it):
#   opa test statement_rest_ext.rego statement_rest_ext_test.rego
#
# These tests exercise the statement extension rules in isolation via allowed_reasons; the base
# rest.rego allow head is verified in openbank-libs/governance/policies/rest_test.rego.

package openbank.rest

import rego.v1

operator := {"type": "HUMAN", "id": "u-op", "roles": ["ROLE_OPERATOR"]}

admin := {"type": "HUMAN", "id": "u-admin", "roles": ["ROLE_ADMIN"]}

viewer := {"type": "HUMAN", "id": "u-view", "roles": ["ROLE_VIEWER"]}

auditor := {"type": "HUMAN", "id": "u-aud", "roles": ["ROLE_AUDITOR"]}

# The customer-edge M2M identity — classified HUMAN, carries ROLE_OPERATOR (see caller audit).
edge_m2m := {"type": "HUMAN", "id": "service-account-openbank-edge", "roles": ["ROLE_OPERATOR"]}

# --- close-run telemetry reads: viewer/auditor allowed (the gap base rest.rego leaves) ---

test_viewer_reads_close_run_list if {
	"statement-close-run-telemetry-read" in allowed_reasons with input as {"principal": viewer, "action": "statement.close-run.list"}
}

test_auditor_reads_close_run_read if {
	"statement-close-run-telemetry-read" in allowed_reasons with input as {"principal": auditor, "action": "statement.close-run.read"}
}

test_operator_reads_close_run_list if {
	"statement-close-run-telemetry-read" in allowed_reasons with input as {"principal": operator, "action": "statement.close-run.list"}
}

# --- close-run manual trigger: operator/admin allowed, viewer denied, edge M2M denied ---

test_operator_triggers_close_run if {
	"operator-statement-close-run-trigger" in allowed_reasons with input as {"principal": operator, "action": "statement.close-run.trigger"}
}

test_admin_triggers_close_run if {
	"operator-statement-close-run-trigger" in allowed_reasons with input as {"principal": admin, "action": "statement.close-run.trigger"}
}

test_viewer_cannot_trigger_close_run if {
	count(allowed_reasons) == 0 with input as {"principal": viewer, "action": "statement.close-run.trigger"}
}

# The edge M2M carries ROLE_OPERATOR; the service-account exclusion must keep it out of the
# operator-only trigger (it never calls this endpoint) — regression guard for the exclusion.
test_edge_m2m_cannot_trigger_close_run if {
	count(allowed_reasons) == 0 with input as {"principal": edge_m2m, "action": "statement.close-run.trigger"}
}

# --- statement.close: operator/admin allowed, edge M2M denied ---

test_operator_closes_month if {
	"operator-statement-close" in allowed_reasons with input as {"principal": operator, "action": "statement.close"}
}

test_edge_m2m_cannot_close_month if {
	count(allowed_reasons) == 0 with input as {"principal": edge_m2m, "action": "statement.close"}
}

# --- statement.export: operator/admin allowed, viewer denied (conservative), edge M2M denied ---

test_admin_exports if {
	"operator-statement-export" in allowed_reasons with input as {"principal": admin, "action": "statement.export"}
}

test_viewer_cannot_export if {
	count(allowed_reasons) == 0 with input as {"principal": viewer, "action": "statement.export"}
}

test_edge_m2m_cannot_export if {
	count(allowed_reasons) == 0 with input as {"principal": edge_m2m, "action": "statement.export"}
}

# --- an unauthenticated/anonymous principal is denied everything ---

test_anonymous_denied if {
	count(allowed_reasons) == 0 with input as {"principal": {"type": "ANONYMOUS", "id": "anon", "roles": []}, "action": "statement.close-run.list"}
}
