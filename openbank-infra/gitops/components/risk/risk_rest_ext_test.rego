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
