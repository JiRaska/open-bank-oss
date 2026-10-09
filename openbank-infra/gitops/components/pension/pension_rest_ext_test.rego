# SPDX-License-Identifier: Apache-2.0
# Held to a known-POSITIVE and a known-NEGATIVE: every allow case has a matching deny.

package openbank.rest_test

import data.openbank.rest
import rego.v1

pension_edge := "service-account-openbank-edge"

test_edge_may_create_a_contract if {
	rest.allow with input as {
		"principal": {"type": "HUMAN", "id": pension_edge, "roles": ["ROLE_API"]},
		"action": "pension.contract.create",
	}
}

test_edge_may_terminate if {
	rest.allow with input as {
		"principal": {"type": "HUMAN", "id": pension_edge, "roles": ["ROLE_API"]},
		"action": "pension.contract.terminate",
	}
}

test_operator_may_read_a_contract if {
	rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "alice", "roles": ["ROLE_OPERATOR"]},
		"action": "pension.contract.read",
	}
}

test_operator_may_not_terminate if {
	not rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "alice", "roles": ["ROLE_OPERATOR"]},
		"action": "pension.contract.terminate",
	}
}

test_operator_may_not_change_strategy if {
	not rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "alice", "roles": ["ROLE_OPERATOR"]},
		"action": "pension.contract.strategy",
	}
}

test_other_service_account_may_not_create if {
	not rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_OPERATOR", "ROLE_API"]},
		"action": "pension.contract.create",
	}
}

test_anonymous_may_not_read if {
	not rest.allow with input as {
		"principal": {"type": "ANONYMOUS", "id": "", "roles": []},
		"action": "pension.contract.read",
	}
}

# --- slice S5: exits and death claims ---

test_edge_may_sign_a_termination_and_confirm_a_payout if {
	rest.allow with input as {
		"principal": {"type": "HUMAN", "id": pension_edge, "roles": ["ROLE_API"]},
		"action": "pension.exit.terminate",
	}
	rest.allow with input as {
		"principal": {"type": "HUMAN", "id": pension_edge, "roles": ["ROLE_API"]},
		"action": "pension.exit.payout",
	}
}

test_operator_may_read_exits_but_not_move_money if {
	rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "alice", "roles": ["ROLE_OPERATOR"]},
		"action": "pension.exit.read",
	}
	not rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "alice", "roles": ["ROLE_OPERATOR"]},
		"action": "pension.exit.payout",
	}
	not rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "alice", "roles": ["ROLE_OPERATOR"]},
		"action": "pension.exit.terminate",
	}
}

test_operator_may_work_a_death_claim if {
	every action in {"pension.death.read", "pension.death.notify", "pension.death.verify", "pension.death.approve"} {
		rest.allow with input as {
			"principal": {"type": "HUMAN", "id": "alice", "roles": ["ROLE_OPERATOR"]},
			"action": action,
		}
	}
}

test_edge_may_not_touch_a_death_claim if {
	not rest.allow with input as {
		"principal": {"type": "HUMAN", "id": pension_edge, "roles": ["ROLE_API"]},
		"action": "pension.death.notify",
	}
}

test_service_account_with_operator_role_may_not_approve_a_death_claim if {
	not rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_OPERATOR"]},
		"action": "pension.death.approve",
	}
}

test_viewer_may_not_notify_a_death if {
	not rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "bob", "roles": ["ROLE_VIEWER"]},
		"action": "pension.death.notify",
	}
}
