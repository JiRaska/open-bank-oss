# SPDX-License-Identifier: Apache-2.0
# Held to a known-POSITIVE and a known-NEGATIVE for every action: a policy test that can only show
# what it permits is decoration.

package openbank.rest_test

import data.openbank.rest
import rego.v1

dealer := {"type": "HUMAN", "id": "dealer-1", "roles": ["ROLE_TREASURY_DEALER"]}

approver := {"type": "HUMAN", "id": "approver-1", "roles": ["ROLE_TREASURY_APPROVER"]}

admin := {"type": "HUMAN", "id": "admin-1", "roles": ["ROLE_ADMIN"]}

senior := {"type": "HUMAN", "id": "senior-1", "roles": ["ROLE_TREASURY_SENIOR_APPROVER"]}

nobody := {"type": "HUMAN", "id": "teller-1", "roles": ["ROLE_CUSTOMER"]}

# The shared backend client, given the approver role by mistake: still a machine, still denied.
shared_sa := {
	"type": "HUMAN",
	"id": "service-account-openbank-services",
	"roles": ["ROLE_TREASURY_APPROVER", "ROLE_TREASURY_DEALER", "ROLE_OPERATOR"],
}

agent := {"type": "AI_AGENT", "id": "agent:treasury-drafter", "roles": ["ROLE_TREASURY_DEALER", "ROLE_TREASURY_APPROVER"]}

reads := {"treasury.deal.read", "treasury.counterparty.read", "treasury.position.read", "treasury.nostro.read", "treasury.quote.read"}

dealer_writes := {"treasury.deal.draft", "treasury.deal.submit"}

approver_writes := {
	"treasury.deal.approve",
	"treasury.deal.reject",
	"treasury.deal.confirm",
	"treasury.deal.settle",
	"treasury.deal.mature",
	"treasury.deal.reverse",
}

all_writes := (dealer_writes | approver_writes) | {"treasury.deal.cancel"}

allowed(p, a) if rest.allow.allow with input as {"principal": p, "action": a}

# --- reads ---

test_staff_may_read if {
	every p in [dealer, approver, admin] {
		every a in reads {
			allowed(p, a)
		}
	}
}

test_unrelated_role_may_not_read if {
	every a in reads {
		not allowed(nobody, a)
	}
}

# --- dealer ---

test_dealer_may_draft_submit_cancel if {
	every a in dealer_writes | {"treasury.deal.cancel"} {
		allowed(dealer, a)
	}
}

test_dealer_may_not_approve_or_settle if {
	every a in approver_writes {
		not allowed(dealer, a)
	}
}

# --- approver ---

test_approver_may_approve_reject_settle_mature_reverse_cancel if {
	every a in approver_writes | {"treasury.deal.cancel"} {
		allowed(approver, a)
	}
}

test_approver_may_not_draft_or_submit if {
	every a in dealer_writes {
		not allowed(approver, a)
	}
}

# --- nobody else writes ---

test_admin_and_unrelated_role_may_not_write if {
	every p in [admin, nobody] {
		every a in all_writes {
			not allowed(p, a)
		}
	}
}

# Remove the service-account exclusion and this goes red: the machine holds both treasury roles.
test_shared_service_account_denied_every_write if {
	every a in all_writes {
		not allowed(shared_sa, a)
	}
}

test_shared_service_account_gets_no_treasury_reason if {
	every a in all_writes | reads {
		count({r | some r in rest.allowed_reasons with input as {"principal": shared_sa, "action": a}; startswith(r, "treasury-")}) == 0
	}
}

# ADR-0315 D3/D10: this file grants an agent NOTHING — not even the draft. The agent's draft grant
# comes only from its agents.yaml charter via rest.rego's agent-charter-allows (asserted against the
# real charter in openbank-infra/opa/build-bundle.sh). This trio loads no agents data, so any allow
# an AI_AGENT gets here would have to come from a treasury rule — which is the regression to catch:
# add `treasury.deal.approve` for AI_AGENT to any rule above and this goes red.
test_ai_agent_denied_every_treasury_action_by_this_file if {
	every a in (all_writes | reads) | {"treasury.deal.override-limit", "treasury.nostro.upload"} {
		not allowed(agent, a)
	}
}

test_ai_agent_gets_no_treasury_reason if {
	every a in (all_writes | reads) | {"treasury.deal.override-limit", "treasury.nostro.upload"} {
		count({r | some r in rest.allowed_reasons with input as {"principal": agent, "action": a}; startswith(r, "treasury-")}) == 0
	}
}

# The charter-shaped agent id, with the most roles a treasury agent client could be handed. The
# must-DENY half at the policy layer, as the agent itself.
chartered_agent := {
	"type": "AI_AGENT",
	"id": "agent:treasury-dealing-assistant",
	"roles": ["ROLE_TREASURY_DEALER", "ROLE_TREASURY_APPROVER", "ROLE_TREASURY_SENIOR_APPROVER"],
}

test_chartered_agent_may_not_approve_settle_override_or_upload if {
	every a in {"treasury.deal.approve", "treasury.deal.settle", "treasury.deal.override-limit", "treasury.nostro.upload", "treasury.deal.submit"} {
		not allowed(chartered_agent, a)
	}
}

