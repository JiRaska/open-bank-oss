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
