# SPDX-License-Identifier: MPL-2.0
# Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
#
# Unit tests for the REST authorization policy (ADR-0034 D1). Run from repo root:
#   opa test openbank-libs/governance/policies
#
# Mirrors agents_test.rego patterns. Mocks the rules.yaml + bundle-version data so
# the policy is verified in isolation, before it is shipped into a live OPA sidecar.
# The AI_AGENT branch (which delegates to data.openbank.agents.charter_allowed) is
# covered by agents_test.rego — repeating that surface here would just couple the
# two suites.

package openbank.rest_test

import data.openbank.rest

# Mock for rules.yaml bits the policy reads.
rules := {
	"money_path_services": ["ledger", "sepa-payment", "card-payment"],
	"four_eyes": {"verbs": ["transfer", "freeze", "post"]},
}

# Mock mirroring the REAL rules.yaml shape — money_path_services uses the module name
# (openbank-ledger-service), which rest.rego normalises to the action scope (ledger).
# Also carries the feature_flags block (issue #419).
rules_real := {
	"money_path_services": ["openbank-ledger-service", "openbank-sepa-payment"],
	"four_eyes": {"verbs": ["transfer", "post", "flip"]},
	"feature_flags": {
		"prohibited_flag_combinations": [
			"sca-enforcement-disabled",
			"sanctions-screening-disabled",
			"aml-screening-disabled",
			"payment-gate-fail-open",
		],
		"money_path_flags": ["instant-payments-enabled", "fx-revaluation-enabled"],
	},
}

bundle := {"version": "v0.0.0-test"}

# ---------------------------------------------------------------------------------------
# Default-deny (ADR-0034 D1): no rule, no allow.
# ---------------------------------------------------------------------------------------
test_default_deny if {
	not rest.allow with input as {
		"principal": {"id": "user-1", "type": "HUMAN", "roles": []},
		"action": "party.update",
		"resource": {"type": "party", "id": "p-1"},
	}
}

# ---------------------------------------------------------------------------------------
# operator-on-own-tenant: ROLE_OPERATOR may act on resources in their own tenant.
# ---------------------------------------------------------------------------------------
test_allow_operator_on_own_tenant if {
	decision := rest.allow with input as {
		"principal": {"id": "user-1", "type": "HUMAN", "roles": ["ROLE_OPERATOR"], "attributes": {"tenant": "t-1"}},
		"action": "party.update",
		"resource": {"type": "party", "id": "p-1", "attributes": {"tenant": "t-1"}},
	}
		with data.openbank.bundle as bundle

	decision.allow == true
	decision.reason == "operator-on-own-tenant"
	decision.policy_version == "v0.0.0-test"
}

# Cross-tenant access is denied — operator-on-own-tenant rule requires matching tenants.
test_deny_operator_on_other_tenant if {
	not rest.allow with input as {
		"principal": {"id": "user-1", "type": "HUMAN", "roles": ["ROLE_OPERATOR"], "attributes": {"tenant": "t-1"}},
		"action": "party.update",
		"resource": {"type": "party", "id": "p-1", "attributes": {"tenant": "t-2"}},
	}
}

# ---------------------------------------------------------------------------------------
# compliance-read-any: ROLE_COMPLIANCE may read across tenants (audit duty).
# ---------------------------------------------------------------------------------------
test_allow_compliance_read_any if {
	decision := rest.allow with input as {
		"principal": {"id": "user-c", "type": "HUMAN", "roles": ["ROLE_COMPLIANCE"]},
		"action": "party.read",
		"resource": {"type": "party", "id": "p-1"},
	}
		with data.openbank.bundle as bundle

	decision.allow == true
	decision.reason == "compliance-read-any"
}

# Compliance role cannot write — only the *.read suffix is permitted by this rule.
test_deny_compliance_write if {
	not rest.allow with input as {
		"principal": {"id": "user-c", "type": "HUMAN", "roles": ["ROLE_COMPLIANCE"]},
		"action": "party.update",
		"resource": {"type": "party", "id": "p-1"},
	}
}

