# SPDX-License-Identifier: Apache-2.0
# Held to a known-POSITIVE and a known-NEGATIVE: every allow case has a matching deny.

package openbank.rest_test

import data.openbank.rest
import rego.v1

test_auditor_may_read_filings if {
	rest.allow with input as {
		"principal": {"type": "HUMAN", "service_account": false, "id": "audrey", "roles": ["ROLE_AUDITOR"]},
		"action": "tax.filing.inspect",
	}
}

test_auditor_may_not_assemble if {
	not rest.allow with input as {
		"principal": {"type": "HUMAN", "service_account": false, "id": "audrey", "roles": ["ROLE_AUDITOR"]},
		"action": "tax.filing.assemble",
	}
}

test_operator_may_assemble_and_file if {
	rest.allow with input as {
		"principal": {"type": "HUMAN", "service_account": false, "id": "alice", "roles": ["ROLE_OPERATOR"]},
		"action": "tax.filing.assemble",
	}
	rest.allow with input as {
		"principal": {"type": "HUMAN", "service_account": false, "id": "alice", "roles": ["ROLE_OPERATOR"]},
		"action": "tax.filing.file",
	}
}

test_viewer_may_not_file if {
	not rest.allow with input as {
		"principal": {"type": "HUMAN", "service_account": false, "id": "victor", "roles": ["ROLE_VIEWER"]},
		"action": "tax.filing.file",
	}
}

test_service_account_with_operator_may_not_file if {
	not rest.allow with input as {
		"principal": {"type": "HUMAN", "service_account": true, "id": "service-account-openbank-services", "roles": ["ROLE_OPERATOR", "ROLE_API"]},
		"action": "tax.filing.file",
	}
}

test_service_account_with_operator_may_not_assemble if {
	not rest.allow with input as {
		"principal": {"type": "HUMAN", "service_account": true, "id": "service-account-openbank-services", "roles": ["ROLE_OPERATOR", "ROLE_API"]},
		"action": "tax.filing.assemble",
	}
}

# tax.filing.inspect is deliberately NOT a `.read`. The old `.read` remains only for already
# published images and must stay excluded from operator-read-any while both versions coexist.
# The per-service test runner loads the generated governance data at data.authz. Inject only the
# real exclusion list at data.rules.authz, matching the bundle's data.rules mount point.
test_legacy_staff_can_read_while_old_image_drains if {
	rest.allow with input as {
		"principal": {"type": "HUMAN", "service_account": false, "id": "audrey", "roles": ["ROLE_AUDITOR"]},
		"action": "tax.filing.read",
	}
}

test_legacy_read_is_denied_to_shared_service_account_with_staff_roles if {
	every role in ["ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_COMPLIANCE", "ROLE_AUDITOR", "ROLE_VIEWER"] {
		not rest.allow with data.rules.authz.operator_read_any_excluded_actions as data.authz.operator_read_any_excluded_actions with input as {
			"principal": {"type": "HUMAN", "service_account": true, "id": "service-account-openbank-services", "roles": ["ROLE_API", role]},
			"action": "tax.filing.read",
		}
	}
}

test_legacy_read_is_denied_to_shared_service_account_with_all_staff_roles if {
	not rest.allow with data.rules.authz.operator_read_any_excluded_actions as data.authz.operator_read_any_excluded_actions with input as {
		"principal": {
			"type": "HUMAN", "service_account": true, "id": "service-account-openbank-services",
			"roles": ["ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_COMPLIANCE", "ROLE_AUDITOR"],
		},
		"action": "tax.filing.read",
	}
}

test_shared_service_account_may_not_inspect_with_any_staff_role if {
	every role in ["ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_COMPLIANCE", "ROLE_AUDITOR", "ROLE_VIEWER"] {
		not rest.allow with input as {
			"principal": {"type": "HUMAN", "service_account": true, "id": "service-account-openbank-services", "roles": ["ROLE_API", role]},
			"action": "tax.filing.inspect",
		}
	}
}

test_shared_service_account_with_every_role_may_not_inspect if {
	not rest.allow with input as {
		"principal": {
			"type": "HUMAN", "service_account": true, "id": "service-account-openbank-services",
			"roles": ["ROLE_API", "ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_COMPLIANCE", "ROLE_AUDITOR"],
		},
		"action": "tax.filing.inspect",
	}
}

test_another_service_account_may_not_inspect if {
	not rest.allow with input as {
		"principal": {"type": "HUMAN", "service_account": true, "id": "service-account-openbank-ledger", "roles": ["ROLE_COMPLIANCE", "ROLE_OPERATOR"]},
		"action": "tax.filing.inspect",
	}
}

