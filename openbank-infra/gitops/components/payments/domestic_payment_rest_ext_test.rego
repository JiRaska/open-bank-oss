# SPDX-License-Identifier: Apache-2.0
# Run: opa test openbank-libs/governance/policies/rest.rego \
#   openbank-infra/gitops/components/payments/domestic_payment_rest_ext.rego \
#   openbank-infra/gitops/components/payments/domestic_payment_rest_ext_test.rego

package openbank.rest_test

import rego.v1

import data.openbank.rest

test_verified_edge_may_look_up_receipt if {
	rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "service-account-openbank-edge", "roles": ["ROLE_OPERATOR"]},
		"action": "domestic-payment.receipt.read",
	}
}

test_other_operator_service_account_cannot_look_up_receipt if {
	not rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_OPERATOR"]},
		"action": "domestic-payment.receipt.read",
	}
}

test_other_compliance_service_account_cannot_look_up_receipt if {
	not rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "service-account-other", "roles": ["ROLE_COMPLIANCE"]},
		"action": "domestic-payment.receipt.read",
	}
}

test_human_payments_operator_may_look_up_receipt if {
	rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "human-operator", "roles": ["ROLE_PAYMENTS"]},
		"action": "domestic-payment.receipt.read",
	}
}

test_edge_m2m_only_has_create_read_and_receipt if {
	rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "service-account-openbank-edge", "roles": ["ROLE_OPERATOR"]},
		"action": "domestic-payment.create",
	}
	rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "service-account-openbank-edge", "roles": ["ROLE_OPERATOR"]},
		"action": "domestic-payment.read",
	}
	not rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "service-account-openbank-edge", "roles": ["ROLE_OPERATOR"]},
		"action": "domestic-payment.list",
	}
	not rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "service-account-openbank-edge", "roles": ["ROLE_OPERATOR"]},
		"action": "domestic-payment.transitionStatus",
	}
}

test_other_operator_m2m_cannot_use_domestic_money_path if {
	not rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_OPERATOR"]},
		"action": "domestic-payment.create",
	}
	not rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_OPERATOR"]},
		"action": "domestic-payment.read",
	}
	not rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_OPERATOR"]},
		"action": "domestic-payment.transitionStatus",
	}
}

test_human_operator_and_viewer_keep_legitimate_access if {
	rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "human-operator", "roles": ["ROLE_OPERATOR"]},
		"action": "domestic-payment.transitionStatus",
	}
	rest.allow with input as {
		"principal": {"type": "HUMAN", "id": "human-viewer", "roles": ["ROLE_VIEWER"]},
		"action": "domestic-payment.list",
	}
}