# ADR-0315 D2/D3: counterparty confirmation is a back-office step. Must-ALLOW the approver; must-DENY
# the dealer, the senior (who overrides limits, not confirms), an admin, the shared service-account
# holding every treasury role, and an AI agent holding every treasury role. Drop "treasury.deal.confirm"
# from the approver rule and the first assertion goes red; grant it to any other principal and one
# of the rest does.
test_only_a_human_approver_may_confirm if {
	allowed(approver, "treasury.deal.confirm")
	every p in [dealer, senior, admin, nobody, shared_sa, agent, chartered_agent] {
		not allowed(p, "treasury.deal.confirm")
	}
}

# --- ADR-0315 D4: senior limit override ---

test_senior_may_override_and_read if {
	allowed(senior, "treasury.deal.override-limit")
	every a in reads { allowed(senior, a) }
}

test_only_the_senior_may_override if {
	every p in [dealer, approver, admin, nobody] { not allowed(p, "treasury.deal.override-limit") }
}

test_senior_may_not_book_or_draft if {
	every a in all_writes { not allowed(senior, a) }
}

test_machines_and_agents_may_not_override if {
	sa := object.union(shared_sa, {"roles": ["ROLE_TREASURY_SENIOR_APPROVER"]})
	not allowed(sa, "treasury.deal.override-limit")
	ag := object.union(agent, {"roles": ["ROLE_TREASURY_SENIOR_APPROVER"]})
	not allowed(ag, "treasury.deal.override-limit")
}

# --- nostro reconciliation (#10896) ---

test_approver_may_upload_nostro_statement if {
	allowed(approver, "treasury.nostro.upload")
}

test_nobody_else_may_upload_nostro_statement if {
	every p in [dealer, senior, nobody, shared_sa, agent] {
		not allowed(p, "treasury.nostro.upload")
	}
}

# --- portfolio (ADR-0337 amendment): the one treasury read a machine reaches ---

portfolio_excluded := {"authz": {"operator_read_any_excluded_actions": ["treasury.portfolio.read"]}}

tax_reporting := {"type": "HUMAN", "id": "service-account-openbank-tax-reporting", "roles": ["ROLE_API"]}

portfolio_allowed(p, a) if rest.allow.allow with input as {"principal": p, "action": a} with data.rules as portfolio_excluded

test_tax_reporting_may_read_portfolio if {
	portfolio_allowed(tax_reporting, "treasury.portfolio.read")
}

test_tax_reporting_reads_nothing_else if {
	not portfolio_allowed(tax_reporting, "treasury.portfolio.upload")
	every a in reads {
		not portfolio_allowed(tax_reporting, a)
	}
}

# Without ROLE_API (the role the realm grants that client) the identity alone is not enough.
test_tax_reporting_identity_without_api_role_denied if {
	not portfolio_allowed({"type": "HUMAN", "id": "service-account-openbank-tax-reporting", "roles": []}, "treasury.portfolio.read")
}

test_treasury_staff_may_read_portfolio if {
	every p in [dealer, approver, admin, senior] {
		portfolio_allowed(p, "treasury.portfolio.read")
	}
}

test_shared_m2m_other_service_account_and_agent_may_not_read_portfolio if {
	not portfolio_allowed({"type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_OPERATOR", "ROLE_API", "ROLE_COMPLIANCE"]}, "treasury.portfolio.read")
	not portfolio_allowed({"type": "HUMAN", "id": "service-account-openbank-pension", "roles": ["ROLE_API", "ROLE_OPERATOR"]}, "treasury.portfolio.read")
	not portfolio_allowed(shared_sa, "treasury.portfolio.read")
	not portfolio_allowed({"type": "AI_AGENT", "id": "agent:treasury-drafter", "roles": ["ROLE_API", "ROLE_TREASURY_DEALER"]}, "treasury.portfolio.read")
	not portfolio_allowed(nobody, "treasury.portfolio.read")
}

# compliance-read-any honours the exclusion too: a client holding ROLE_COMPLIANCE is still a machine.
test_compliance_role_does_not_reach_portfolio if {
	not portfolio_allowed({"type": "HUMAN", "id": "service-account-openbank-aml", "roles": ["ROLE_COMPLIANCE"]}, "treasury.portfolio.read")
	not portfolio_allowed({"type": "HUMAN", "id": "carol", "roles": ["ROLE_COMPLIANCE"]}, "treasury.portfolio.read")
}

# The exclusion is what does the work: without it the base rules re-admit the shared account.
test_without_the_exclusion_the_base_rules_reopen_portfolio if {
	rest.allow.allow with input as {
		"principal": {"type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_OPERATOR"]},
		"action": "treasury.portfolio.read",
	}
		with data.rules as {}
}

test_only_approver_may_upload_portfolio if {
	allowed(approver, "treasury.portfolio.upload")
	not allowed(dealer, "treasury.portfolio.upload")
	not allowed(admin, "treasury.portfolio.upload")
	not allowed(shared_sa, "treasury.portfolio.upload")
	not allowed(agent, "treasury.portfolio.upload")
}