# ---------------------------------------------------------------------------------------
# party-self-service: a party may list/read its own resources.
# Covers the device.list action added in feat/sca-device-enrolled-event (ADR-0068).
# ---------------------------------------------------------------------------------------
test_allow_party_list_own_devices if {
	partyId := "550e8400-e29b-41d4-a716-446655440000"
	decision := rest.allow with input as {
		"principal": {"id": partyId, "type": "HUMAN", "roles": [], "attributes": {}},
		"action": "device.list",
		"resource": {"type": "device", "id": partyId},
	}
		with data.openbank.bundle as bundle

	decision.allow == true
	decision.reason == "party-self-service"
}

# A party may not list another party's devices.
test_deny_party_list_other_party_devices if {
	not rest.allow with input as {
		"principal": {"id": "party-a", "type": "HUMAN", "roles": [], "attributes": {}},
		"action": "device.list",
		"resource": {"type": "device", "id": "party-b"},
	}
}

# party-self-service covers read verbs too.
test_allow_party_read_own_resource if {
	partyId := "550e8400-e29b-41d4-a716-446655440001"
	decision := rest.allow with input as {
		"principal": {"id": partyId, "type": "HUMAN", "roles": [], "attributes": {}},
		"action": "party.read",
		"resource": {"type": "party", "id": partyId},
	}
		with data.openbank.bundle as bundle

	decision.allow == true
	decision.reason == "party-self-service"
}

# party-self-service does NOT cover mutation verbs (enroll, update, …).
test_deny_party_self_service_does_not_cover_enroll if {
	partyId := "550e8400-e29b-41d4-a716-446655440002"
	not rest.allow with input as {
		"principal": {"id": partyId, "type": "HUMAN", "roles": [], "attributes": {}},
		"action": "device.enroll",
		"resource": {"type": "device", "id": partyId},
	}
}

# ---------------------------------------------------------------------------------------
# operator-read-any: operators and admins may list/read any resource.
# ---------------------------------------------------------------------------------------
test_allow_operator_list_any_devices if {
	decision := rest.allow with input as {
		"principal": {"id": "operator-1", "type": "HUMAN", "roles": ["ROLE_OPERATOR"], "attributes": {}},
		"action": "device.list",
		"resource": {"type": "device", "id": "any-party-id"},
	}
		with data.openbank.bundle as bundle

	decision.allow == true
	decision.reason == "operator-read-any"
}

test_allow_admin_list_any_devices if {
	decision := rest.allow with input as {
		"principal": {"id": "admin-1", "type": "HUMAN", "roles": ["ROLE_ADMIN"], "attributes": {}},
		"action": "device.list",
		"resource": {"type": "device", "id": "any-party-id"},
	}
		with data.openbank.bundle as bundle

	decision.allow == true
	decision.reason == "operator-read-any"
}

# operator-read-any does NOT cover mutation verbs.
test_deny_operator_read_any_does_not_cover_write if {
	not rest.allow with input as {
		"principal": {"id": "operator-1", "type": "HUMAN", "roles": ["ROLE_OPERATOR"], "attributes": {}},
		"action": "device.enroll",
		"resource": {"type": "device", "id": "any-party-id"},
	}
}

# ---------------------------------------------------------------------------------------
# four_eyes_required: money-path verbs surface a flag the handler must honour.
# Tests the augmentation surface — separate from allow/deny.
# ---------------------------------------------------------------------------------------
test_four_eyes_required_for_ledger_post if {
	rest.four_eyes_required with input as {"action": "ledger.post"}
		with data.rules as rules
}

test_four_eyes_required_for_sepa_transfer if {
	rest.four_eyes_required with input as {"action": "sepa-payment.transfer"}
		with data.rules as rules
}

# Non-money-path action — no four-eyes flag.
test_four_eyes_not_required_for_party_update if {
	not rest.four_eyes_required with input as {"action": "party.update"}
		with data.rules as rules
}

# Money-path service but non-money-path verb — no flag (only listed verbs trigger it).
test_four_eyes_not_required_for_ledger_read if {
	not rest.four_eyes_required with input as {"action": "ledger.read"}
		with data.rules as rules
}

# ---------------------------------------------------------------------------------------
# Name normalisation (issue #419): the REAL rules.yaml lists money_path_services as module
# names (openbank-ledger-service); rest.rego must normalise them to the action scope so the
# four-eyes prefix match still fires. Without normalisation these would silently not match.
# ---------------------------------------------------------------------------------------
test_four_eyes_required_with_real_module_names if {
	rest.four_eyes_required with input as {"action": "ledger.post"}
		with data.rules as rules_real
}

