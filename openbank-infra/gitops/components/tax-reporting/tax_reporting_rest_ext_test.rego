# SPDX-License-Identifier: Apache-2.0
# Held to a known-POSITIVE and a known-NEGATIVE: every allow case has a matching deny.

package openbank.rest_test

import data.openbank.rest
import rego.v1

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

# The ext grants reads to no machine. (A service-account holding ROLE_OPERATOR can still read
# through base rest.rego's operator read rule — that is base policy, not this extension.)
test_edge_service_account_may_not_read if {
	not rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "service-account-openbank-edge", "roles": ["ROLE_API"]},
		"action": "tax.filing.read",
	}
}

test_anonymous_may_not_read if {
	not rest.allow with input as {
		"principal": {"type": "ANONYMOUS", "id": "", "roles": []},
		"action": "tax.filing.read",
	}
}
