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

# S8: the S1 shortcuts are retired — no principal reaches them.
test_edge_may_not_use_the_retired_s1_termination if {
	not rest.allow with input as {
		"principal": {"type": "HUMAN", "id": pension_edge, "roles": ["ROLE_API"]},
		"action": "pension.contract.terminate",
	}
}

test_edge_may_not_activate_a_contract_directly if {
	not rest.allow with input as {
		"principal": {"type": "HUMAN", "id": pension_edge, "roles": ["ROLE_API"]},
		"action": "pension.contract.activate",
	}
}

test_edge_may_simulate if {
	rest.allow with input as {
		"principal": {"type": "HUMAN", "id": pension_edge, "roles": ["ROLE_API"]},
		"action": "pension.simulation.run",
	}
}

test_other_service_account_may_not_simulate if {
	not rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_OPERATOR", "ROLE_API"]},
		"action": "pension.simulation.run",
	}
}

test_operator_may_read_the_payout_queue if {
	rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "alice", "roles": ["ROLE_OPERATOR"]},
		"action": "pension.operator.exit-read",
	}
}

test_edge_may_not_read_the_payout_queue if {
	not rest.allow with input as {
		"principal": {"type": "HUMAN", "id": pension_edge, "roles": ["ROLE_API", "ROLE_OPERATOR"]},
		"action": "pension.operator.exit-read",
	}
}

test_service_account_may_not_read_the_payout_queue if {
	not rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_OPERATOR"]},
		"action": "pension.operator.exit-read",
	}
}

test_operator_may_read_a_contract if {
	rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "alice", "roles": ["ROLE_OPERATOR"]},
		"action": "pension.contract.inspect",
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
		"action": "pension.contract.inspect",
	}
}

# --- ADR-0334 slice S2 -------------------------------------------------------------------------

provider := "service-account-pension-provider-P2"

test_edge_may_sign_an_application if {
	rest.allow with input as {
		"principal": {"type": "HUMAN", "id": pension_edge, "roles": ["ROLE_API"]},
		"action": "pension.onboarding.sign",
	}
}

test_other_service_account_may_not_sign if {
	not rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_OPERATOR", "ROLE_API"]},
		"action": "pension.onboarding.sign",
	}
}

test_edge_may_not_use_operator_actions if {
	not rest.allow with input as {
		"principal": {"type": "HUMAN", "id": pension_edge, "roles": ["ROLE_API"]},
		"action": "pension.operator.transfer",
	}
}

test_edge_may_not_raise_a_provider_request if {
	not rest.allow with input as {
		"principal": {"type": "HUMAN", "id": pension_edge, "roles": ["ROLE_API"]},
		"action": "pension.transfer.provider-request",
	}
}

test_provider_may_raise_a_provider_request if {
	rest.allow with input as {
		"principal": {"type": "HUMAN", "id": provider, "roles": ["ROLE_API"]},
		"action": "pension.transfer.provider-request",
	}
}

test_provider_may_not_consent_for_the_participant if {
	not rest.allow with input as {
		"principal": {"type": "HUMAN", "id": provider, "roles": ["ROLE_API"]},
		"action": "pension.transfer.consent",
	}
}

test_provider_may_not_act_on_onboarding if {
	not rest.allow with input as {
		"principal": {"type": "HUMAN", "id": provider, "roles": ["ROLE_API"]},
		"action": "pension.onboarding.start",
	}
}

test_operator_may_relay_a_transfer_answer if {
	rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "alice", "roles": ["ROLE_OPERATOR"]},
		"action": "pension.operator.transfer",
	}
}

test_operator_may_not_sign_for_a_participant if {
	not rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "alice", "roles": ["ROLE_OPERATOR"]},
		"action": "pension.onboarding.sign",
	}
}

test_service_account_operator_may_not_relay if {
	not rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_OPERATOR"]},
		"action": "pension.operator.transfer",
	}
}

test_compliance_may_read_but_not_act if {
	rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "carol", "roles": ["ROLE_COMPLIANCE"]},
		"action": "pension.operator.inspect",
	}
	not rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "carol", "roles": ["ROLE_COMPLIANCE"]},
		"action": "pension.operator.contribution",
	}
}

# --- ADR-0334 S3 funding -------------------------------------------------------------------

test_edge_may_read_funding if {
	rest.allow with input as {
		"principal": {"type": "HUMAN", "id": pension_edge, "roles": ["ROLE_API"]},
		"action": "pension.funding.inspect",
	}
}

test_edge_may_set_up_a_mandate if {
	rest.allow with input as {
		"principal": {"type": "HUMAN", "id": pension_edge, "roles": ["ROLE_API"]},
		"action": "pension.funding.mandate",
	}
}

test_edge_may_not_operate_the_unmatched_queue if {
	not rest.allow with input as {
		"principal": {"type": "HUMAN", "id": pension_edge, "roles": ["ROLE_API"]},
		"action": "pension.funding.operate",
	}
}