test_ai_agent_may_not_inspect if {
	not rest.allow with input as {
		"principal": {"type": "AI_AGENT", "id": "agent-compliance-copilot", "roles": ["ROLE_COMPLIANCE", "ROLE_OPERATOR"]},
		"action": "tax.filing.inspect",
	}
}

test_customer_may_not_inspect if {
	not rest.allow with input as {
		"principal": {"type": "HUMAN", "service_account": false, "id": "carol", "roles": ["ROLE_CUSTOMER"]},
		"action": "tax.filing.inspect",
	}
}

test_staff_may_inspect_with_each_staff_role if {
	every role in ["ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_AUDITOR", "ROLE_VIEWER", "ROLE_COMPLIANCE"] {
		rest.allow with input as {
			"principal": {"type": "HUMAN", "service_account": false, "id": "audrey", "roles": [role]},
			"action": "tax.filing.inspect",
		}
	}
}

test_anonymous_may_not_inspect if {
	not rest.allow with input as {
		"principal": {"type": "ANONYMOUS", "id": "", "roles": []},
		"action": "tax.filing.inspect",
	}
}

# Verified machine flag (input.principal.service_account), #12537: a machine token whose NAME is
# not `service-account-*` (client_id-only token, renamed account) must not pass as staff.
test_machine_token_with_a_human_looking_name_may_not_read_or_file if {
	every action in {"tax.filing.inspect", "tax.filing.read", "tax.filing.assemble", "tax.filing.file"} {
		not rest.allow with data.rules.authz.operator_read_any_excluded_actions as data.authz.operator_read_any_excluded_actions with input as {
			"principal": {
				"type": "HUMAN", "id": "batch-bot", "roles": ["ROLE_OPERATOR", "ROLE_AUDITOR"],
				"service_account": true, "client_id": "openbank-batch",
			},
			"action": action,
		}
	}
}

# Once the PEP sends the flag the name is never consulted: a person whose username happens to
# start with `service-account-` is a person.
test_human_named_like_a_service_account_is_staff_when_the_flag_says_human if {
	rest.allow with input as {
		"principal": {
			"type": "HUMAN", "id": "service-account-lookalike", "roles": ["ROLE_OPERATOR"],
			"service_account": false,
		},
		"action": "tax.filing.file",
	}
}

# Fail closed: a PEP that sends no flag is a machine for every staff rule, whatever the name.
test_absent_service_account_field_denies_every_staff_rule if {
	every id in {"alice", "batch-bot"} {
		every action in {"tax.filing.inspect", "tax.filing.read", "tax.filing.assemble", "tax.filing.file"} {
			not rest.allow with data.rules.authz.operator_read_any_excluded_actions as data.authz.operator_read_any_excluded_actions with input as {
				"principal": {"type": "HUMAN", "id": id, "roles": ["ROLE_OPERATOR", "ROLE_AUDITOR"]},
				"action": action,
			}
		}
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
		rest.allow with input as {"principal": {"type": "HUMAN", "id": "alice", "roles": ["ROLE_OPERATOR"], "service_account": false}, "action": action}
	}
}

test_admin_and_auditor_may_inspect_but_not_act if {
	every role in ["ROLE_ADMIN", "ROLE_AUDITOR", "ROLE_VIEWER", "ROLE_COMPLIANCE"] {
		rest.allow with input as {"principal": {"type": "HUMAN", "id": "audrey", "roles": [role], "service_account": false}, "action": "tax.statutory-return.inspect"}
	}
	every role in ["ROLE_ADMIN", "ROLE_AUDITOR", "ROLE_VIEWER", "ROLE_COMPLIANCE"] {
		every action in sr_lifecycle {
			not rest.allow with input as {"principal": {"type": "HUMAN", "id": "audrey", "roles": [role], "service_account": false}, "action": action}
		}
	}
}

test_shared_service_account_denied_every_statutory_return_action_with_any_role if {
	every role in ["ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_COMPLIANCE", "ROLE_AUDITOR", "ROLE_VIEWER", "ROLE_API"] {
		every action in sr_actions {
			not rest.allow with input as {
				"principal": {"type": "HUMAN", "id": "service-account-openbank-services", "roles": [role, "ROLE_OPERATOR", "ROLE_ADMIN"], "service_account": true},
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
		not rest.allow with input as {"principal": {"type": "AI_AGENT", "id": "agent-x", "roles": ["ROLE_OPERATOR"], "service_account": false}, "action": action}
	}
}
