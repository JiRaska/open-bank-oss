# SPDX-License-Identifier: Apache-2.0
# Held to a known-POSITIVE and a known-NEGATIVE: every allow case has a matching deny.

package openbank.rest_test

import data.openbank.rest
import rego.v1

test_operator_may_create_a_snapshot if {
	rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "alice", "roles": ["ROLE_OPERATOR"]},
		"action": "risk.snapshot.create",
	}
}

test_admin_may_create_a_snapshot if {
	rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "root", "roles": ["ROLE_ADMIN"]},
		"action": "risk.snapshot.create",
	}
}

test_operator_may_read_a_snapshot if {
	rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "alice", "roles": ["ROLE_OPERATOR"]},
		"action": "risk.snapshot.read",
	}
}

# The shared backend service-account carries ROLE_OPERATOR in at least one realm; that must not
# be enough to trigger a run.
test_shared_service_account_is_denied_create if {
	not rest.allow with input as {
		"principal": {
			"type": "HUMAN",
			"id": "service-account-openbank-services",
			"roles": ["ROLE_OPERATOR", "ROLE_API"],
		},
		"action": "risk.snapshot.create",
	}
}

test_viewer_is_denied_create if {
	not rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "bob", "roles": ["ROLE_VIEWER"]},
		"action": "risk.snapshot.create",
	}
}

test_anonymous_is_denied if {
	not rest.allow with input as {
		"principal": {"type": "ANONYMOUS", "id": "", "roles": []},
		"action": "risk.snapshot.read",
	}
}

test_operator_may_upload_a_curve_set if {
	rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "alice", "roles": ["ROLE_OPERATOR"]},
		"action": "risk.curve-set.create",
	}
}

test_operator_may_read_a_curve_set if {
	rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "alice", "roles": ["ROLE_OPERATOR"]},
		"action": "risk.curve-set.read",
	}
}

test_shared_service_account_is_denied_curve_set_upload if {
	not rest.allow with input as {
		"principal": {
			"type": "HUMAN",
			"id": "service-account-openbank-services",
			"roles": ["ROLE_OPERATOR", "ROLE_API"],
		},
		"action": "risk.curve-set.create",
	}
}

test_viewer_is_denied_curve_set_upload if {
	not rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "bob", "roles": ["ROLE_VIEWER"]},
		"action": "risk.curve-set.create",
	}
}

# The snapshot grant must not leak into the curve-set action, nor the reverse.
test_snapshot_create_reason_does_not_cover_curve_sets if {
	not "operator-risk-snapshot-create" in rest.allowed_reasons with input as {
		"principal": {"type": "HUMAN", "id": "alice", "roles": ["ROLE_OPERATOR"]},
		"action": "risk.curve-set.create",
	}
}

# ── #10618: department roles ───────────────────────────────────────────────────────────────

risk_user := {"type": "HUMAN", "id": "u-risk", "roles": ["ROLE_RISK"]}

finance_user := {"type": "HUMAN", "id": "u-fin", "roles": ["ROLE_FINANCE"]}

# Mirrors rules.yaml authz.role_action_matrix for the two department roles (the CI trio loads no
# data document). The live-bundle decision is re-checked with `opa eval` over rules-opa-data.yaml.
dept_rules := {"authz": {"role_action_matrix": {
	"ROLE_RISK": {"grant": ["risk.snapshot.read", "risk.curve-set.read"]},
	"ROLE_FINANCE": {"grant": ["risk.snapshot.read", "risk.curve-set.read"]},
}}}

test_risk_may_read_snapshots_and_curve_sets if {
	every action in ["risk.snapshot.read", "risk.curve-set.read"] {
		rest.allow with input as {"principal": risk_user, "action": action}
			with data.rules as dept_rules
	}
}

test_finance_may_read_snapshots_and_curve_sets if {
	every action in ["risk.snapshot.read", "risk.curve-set.read"] {
		rest.allow with input as {"principal": finance_user, "action": action}
			with data.rules as dept_rules
	}
}

test_risk_may_create_snapshot_and_upload_curve_set if {
	every action in ["risk.snapshot.create", "risk.curve-set.create"] {
		rest.allow with input as {"principal": risk_user, "action": action}
	}
}

test_finance_is_denied_risk_writes if {
	every action in ["risk.snapshot.create", "risk.curve-set.create"] {
		not rest.allow with input as {"principal": finance_user, "action": action}
			with data.rules as dept_rules
	}
}

# A service account holding ROLE_RISK must still be refused every write.
test_service_account_with_risk_role_is_denied_writes if {
	every action in ["risk.snapshot.create", "risk.curve-set.create"] {
		not rest.allow with input as {
			"principal": {"type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_RISK"]},
			"action": action,
		}
			with data.rules as dept_rules
	}
}

test_treasury_roles_grant_nothing_in_risk if {
	every action in ["risk.snapshot.read", "risk.curve-set.read", "risk.snapshot.create"] {
		not rest.allow with input as {
			"principal": {"type": "HUMAN", "id": "u-tr", "roles": ["ROLE_TREASURY_DEALER", "ROLE_TREASURY_APPROVER"]},
			"action": action,
		}
			with data.rules as dept_rules
	}
}
