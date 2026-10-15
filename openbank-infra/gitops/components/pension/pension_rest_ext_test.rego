# SPDX-License-Identifier: Apache-2.0
# Held to a known-POSITIVE and a known-NEGATIVE: every allow case has a matching deny.

package openbank.rest_test

import data.openbank.rest
import rego.v1

pension_edge := "service-account-openbank-edge"

# The S1 draft path is not a customer flow: contracts are born through onboarding (#12435).
test_edge_may_not_create_a_contract_through_the_s1_draft_path if {
	not rest.allow with input as {
		"principal": {"service_account": true, "client_id": "openbank-edge", "type": "HUMAN", "id": pension_edge, "roles": ["ROLE_API"]},
		"action": "pension.contract.create",
	}
}

test_edge_may_not_submit_an_s1_draft if {
	not rest.allow with input as {
		"principal": {"type": "HUMAN", "id": pension_edge, "roles": ["ROLE_API"]},
		"action": "pension.contract.submit",
	}
}

# Known-positive twin: the edge still starts a contract the real way.
test_edge_may_start_onboarding if {
	rest.allow with input as {
		"principal": {"service_account": true, "client_id": "openbank-edge", "type": "HUMAN", "id": pension_edge, "roles": ["ROLE_API"]},
		"action": "pension.onboarding.start",
	}
}

# S8: the S1 shortcuts are retired — no principal reaches them.
test_edge_may_not_use_the_retired_s1_termination if {
	not rest.allow with input as {
		"principal": {"service_account": true, "client_id": "openbank-edge", "type": "HUMAN", "id": pension_edge, "roles": ["ROLE_API"]},
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
		"principal": {"service_account": false, "type": "HUMAN", "id": "alice", "roles": ["ROLE_OPERATOR"]},
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
		"principal": {"service_account": false, "type": "HUMAN", "id": "alice", "roles": ["ROLE_OPERATOR"]},
		"action": "pension.contract.inspect",
	}
}

test_operator_may_not_terminate if {
	not rest.allow with input as {
		"principal": {"service_account": false, "type": "HUMAN", "id": "alice", "roles": ["ROLE_OPERATOR"]},
		"action": "pension.contract.terminate",
	}
}

test_operator_may_not_change_strategy if {
	not rest.allow with input as {
		"principal": {"service_account": false, "type": "HUMAN", "id": "alice", "roles": ["ROLE_OPERATOR"]},
		"action": "pension.contract.strategy",
	}
}

test_other_service_account_may_not_create if {
	not rest.allow with input as {
		"principal": {"service_account": true, "client_id": "openbank-services", "type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_OPERATOR", "ROLE_API"]},
		"action": "pension.contract.create",
	}
}

test_anonymous_may_not_read if {
	not rest.allow with input as {
		"principal": {"service_account": false, "type": "ANONYMOUS", "id": "", "roles": []},
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

# A participant withdraws their own transfer-out (or consent) through the edge — and only there.
test_edge_may_withdraw_a_transfer_out if {
	rest.allow with input as {
		"principal": {"type": "HUMAN", "id": pension_edge, "roles": ["ROLE_API"]},
		"action": "pension.transfer.withdraw",
	}
}

test_provider_may_not_withdraw_for_the_participant if {
	not rest.allow with input as {
		"principal": {"type": "HUMAN", "id": provider, "roles": ["ROLE_API"]},
		"action": "pension.transfer.withdraw",
	}
}

test_operator_may_not_withdraw_for_the_participant if {
	not rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "alice", "roles": ["ROLE_OPERATOR", "ROLE_ADMIN"]},
		"action": "pension.transfer.withdraw",
	}
}

test_other_service_account_may_not_withdraw if {
	not rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_OPERATOR", "ROLE_API"]},
		"action": "pension.transfer.withdraw",
	}
}

test_provider_may_not_act_on_onboarding if {
	not rest.allow with input as {
		"principal": {"type": "HUMAN", "id": provider, "roles": ["ROLE_API"]},
		"action": "pension.onboarding.start",
	}
}

