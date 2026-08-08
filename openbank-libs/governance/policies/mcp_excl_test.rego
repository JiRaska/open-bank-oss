package openbank.rest_mcp_excl_test

import data.openbank.rest
import rego.v1

test_service_account_denied_mcp_session if {
	not "operator-mcp-session" in rest.allowed_reasons with input as {
		"principal": {"type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_OPERATOR"]},
		"action": "mcp.session.create",
	}
}

test_real_operator_still_allowed if {
	"operator-mcp-session" in rest.allowed_reasons with input as {
		"principal": {"type": "HUMAN", "id": "jiri", "roles": ["ROLE_OPERATOR"]},
		"action": "mcp.session.create",
	}
}
