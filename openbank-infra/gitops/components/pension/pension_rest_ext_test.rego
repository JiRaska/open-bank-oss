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
		"action": "pension.operator.read",
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
		"action": "pension.funding.read",
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
		"action": "pension.funding.read",
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

# --- #12376: schedule and beneficiary changes ------------------------------------------------

test_edge_may_change_schedule_and_beneficiaries if {
	every action in {"pension.contract.schedule", "pension.contract.beneficiaries"} {
		rest.allow with input as {
			"principal": {"type": "HUMAN", "id": pension_edge, "roles": ["ROLE_API"]},
			"action": action,
		}
	}
}

test_staff_may_not_change_schedule_or_beneficiaries if {
	every action in {"pension.contract.schedule", "pension.contract.beneficiaries"} {
		not rest.allow with input as {
			"principal": {"type": "HUMAN", "id": "alice", "roles": ["ROLE_OPERATOR", "ROLE_ADMIN"]},
			"action": action,
		}
	}
}

test_other_service_account_may_not_change_beneficiaries if {
	not rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_API", "ROLE_OPERATOR"]},
		"action": "pension.contract.beneficiaries",
	}
}

test_compliance_may_read_change_history_via_operator_view if {
	rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "carol", "roles": ["ROLE_COMPLIANCE"]},
		"action": "pension.operator.read",
	}
}

# --- #12383 annuity marketplace ---------------------------------------------------------------

test_edge_may_select_an_annuity_offer if {
	rest.allow with input as {
		"principal": {"type": "HUMAN", "id": pension_edge, "roles": ["ROLE_API"]},
		"action": "pension.annuity.select",
	}
}

test_other_service_account_may_not_select_an_annuity_offer if {
	not rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_OPERATOR", "ROLE_API"]},
		"action": "pension.annuity.select",
	}
}

test_edge_may_not_manage_annuity_partners if {
	not rest.allow with input as {
		"principal": {"type": "HUMAN", "id": pension_edge, "roles": ["ROLE_API", "ROLE_OPERATOR"]},
		"action": "pension.operator.annuity-write",
	}
}

test_operator_may_approve_an_annuity_partner if {
	rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "alice", "roles": ["ROLE_OPERATOR"]},
		"action": "pension.operator.annuity-approve",
	}
}

test_service_account_operator_may_not_approve_an_annuity_partner if {
	not rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_OPERATOR"]},
		"action": "pension.operator.annuity-approve",
	}
}

test_compliance_may_read_annuity_partners if {
	rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "carol", "roles": ["ROLE_COMPLIANCE"]},
		"action": "pension.operator.annuity-read",
	}
}

test_compliance_may_not_write_annuity_partners if {
	not rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "carol", "roles": ["ROLE_COMPLIANCE"]},
		"action": "pension.operator.annuity-write",
	}
}

# ---- #12425: participant aggregates for the ČNB returns ----

tax_reporting_principal := {"type": "HUMAN", "id": "service-account-openbank-tax-reporting", "roles": ["ROLE_API"]}

# must-ALLOW: tax-reporting's own client and staff oversight.
test_tax_reporting_and_staff_read_participant_aggregates if {
	rest.allow with input as {"principal": tax_reporting_principal, "action": "pension.reporting.aggregate"}
	rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "carol", "roles": ["ROLE_AUDITOR"]},
		"action": "pension.reporting.aggregate",
	}
}

# must-DENY: the shared account, the edge, pension's own client, an AI agent, a customer.
test_others_cannot_read_participant_aggregates if {
	not rest.allow with input as {"principal": {"type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_OPERATOR", "ROLE_API"]}, "action": "pension.reporting.aggregate"}
	not rest.allow with input as {"principal": {"type": "HUMAN", "id": "service-account-openbank-edge", "roles": ["ROLE_API"]}, "action": "pension.reporting.aggregate"}
	not rest.allow with input as {"principal": {"type": "HUMAN", "id": "service-account-openbank-pension", "roles": ["ROLE_API"]}, "action": "pension.reporting.aggregate"}
	not rest.allow with input as {"principal": {"type": "AI_AGENT", "id": "agent:x", "roles": ["ROLE_OPERATOR"]}, "action": "pension.reporting.aggregate"}
	not rest.allow with input as {"principal": {"type": "HUMAN", "id": "cust-1", "roles": ["ROLE_CUSTOMER"]}, "action": "pension.reporting.aggregate"}
	not rest.allow with input as {"principal": {"type": "HUMAN", "id": "service-account-openbank-tax-reporting", "roles": []}, "action": "pension.reporting.aggregate"}
}

# must-DENY: tax-reporting reaches no participant-level route.
test_tax_reporting_reaches_no_participant_route if {
	every a in {"pension.contract.read", "pension.exit.read", "pension.funding.read", "pension.operator.read", "pension.transfer.read"} {
		not rest.allow with input as {"principal": tax_reporting_principal, "action": a}
	}
}

# Why the verb is not `.read`: under that name the generic rule hands it to the shared account.
test_a_read_verb_would_have_reached_the_shared_account if {
	rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_OPERATOR"]},
		"action": "pension.reporting.read",
	}
}
