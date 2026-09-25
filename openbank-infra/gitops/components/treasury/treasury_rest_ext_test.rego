# SPDX-License-Identifier: Apache-2.0
# Held to a known-POSITIVE and a known-NEGATIVE for every action: a policy test that can only show
# what it permits is decoration.

package openbank.rest_test

import data.openbank.rest
import rego.v1

dealer := {"type": "HUMAN", "id": "dealer-1", "roles": ["ROLE_TREASURY_DEALER"]}

approver := {"type": "HUMAN", "id": "approver-1", "roles": ["ROLE_TREASURY_APPROVER"]}

admin := {"type": "HUMAN", "id": "admin-1", "roles": ["ROLE_ADMIN"]}

nobody := {"type": "HUMAN", "id": "teller-1", "roles": ["ROLE_CUSTOMER"]}

# The shared backend client, given the approver role by mistake: still a machine, still denied.
shared_sa := {
	"type": "HUMAN",
	"id": "service-account-openbank-services",
	"roles": ["ROLE_TREASURY_APPROVER", "ROLE_TREASURY_DEALER", "ROLE_OPERATOR"],
}

agent := {"type": "AI_AGENT", "id": "agent:treasury-drafter", "roles": ["ROLE_TREASURY_DEALER", "ROLE_TREASURY_APPROVER"]}

reads := {"treasury.deal.read", "treasury.counterparty.read", "treasury.position.read"}

dealer_writes := {"treasury.deal.draft", "treasury.deal.submit"}

approver_writes := {"treasury.deal.approve", "treasury.deal.reject", "treasury.deal.settle", "treasury.deal.mature", "treasury.deal.reverse"}

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

# ADR-0315 D3/D10: no charter yet, so an agent cannot draft, let alone approve or settle.
test_ai_agent_denied_draft_approve_settle if {
	every a in {"treasury.deal.draft", "treasury.deal.approve", "treasury.deal.settle"} {
		not allowed(agent, a)
	}
}