# Phase 2 contract: staff allow-cases carry the PEP marker (service_account: false);
# without it the principal classifies fail-closed as a machine.
test_operator_may_relay_a_transfer_answer if {
	rest.allow with input as {
		"principal": {"service_account": false, "type": "HUMAN", "id": "alice", "roles": ["ROLE_OPERATOR"]},
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

# Phase 2 contract: staff allow-cases carry the PEP marker (service_account: false);
# without it the principal classifies fail-closed as a machine.
test_compliance_may_read_but_not_act if {
	rest.allow with input as {
		"principal": {"service_account": false, "type": "HUMAN", "id": "carol", "roles": ["ROLE_COMPLIANCE"]},
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

# Phase 2 contract: staff allow-cases carry the PEP marker (service_account: false);
# without it the principal classifies fail-closed as a machine.
test_operator_may_operate_claim_batches if {
	rest.allow with input as {
		"principal": {"service_account": false, "type": "HUMAN", "id": "alice", "roles": ["ROLE_OPERATOR"]},
		"action": "pension.funding.operate",
	}
}

test_payments_staff_may_ingest if {
	rest.allow with input as {
		"principal": {"service_account": false, "type": "HUMAN", "id": "bob", "roles": ["ROLE_PAYMENTS"]},
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

# Phase 2 contract: staff allow-cases carry the PEP marker (service_account: false);
# without it the principal classifies fail-closed as a machine.
test_operator_may_read_funding if {
	rest.allow with input as {
		"principal": {"service_account": false, "type": "HUMAN", "id": "alice", "roles": ["ROLE_OPERATOR"]},
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
		"principal": {"service_account": false, "type": "HUMAN", "id": "alice", "roles": ["ROLE_OPERATOR"]},
		"action": "pension.exit.inspect",
	}
	not rest.allow with input as {
		"principal": {"service_account": false, "type": "HUMAN", "id": "alice", "roles": ["ROLE_OPERATOR"]},
		"action": "pension.exit.payout",
	}
	not rest.allow with input as {
		"principal": {"service_account": false, "type": "HUMAN", "id": "alice", "roles": ["ROLE_OPERATOR"]},
		"action": "pension.exit.terminate",
	}
}

# Phase 2 contract: staff allow-cases carry the PEP marker (service_account: false);
# without it the principal classifies fail-closed as a machine.
test_operator_may_work_a_death_claim if {
	every action in {"pension.death.inspect", "pension.death.notify", "pension.death.verify", "pension.death.approve"} {
		rest.allow with input as {
			"principal": {"service_account": false, "type": "HUMAN", "id": "alice", "roles": ["ROLE_OPERATOR"]},
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

# Phase 2 contract: staff allow-cases carry the PEP marker (service_account: false);
# without it the principal classifies fail-closed as a machine.
test_compliance_may_read_change_history_via_operator_view if {
	rest.allow with input as {
		"principal": {"service_account": false, "type": "HUMAN", "id": "carol", "roles": ["ROLE_COMPLIANCE"]},
		"action": "pension.operator.inspect",
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

# Phase 2 contract: staff allow-cases carry the PEP marker (service_account: false);
# without it the principal classifies fail-closed as a machine.
test_operator_may_approve_an_annuity_partner if {
	rest.allow with input as {
		"principal": {"service_account": false, "type": "HUMAN", "id": "alice", "roles": ["ROLE_OPERATOR"]},
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
		"principal": {"service_account": false, "type": "HUMAN", "id": "carol", "roles": ["ROLE_COMPLIANCE"]},
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
# Phase 2 contract: staff allow-cases carry the PEP marker (service_account: false);
# without it the principal classifies fail-closed as a machine.
test_tax_reporting_and_staff_read_participant_aggregates if {
	rest.allow with input as {"principal": tax_reporting_principal, "action": "pension.reporting.aggregate"}
	rest.allow with input as {
		"principal": {"service_account": false, "type": "HUMAN", "id": "carol", "roles": ["ROLE_AUDITOR"]},
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
	every a in {"pension.contract.inspect", "pension.exit.inspect", "pension.funding.inspect", "pension.operator.inspect", "pension.transfer.inspect", "pension.onboarding.inspect", "pension.death.inspect", "pension.annuity.inspect"} {
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

# --- participant valuation + operator mandates list (#12350) ------------------------------------

test_edge_may_read_a_contract_valuation if {
	rest.allow with input as {
		"principal": {"type": "HUMAN", "id": pension_edge, "roles": ["ROLE_API"]},
		"action": "pension.contract.inspect",
	}
}

test_shared_api_service_account_may_not_read_a_contract_valuation if {
	not rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_API"]},
		"action": "pension.contract.inspect",
	}
}

# Phase 2 contract: staff allow-cases carry the PEP marker (service_account: false);
# without it the principal classifies fail-closed as a machine.
test_operator_may_list_mandates if {
	rest.allow with input as {
		"principal": {"service_account": false, "type": "HUMAN", "id": "alice", "roles": ["ROLE_OPERATOR"]},
		"action": "pension.operator.mandate-read",
	}
}

test_compliance_may_list_mandates if {
	rest.allow with input as {
		"principal": {"service_account": false, "type": "HUMAN", "id": "carol", "roles": ["ROLE_COMPLIANCE"]},
		"action": "pension.operator.mandate-read",
	}
}

test_edge_may_not_list_mandates if {
	not rest.allow with input as {
		"principal": {"type": "HUMAN", "id": pension_edge, "roles": ["ROLE_API", "ROLE_OPERATOR"]},
		"action": "pension.operator.mandate-read",
	}
}

test_service_account_with_operator_role_may_not_list_mandates if {
	not rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_OPERATOR"]},
		"action": "pension.operator.mandate-read",
	}
}

test_customer_role_may_not_list_mandates if {
	not rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "bob", "roles": ["ROLE_CUSTOMER"]},
		"action": "pension.operator.mandate-read",
	}
}

# ---------------------------------------------------------------------------------------------
# Participant-data reads vs the generic read rules (operator-read-any / compliance-read-any).
# Every pension read is `.inspect`, so no generic `*.read` rule can match it — independent of
# rules.yaml: authz.operator_read_any_excluded_actions, which is therefore evaluated EMPTY here.
# ---------------------------------------------------------------------------------------------
pension_participant_reads := [
	"pension.contract.inspect",
	"pension.exit.inspect",
	"pension.death.inspect",
	"pension.funding.inspect",
	"pension.onboarding.inspect",
	"pension.transfer.inspect",
	"pension.operator.inspect",
	"pension.annuity.inspect",
]

no_exclusions := {"authz": {"operator_read_any_excluded_actions": []}}

pension_principal(id, roles) := {"type": "HUMAN", "id": id, "roles": roles}

human_pension_principal(id, roles) := {"service_account": false, "type": "HUMAN", "id": id, "roles": roles}

# must-DENY: the shared M2M client (ROLE_OPERATOR in the gitops realm, + ROLE_COMPLIANCE in CI),
# an unrelated service, and an AI agent — for every participant read, with no exclusion list.
test_machines_cannot_read_pension_participant_data if {
	every action in pension_participant_reads {
		every role in ["ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_COMPLIANCE"] {
			not rest.allow with input as {"principal": pension_principal("service-account-openbank-services", [role, "ROLE_API"]), "action": action}
				with data.rules as no_exclusions
		}
		not rest.allow with input as {"principal": pension_principal("service-account-openbank-treasury", ["ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_COMPLIANCE"]), "action": action}
			with data.rules as no_exclusions
		not rest.allow with input as {"principal": {"type": "AI_AGENT", "id": "agent-x", "roles": ["ROLE_OPERATOR"]}, "action": action}
			with data.rules as no_exclusions
	}
}

# must-ALLOW: human operator, admin and compliance read every participant read.
# Phase-2 contract: a human principal declares service_account == false explicitly
# (`pension_principal` stays flagless — it is also used for machine must-DENY cases).
test_human_staff_read_pension_participant_data if {
	every action in pension_participant_reads {
		every role in ["ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_COMPLIANCE"] {
			rest.allow with input as {"principal": human_pension_principal("alice", [role]), "action": action}
				with data.rules as no_exclusions
		}
	}
}

# must-ALLOW the edge relay for the participant's own reads; must-DENY it the staff-only ones.
test_edge_relays_customer_scoped_reads_only if {
	every action in ["pension.contract.inspect", "pension.exit.inspect", "pension.funding.inspect", "pension.onboarding.inspect", "pension.transfer.inspect", "pension.annuity.inspect"] {
		rest.allow with input as {"principal": pension_principal("service-account-openbank-edge", ["ROLE_API", "ROLE_OPERATOR"]), "action": action}
			with data.rules as no_exclusions
	}
	every action in ["pension.death.inspect", "pension.operator.inspect", "pension.operator.mandate-read"] {
		not rest.allow with input as {"principal": pension_principal("service-account-openbank-edge", ["ROLE_API", "ROLE_OPERATOR"]), "action": action}
			with data.rules as no_exclusions
	}
}

# Generic operator reads remain a positive control for the inspect-verb tests above.
# Compliance reads separately exclude machines in the base policy; real staff still pass.
test_read_controls_match_base_identity_policy if {
	rest.allow with input as {"principal": pension_principal("service-account-openbank-services", ["ROLE_OPERATOR"]), "action": "pension.onboarding.read"}
		with data.rules as no_exclusions
	not rest.allow with input as {"principal": pension_principal("service-account-openbank-services", ["ROLE_COMPLIANCE"]), "action": "pension.contract.read"}
		with data.rules as no_exclusions
	rest.allow with input as {"principal": pension_principal("alice", ["ROLE_COMPLIANCE"]), "action": "pension.contract.read"}
		with data.rules as no_exclusions
}

# ---------------------------------------------------------------------------------------------
# The customer edge is allowed EXACTLY the actions its routes use — no more, no fewer (#12435).
# `pension_actions` is every `@Authorize(action = "pension.…")` in pension-service's src/main;
# `PensionOpaActionInventoryTest` fails the build when the two drift, so a new action cannot reach
# production without being classified here as an edge action or not.
# ---------------------------------------------------------------------------------------------
pension_actions := {
	"pension.annuity.cancel", "pension.annuity.inspect", "pension.annuity.quote", "pension.annuity.select",
	"pension.contract.beneficiaries", "pension.contract.inspect", "pension.contract.resume",
	"pension.contract.schedule", "pension.contract.strategy", "pension.contract.suspend",
	"pension.death.approve", "pension.death.inspect", "pension.death.notify", "pension.death.verify",
	"pension.exit.inspect", "pension.exit.payout", "pension.exit.terminate",
	"pension.funding.declare", "pension.funding.ingest", "pension.funding.inspect", "pension.funding.mandate",
	"pension.funding.operate",
	"pension.onboarding.inspect", "pension.onboarding.kid", "pension.onboarding.questionnaire",
	"pension.onboarding.sign", "pension.onboarding.start", "pension.onboarding.strategy",
	"pension.onboarding.withdraw",
	"pension.operator.annuity-approve", "pension.operator.annuity-purchase", "pension.operator.annuity-read",
	"pension.operator.annuity-write", "pension.operator.contribution", "pension.operator.exit-read",
	"pension.operator.inspect", "pension.operator.mandate-read", "pension.operator.transfer",
	"pension.reporting.aggregate",
	"pension.simulation.run",
	"pension.transfer.consent", "pension.transfer.inspect", "pension.transfer.provider-request",
	"pension.transfer.request-out", "pension.transfer.withdraw",
}

# What customer-edge's pension routes call (CustomerPensionResource, CustomerPensionChangeResource,
# CustomerPensionTransferResource → pension-service paths → that route's @Authorize action).
edge_route_actions := {
	"pension.annuity.cancel", "pension.annuity.inspect", "pension.annuity.quote", "pension.annuity.select",
	"pension.contract.beneficiaries", "pension.contract.inspect", "pension.contract.resume",
	"pension.contract.schedule", "pension.contract.strategy", "pension.contract.suspend",
	"pension.exit.inspect", "pension.exit.payout", "pension.exit.terminate",
	"pension.funding.declare", "pension.funding.inspect", "pension.funding.mandate",
	"pension.onboarding.inspect", "pension.onboarding.kid", "pension.onboarding.questionnaire",
	"pension.onboarding.sign", "pension.onboarding.start", "pension.onboarding.strategy",
	"pension.onboarding.withdraw",
	"pension.simulation.run",
	"pension.transfer.consent", "pension.transfer.inspect", "pension.transfer.request-out",
	"pension.transfer.withdraw",
}

edge_allowed_pension_actions := {action |
	some action in pension_actions
	rest.allow with input as {
		"principal": {"type": "HUMAN", "id": pension_edge, "roles": ["ROLE_API"]},
		"action": action,
	}
}

test_edge_is_allowed_exactly_the_actions_its_routes_use if {
	edge_allowed_pension_actions == edge_route_actions
}

# Known-negative for the exactness test itself: a set one action short must NOT compare equal.
test_edge_exactness_check_detects_a_missing_action if {
	edge_allowed_pension_actions != edge_route_actions - {"pension.transfer.withdraw"}
}
