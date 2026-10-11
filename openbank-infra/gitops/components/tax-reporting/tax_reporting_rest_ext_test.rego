# SPDX-License-Identifier: Apache-2.0
# Held to a known-POSITIVE and a known-NEGATIVE: every allow case has a matching deny.

package openbank.rest_test

import data.openbank.rest
import rego.v1

excluded := {"authz": {"operator_read_any_excluded_actions": ["tax.filing.read"]}}

test_auditor_may_read_filings if {
	rest.allow with input as {
		"principal": {"service_account": false, "type": "HUMAN", "id": "audrey", "roles": ["ROLE_AUDITOR"]},
		"action": "tax.filing.read",
	}
}

test_auditor_may_not_assemble if {
	not rest.allow with input as {
		"principal": {"service_account": false, "type": "HUMAN", "id": "audrey", "roles": ["ROLE_AUDITOR"]},
		"action": "tax.filing.assemble",
	}
}

test_operator_may_assemble_and_file if {
	rest.allow with input as {
		"principal": {"service_account": false, "type": "HUMAN", "id": "alice", "roles": ["ROLE_OPERATOR"]},
		"action": "tax.filing.assemble",
	}
	rest.allow with input as {
		"principal": {"service_account": false, "type": "HUMAN", "id": "alice", "roles": ["ROLE_OPERATOR"]},
		"action": "tax.filing.file",
	}
}

test_viewer_may_not_file if {
	not rest.allow with input as {
		"principal": {"service_account": false, "type": "HUMAN", "id": "victor", "roles": ["ROLE_VIEWER"]},
		"action": "tax.filing.file",
	}
}

test_service_account_with_operator_may_not_file if {
	not rest.allow with input as {
		"principal": {"service_account": true, "type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_OPERATOR", "ROLE_API"]},
		"action": "tax.filing.file",
	}
}

test_service_account_with_operator_may_not_assemble if {
	not rest.allow with input as {
		"principal": {"service_account": true, "type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_OPERATOR", "ROLE_API"]},
		"action": "tax.filing.assemble",
	}
}

# tax.filing.read is in rules.yaml authz.operator_read_any_excluded_actions, so a service-account
# holding ROLE_OPERATOR cannot read a return through base operator-read-any either.
test_service_account_with_operator_may_not_read if {
	not rest.allow with input as {
		"principal": {"service_account": true, "type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_API", "ROLE_OPERATOR"]},
		"action": "tax.filing.read",
	}
		with data.rules as excluded
}

# Staff still read with the exclusion in force.
test_auditor_reads_with_exclusion_in_force if {
	rest.allow with input as {
		"principal": {"service_account": false, "type": "HUMAN", "id": "audrey", "roles": ["ROLE_AUDITOR"]},
		"action": "tax.filing.read",
	}
		with data.rules as excluded
}

test_anonymous_may_not_read if {
	not rest.allow with input as {
		"principal": {"service_account": false, "type": "ANONYMOUS", "id": "", "roles": []},
		"action": "tax.filing.read",
	}
}

# Verified machine flag (input.principal.service_account): a machine token whose NAME is not
# `service-account-*` (client_id-only token, renamed account) must not pass as staff.
test_machine_token_with_a_human_looking_name_may_not_read_or_file if {
	every action in {"tax.filing.read", "tax.filing.assemble", "tax.filing.file"} {
		not rest.allow with input as {
			"principal": {
				"type": "HUMAN", "id": "batch-bot", "roles": ["ROLE_OPERATOR", "ROLE_AUDITOR"],
				"service_account": true, "client_id": "openbank-batch",
			},
			"action": action,
		}
			with data.rules as excluded
	}
}

test_verified_human_operator_still_files if {
	rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "alice", "roles": ["ROLE_OPERATOR"], "service_account": false},
		"action": "tax.filing.file",
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

test_absent_service_account_field_denies_every_staff_rule if {
	every action in {"tax.filing.read", "tax.filing.assemble", "tax.filing.file"} {
		not rest.allow with input as {
			"principal": {"type": "HUMAN", "id": "alice", "roles": ["ROLE_OPERATOR", "ROLE_AUDITOR"]},
			"action": action,
		}
			with data.rules as excluded
	}
}
