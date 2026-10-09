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

test_edge_may_terminate if {
	rest.allow with input as {
		"principal": {"type": "HUMAN", "id": pension_edge, "roles": ["ROLE_API"]},
		"action": "pension.contract.terminate",
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