test_four_eyes_required_sepa_payment_real_names if {
	rest.four_eyes_required with input as {"action": "sepa-payment.transfer"}
		with data.rules as rules_real
}

# ---------------------------------------------------------------------------------------
# Feature-flag flip gating (ADR-0067 / issue #419).
# ---------------------------------------------------------------------------------------
test_four_eyes_required_for_money_path_flag_flip if {
	rest.four_eyes_required with input as {
		"action": "featureflag.flip",
		"attributes": {"flag": "instant-payments-enabled"},
	}
		with data.rules as rules_real
}

test_four_eyes_not_required_for_benign_flag_flip if {
	not rest.four_eyes_required with input as {
		"action": "featureflag.flip",
		"attributes": {"flag": "ui-dark-mode"},
	}
		with data.rules as rules_real
}

# A flip that would disable a regulatory safety control is prohibited outright.
test_prohibited_disabling_sca if {
	rest.prohibited with input as {
		"action": "featureflag.flip",
		"attributes": {"flag": "sca-enforcement-disabled"},
	}
		with data.rules as rules_real
}

test_prohibited_disabling_sanctions_screening if {
	rest.prohibited with input as {
		"action": "featureflag.flip",
		"attributes": {"flag": "sanctions-screening-disabled"},
	}
		with data.rules as rules_real
}

# A benign flip is not prohibited.
test_benign_flag_flip_not_prohibited if {
	not rest.prohibited with input as {
		"action": "featureflag.flip",
		"attributes": {"flag": "instant-payments-enabled"},
	}
		with data.rules as rules_real
}

test_prohibited_disabling_aml_screening if {
	rest.prohibited with input as {
		"action": "featureflag.flip",
		"attributes": {"flag": "aml-screening-disabled"},
	}
		with data.rules as rules_real
}

test_prohibited_payment_gate_fail_open if {
	rest.prohibited with input as {
		"action": "featureflag.flip",
		"attributes": {"flag": "payment-gate-fail-open"},
	}
		with data.rules as rules_real
}

# A prohibited flag flip yields no allow reason — default-deny blocks it even without a
# dedicated deny rule (belt and braces with `prohibited`).
test_prohibited_flip_is_not_allowed if {
	not rest.allow with input as {
		"principal": {"id": "op-1", "type": "HUMAN", "roles": ["ROLE_ADMIN"], "attributes": {}},
		"action": "featureflag.flip",
		"resource": {"type": "feature-flag", "id": "sca-enforcement-disabled"},
		"attributes": {"flag": "sca-enforcement-disabled"},
	}
		with data.rules as rules_real
}

# F1 defense-in-depth (review #637): an operator whose tenant matches a tenant-enriched
# resource must STILL be denied a prohibited flip — the `not prohibited` guard on the allow
# head blocks every reason, so operator-on-own-tenant cannot grant it.
test_operator_own_tenant_cannot_flip_prohibited if {
	not rest.allow with input as {
		"principal": {"id": "op-1", "type": "HUMAN", "roles": ["ROLE_OPERATOR"], "attributes": {"tenant": "t-1"}},
		"action": "featureflag.flip",
		"resource": {"type": "feature-flag", "id": "sca-enforcement-disabled", "attributes": {"tenant": "t-1"}},
		"attributes": {"flag": "sca-enforcement-disabled"},
	}
		with data.openbank.bundle as bundle
		with data.rules as rules_real
}

# A non-prohibited money-path flip by an operator on their own tenant is still allowed
# (the guard only blocks PROHIBITED actions, not all flips) — confirms no over-blocking.
test_operator_own_tenant_may_flip_non_prohibited if {
	decision := rest.allow with input as {
		"principal": {"id": "op-1", "type": "HUMAN", "roles": ["ROLE_OPERATOR"], "attributes": {"tenant": "t-1"}},
		"action": "featureflag.flip",
		"resource": {"type": "feature-flag", "id": "instant-payments-enabled", "attributes": {"tenant": "t-1"}},
		"attributes": {"flag": "instant-payments-enabled"},
	}
		with data.openbank.bundle as bundle
		with data.rules as rules_real

	decision.allow == true
}