test_edge_may_not_ingest_payments if {
	not rest.allow with input as {
		"principal": {"type": "HUMAN", "id": pension_edge, "roles": ["ROLE_API"]},
		"action": "pension.funding.ingest",
	}
}

test_operator_may_operate_claim_batches if {
	rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "alice", "roles": ["ROLE_OPERATOR"]},
		"action": "pension.funding.operate",
	}
}

test_payments_staff_may_ingest if {
	rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "bob", "roles": ["ROLE_PAYMENTS"]},
		"action": "pension.funding.ingest",
	}
}

test_payments_staff_may_not_operate if {
	not rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "bob", "roles": ["ROLE_PAYMENTS"]},
		"action": "pension.funding.operate",
	}
}

test_service_account_with_operator_may_not_operate if {
	not rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_OPERATOR", "ROLE_API"]},
		"action": "pension.funding.operate",
	}
}

test_service_account_with_operator_may_not_ingest if {
	not rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_OPERATOR", "ROLE_API"]},
		"action": "pension.funding.ingest",
	}
}

test_operator_may_not_set_up_a_mandate if {
	not rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "alice", "roles": ["ROLE_OPERATOR"]},
		"action": "pension.funding.mandate",
	}
}

test_operator_may_read_funding if {
	rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "alice", "roles": ["ROLE_OPERATOR"]},
		"action": "pension.funding.inspect",
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
		"action": "pension.exit.inspect",
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
	every action in {"pension.death.inspect", "pension.death.notify", "pension.death.verify", "pension.death.approve"} {
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

# --- #12371: no pension read rides the base read-any grants ------------------------------------
# Base rest.rego admits any action ending in `.read`/`.list` to a HUMAN with ROLE_OPERATOR/ADMIN
# (operator-read-any) or ROLE_COMPLIANCE (compliance-read-any), and the shared M2M account is
# classified HUMAN and holds those roles. The reads are therefore named `*.inspect`; these tests
# fail the day one is renamed back to `.read`.

pension_inspect_actions := {
	"pension.contract.inspect",
	"pension.exit.inspect",
	"pension.funding.inspect",
	"pension.death.inspect",
	"pension.onboarding.inspect",
	"pension.transfer.inspect",
	"pension.operator.inspect",
}

test_shared_service_account_may_not_inspect_any_pension_data if {
	every action in pension_inspect_actions {
		every role in {"ROLE_OPERATOR", "ROLE_COMPLIANCE", "ROLE_ADMIN"} {
			not rest.allow with input as {
				"principal": {"type": "HUMAN", "id": "service-account-openbank-services", "roles": [role]},
				"action": action,
			}
		}
	}
}

test_shared_service_account_with_every_role_may_not_inspect if {
	every action in pension_inspect_actions {
		not rest.allow with input as {
			"principal": {
				"type": "HUMAN", "id": "service-account-openbank-services",
				"roles": ["ROLE_OPERATOR", "ROLE_COMPLIANCE", "ROLE_ADMIN", "ROLE_API"],
			},
			"action": action,
		}
	}
}

test_no_pension_action_carries_a_read_any_verb if {
	every action in pension_inspect_actions {
		not endswith(action, ".read")
		not endswith(action, ".list")
	}
}

# Known-positives: the narrow rules still admit the intended callers.
test_staff_operator_may_inspect_staff_facing_pension_data if {
	every action in {
		"pension.contract.inspect", "pension.exit.inspect", "pension.funding.inspect",
		"pension.death.inspect", "pension.operator.inspect",
	} {
		rest.allow with input as {
			"principal": {"type": "HUMAN", "id": "alice", "roles": ["ROLE_OPERATOR"]},
			"action": action,
		}
	}
}

test_edge_may_inspect_participant_pension_data if {
	every action in {
		"pension.contract.inspect", "pension.exit.inspect", "pension.funding.inspect",
		"pension.onboarding.inspect", "pension.transfer.inspect",
	} {
		rest.allow with input as {
			"principal": {"type": "HUMAN", "id": pension_edge, "roles": ["ROLE_API"]},
			"action": action,
		}
	}
}

# Participant-scoped reads (the service requires X-Customer-Party-Id) are edge-only, staff too.
test_staff_may_not_inspect_participant_only_reads if {
	every action in {"pension.onboarding.inspect", "pension.transfer.inspect"} {
		not rest.allow with input as {
			"principal": {"type": "HUMAN", "id": "alice", "roles": ["ROLE_OPERATOR", "ROLE_COMPLIANCE"]},
			"action": action,
		}
	}
}

test_edge_may_not_inspect_staff_only_reads if {
	every action in {"pension.death.inspect", "pension.operator.inspect"} {
		not rest.allow with input as {
			"principal": {"type": "HUMAN", "id": pension_edge, "roles": ["ROLE_API"]},
			"action": action,
		}
	}
}
