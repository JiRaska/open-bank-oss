# SPDX-License-Identifier: Apache-2.0
# Held to a known-POSITIVE and a known-NEGATIVE: every allow case has a matching deny.

package openbank.rest_test

import data.openbank.rest
import rego.v1

excluded := {"authz": {"operator_read_any_excluded_actions": ["tax.filing.read"]}}

test_auditor_may_read_filings if {
	rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "audrey", "roles": ["ROLE_AUDITOR"]},
		"action": "tax.filing.read",
	}
}

test_auditor_may_not_assemble if {
	not rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "audrey", "roles": ["ROLE_AUDITOR"]},
		"action": "tax.filing.assemble",
	}
}

test_operator_may_assemble_and_file if {
	rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "alice", "roles": ["ROLE_OPERATOR"]},
		"action": "tax.filing.assemble",
	}
	rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "alice", "roles": ["ROLE_OPERATOR"]},
		"action": "tax.filing.file",
	}
}

test_viewer_may_not_file if {
	not rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "victor", "roles": ["ROLE_VIEWER"]},
		"action": "tax.filing.file",
	}
}

test_service_account_with_operator_may_not_file if {
	not rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_OPERATOR", "ROLE_API"]},
		"action": "tax.filing.file",
	}
}

test_service_account_with_operator_may_not_assemble if {
	not rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_OPERATOR", "ROLE_API"]},
		"action": "tax.filing.assemble",
	}
}

# tax.filing.read is in rules.yaml authz.operator_read_any_excluded_actions, so a service-account
# holding ROLE_OPERATOR cannot read a return through base operator-read-any either.
test_service_account_with_operator_may_not_read if {
	not rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_API", "ROLE_OPERATOR"]},
		"action": "tax.filing.read",
	}
		with data.rules as excluded
}

# Staff still read with the exclusion in force.
test_auditor_reads_with_exclusion_in_force if {
	rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "audrey", "roles": ["ROLE_AUDITOR"]},
		"action": "tax.filing.read",
	}
		with data.rules as excluded
}

test_anonymous_may_not_read if {
	not rest.allow with input as {
		"principal": {"type": "ANONYMOUS", "id": "", "roles": []},
		"action": "tax.filing.read",
	}
}

# --- statutory returns (ADR-0336) ---------------------------------------------------------

sr_actions := {
	"tax.statutory-return.inspect",
	"tax.statutory-return.assemble",
	"tax.statutory-return.approve",
	"tax.statutory-return.submit",
}

sr_lifecycle := {"tax.statutory-return.assemble", "tax.statutory-return.approve", "tax.statutory-return.submit"}

test_human_operator_may_do_every_statutory_return_action if {
	every action in sr_actions {
		rest.allow with input as {"principal": {"type": "HUMAN", "id": "alice", "roles": ["ROLE_OPERATOR"]}, "action": action}
	}
}

test_admin_and_auditor_may_inspect_but_not_act if {
	every role in ["ROLE_ADMIN", "ROLE_AUDITOR", "ROLE_VIEWER", "ROLE_COMPLIANCE"] {
		rest.allow with input as {"principal": {"type": "HUMAN", "id": "audrey", "roles": [role]}, "action": "tax.statutory-return.inspect"}
	}
	every role in ["ROLE_ADMIN", "ROLE_AUDITOR", "ROLE_VIEWER", "ROLE_COMPLIANCE"] {
		every action in sr_lifecycle {
			not rest.allow with input as {"principal": {"type": "HUMAN", "id": "audrey", "roles": [role]}, "action": action}
		}
	}
}

test_shared_service_account_denied_every_statutory_return_action_with_any_role if {
	every role in ["ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_COMPLIANCE", "ROLE_AUDITOR", "ROLE_VIEWER", "ROLE_API"] {
		every action in sr_actions {
			not rest.allow with input as {
				"principal": {"type": "HUMAN", "id": "service-account-openbank-services", "roles": [role, "ROLE_OPERATOR", "ROLE_ADMIN"]},
				"action": action,
			}
		}
	}
}

test_other_service_account_denied_every_statutory_return_action if {
	every action in sr_actions {
		not rest.allow with input as {
			"principal": {"type": "HUMAN", "id": "service-account-openbank-pension", "roles": ["ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_COMPLIANCE"]},
			"action": action,
		}
	}
}

test_ai_agent_denied_every_statutory_return_action if {
	every action in sr_actions {
		not rest.allow with input as {"principal": {"type": "AI_AGENT", "id": "agent-x", "roles": ["ROLE_OPERATOR"]}, "action": action}
	}
}

# --- corporate register (PSP 32-04, 50-04, 40-01) ------------------------------------------

cr_actions := {
	"tax.corporate-register.inspect",
	"tax.corporate-register.propose",
	"tax.corporate-register.approve",
	"tax.corporate-register.reject",
}

cr_changes := {"tax.corporate-register.propose", "tax.corporate-register.approve", "tax.corporate-register.reject"}

test_human_operator_may_do_every_corporate_register_action if {
	every action in cr_actions {
		rest.allow with input as {"principal": {"type": "HUMAN", "id": "alice", "roles": ["ROLE_OPERATOR"]}, "action": action}
	}
}

test_oversight_roles_may_inspect_but_not_change_the_corporate_register if {
	every role in ["ROLE_ADMIN", "ROLE_AUDITOR", "ROLE_VIEWER", "ROLE_COMPLIANCE"] {
		rest.allow with input as {"principal": {"type": "HUMAN", "id": "audrey", "roles": [role]}, "action": "tax.corporate-register.inspect"}
	}
	every role in ["ROLE_ADMIN", "ROLE_AUDITOR", "ROLE_VIEWER", "ROLE_COMPLIANCE", "ROLE_API"] {
		every action in cr_changes {
			not rest.allow with input as {"principal": {"type": "HUMAN", "id": "audrey", "roles": [role]}, "action": action}
		}
	}
}

test_shared_service_account_denied_every_corporate_register_action_with_any_role if {
	every role in ["ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_COMPLIANCE", "ROLE_AUDITOR", "ROLE_VIEWER", "ROLE_API"] {
		every action in cr_actions {
			not rest.allow with input as {
				"principal": {"type": "HUMAN", "id": "service-account-openbank-services", "roles": [role, "ROLE_OPERATOR", "ROLE_ADMIN"]},
				"action": action,
			}
		}
	}
}

test_other_service_account_denied_every_corporate_register_action if {
	every action in cr_actions {
		not rest.allow with input as {
			"principal": {"type": "HUMAN", "id": "service-account-openbank-pension", "roles": ["ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_COMPLIANCE"]},
			"action": action,
		}
	}
}

test_ai_agent_denied_every_corporate_register_action if {
	every action in cr_actions {
		not rest.allow with input as {"principal": {"type": "AI_AGENT", "id": "agent-x", "roles": ["ROLE_OPERATOR"]}, "action": action}
	}
}
