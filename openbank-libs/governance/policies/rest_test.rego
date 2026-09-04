# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# Unit tests for the REST authorization policy (ADR-0034 D1). Run from repo root:
#   opa test openbank-libs/governance/policies openbank-infra/opa/policies
# (both directories: the agent-charter-allows tests below delegate into
# data.openbank.agents, defined in openbank-infra/opa/policies/agents.rego -- running this
# directory alone leaves that package undefined and those tests fail. build-bundle.sh
# already runs both together; this is only a trap for `opa test` invoked by hand on just
# this directory, as the comment on line 5 used to suggest.)
#
# Mirrors agents_test.rego patterns. Mocks the rules.yaml + bundle-version data so
# the policy is verified in isolation, before it is shipped into a live OPA sidecar.
# The AI_AGENT branch's charter_allowed rule VARIATIONS (which tool bridges to which
# domain, read-vs-write scoping) are covered by agents_test.rego — repeating that
# surface here would just couple the two suites. What IS covered here is the
# end-to-end wiring of that delegation through rest.allow itself (the
# input.tool := input.action translation) — the exact integration a REST-action /
# tool-tier vocabulary mismatch used to break silently.

package openbank.rest_test

import data.openbank.rest

# Mock for rules.yaml bits the policy reads.
rules := {
	"money_path_services": ["ledger", "sepa-payment", "card-payment"],
	"four_eyes": {"verbs": ["transfer", "freeze", "post"]},
}

# Mock mirroring the REAL rules.yaml shape — money_path_services uses the module name
# (openbank-ledger-service), which rest.rego normalises to the action scope (ledger), or
# the money_path_action_prefixes override for the 5 services whose real @Authorize
# prefix differs from that derived name (issue #395). Also carries the feature_flags
# block (issue #419).
rules_real := {
	"money_path_services": [
		"openbank-ledger-service",
		"openbank-sepa-payment",
		"openbank-sepa-instant",
		"openbank-domestic-payment",
		"openbank-clearing-service",
		"openbank-sca-service",
		"openbank-lending-service",
		"openbank-fx-service",
		"openbank-consent-service",
		"openbank-sanctions-service",
	],
	"money_path_action_prefixes": {
		"sepa-payment": ["sepaPayment"],
		"sepa-instant": ["sctInstPayment"],
		# domestic-payment intentionally absent -- its real @Authorize prefix is now
		# domestic-payment.* (kebab-case), matching the derived scope by default
		# (issue #413 audit found a stale override here silently re-breaking it).
		"clearing": ["clearingBatch"],
		"sca": ["device", "scaChallenge"],
	},
	"four_eyes": {
		"verbs": [
			"transfer", "post", "reverse", "freeze", "release", "flip",
			"transitionStatus", "recall", "settle", "disburse", "send", "credit", "debit",
			"collateralRegister", "convert", "grant", "revoke", "clear",
		],
		"actions": ["opsmessage.compose", "party.merge", "campaign.activate", "device.enroll", "scaChallenge.consume"],
		# ADR-0280 / #8360: caller-aware exemptions — the verified M2M identities that
		# must keep flowing while the human path pauses for a second approver.
		"exemptions": {
			"device.enroll": ["service-account-openbank-edge"],
			"scaChallenge.consume": ["service-account-openbank-edge", "service-account-openbank-services"],
		},
	},
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
# Uses "ledger.reverse" — the actual @Authorize string in LedgerResource.kt (issue #395;
# "ledger.post" was never a real action, so a passing test here previously proved nothing
# about the fleet).
# ---------------------------------------------------------------------------------------
test_four_eyes_required_with_real_module_names if {
	rest.four_eyes_required with input as {"action": "ledger.reverse"}
		with data.rules as rules_real
}

# ---------------------------------------------------------------------------------------
# money_path_action_prefixes override (issue #395): pins each overridden service's REAL
# @Authorize action against the actual source file, so a future rename that isn't matched
# by an update here goes red instead of silently disabling four-eyes again.
# ---------------------------------------------------------------------------------------
test_four_eyes_required_sepa_payment_real_action if {
	# SepaPaymentResource.kt: @Authorize(action = "sepaPayment.transitionStatus", ...)
	rest.four_eyes_required with input as {"action": "sepaPayment.transitionStatus"}
		with data.rules as rules_real
}

test_four_eyes_required_sepa_instant_real_action if {
	# SctInstResource.kt: @Authorize(action = "sctInstPayment.recall", ...)
	rest.four_eyes_required with input as {"action": "sctInstPayment.recall"}
		with data.rules as rules_real
}

test_four_eyes_required_domestic_payment_real_action if {
	# DomesticPaymentResource.kt: @Authorize(action = "domestic-payment.transitionStatus", ...)
	# (renamed from domesticPayment.transitionStatus since the #395/#396 fix landed —
	# now matches the derived scope directly, no override needed; issue #413 audit.)
	rest.four_eyes_required with input as {"action": "domestic-payment.transitionStatus"}
		with data.rules as rules_real
}

test_money_path_scopes_domestic_payment_uses_derived_name_not_stale_override if {
	"domestic-payment" in rest.money_path_scopes with data.rules as rules_real
	not "domesticPayment" in rest.money_path_scopes with data.rules as rules_real
}

test_four_eyes_required_clearing_real_action if {
	# ClearingResource.kt: @Authorize(action = "clearingBatch.settle", ...)
	rest.four_eyes_required with input as {"action": "clearingBatch.settle"}
		with data.rules as rules_real
}

test_four_eyes_required_lending_disburse_real_action if {
	# LendingResource.kt: @Authorize(action = "lending.disburse", ...). "lending" is not in
	# money_path_action_prefixes -- its real @Authorize prefix already matches the derived
	# scope (openbank-lending-service -> lending), so no override entry is needed.
	rest.four_eyes_required with input as {"action": "lending.disburse"}
		with data.rules as rules_real
}

test_four_eyes_required_lending_collateral_register_real_action if {
	# LendingResource.kt: @Authorize(action = "lending.collateralRegister", ...) -- collateral
	# registration feeds the IFRS 9 LGD adjustment (ADR-0028 follow-up, issue #621); a maker
	# registering it alone must not make it usable until a checker approves it.
	rest.four_eyes_required with input as {"action": "lending.collateralRegister"}
		with data.rules as rules_real
}

test_four_eyes_not_required_for_lending_collateral_decide if {
	# The checker-decision endpoint itself (lending.collateralDecide) is not four-eyes-gated --
	# gating the second-eye action would be circular. Mirrors lending.approve (the loan-decision
	# checker endpoint), which is likewise absent from four_eyes.verbs.
	not rest.four_eyes_required with input as {"action": "lending.collateralDecide"}
		with data.rules as rules_real
}

# ---------------------------------------------------------------------------------------
# issue #1390: rules_real's four_eyes.verbs had drifted from the real rules.yaml, missing
# convert/grant/revoke/clear (added per issue #938 follow-up). None of the existing
# rules_real-driven tests exercised those four actions, so CI stayed green while the mock
# silently stopped proving those four production maker-checker gates still fire. Pins each
# against the real @Authorize action string documented alongside the verb in rules.yaml.
# ---------------------------------------------------------------------------------------
test_four_eyes_required_fx_convert_real_action if {
	# FxResource.kt: @Authorize(action = "fx.convert", ...) -- execute a currency conversion
	# at the current spot rate. Confirmed no M2M caller (fx-service's own rego).
	rest.four_eyes_required with input as {"action": "fx.convert"}
		with data.rules as rules_real
}

test_four_eyes_required_consent_grant_real_action if {
	# ConsentResource.kt: @Authorize(action = "consent.grant", ...) -- create a new
	# TPP<->customer data-access consent. Confirmed no M2M caller.
	rest.four_eyes_required with input as {"action": "consent.grant"}
		with data.rules as rules_real
}

test_four_eyes_required_consent_revoke_real_action if {
	# ConsentResource.kt: @Authorize(action = "consent.revoke", ...) -- operator-initiated
	# revocation of a customer's active consent. Confirmed no M2M caller.
	rest.four_eyes_required with input as {"action": "consent.revoke"}
		with data.rules as rules_real
}

test_four_eyes_required_sanctions_clear_real_action if {
	# SanctionsResource.kt: @Authorize(action = "sanctions.clear", ...) -- decide a
	# screening hit (CLEAR/HIT/POTENTIAL_HIT). Confirmed no M2M caller.
	rest.four_eyes_required with input as {"action": "sanctions.clear"}
		with data.rules as rules_real
}

# ---------------------------------------------------------------------------------------
# party.merge (ADR-0179, issue #1984) -- gated via four_eyes.ACTIONS, not verbs. The three
# tests below are a set: the first proves the gate fires, the second proves it fires for a
# structural reason a verb addition could never supply, and the third proves the gate is
# narrow. Without the second, a reader would reasonably assume `merge` had been added to
# `verbs` and that party-service was somehow money-path; it is not, and never will be.
# ---------------------------------------------------------------------------------------
test_four_eyes_required_party_merge_real_action if {
	# PartyResource.kt: @Authorize(action = "party.merge", resource = "#id") -- retire a
	# duplicate identity into a survivor. Destructive of identity, irreversible in practice.
	rest.four_eyes_required with input as {"action": "party.merge"}
		with data.rules as rules_real
}

test_party_is_not_a_money_path_scope if {
	# The reason party.merge MUST live in four_eyes.actions: `verbs` derives its scopes from
	# money_path_services, and party-service is not on that list. If this ever flips, revisit
	# the actions entry -- but until then, no verb addition can reach party.*.
	not "party" in rest.money_path_scopes with data.rules as rules_real
}

test_four_eyes_not_required_for_other_party_actions if {
	# The gate is exact-action, so the rest of the party surface is untouched -- including
	# party.approval.decide, the checker endpoint itself. Gating that would deadlock the flow.
	not rest.four_eyes_required with input as {"action": "party.update"}
		with data.rules as rules_real

	not rest.four_eyes_required with input as {"action": "party.consent.update"}
		with data.rules as rules_real

	not rest.four_eyes_required with input as {"action": "party.approval.decide"}
		with data.rules as rules_real
}

# money_path_scopes uses the override prefix INSTEAD OF the derived name — the derived
# name never appears in any real @Authorize action for these 5 services, so keeping it
# in the set too would just be dead weight.
test_money_path_scopes_uses_override_prefix_not_derived_name if {
	"sepaPayment" in rest.money_path_scopes with data.rules as rules_real
	not "sepa-payment" in rest.money_path_scopes with data.rules as rules_real
}

# A service with no override keeps using its derived name (unaffected by the override table).
test_money_path_scopes_keeps_derived_name_when_no_override if {
	"ledger" in rest.money_path_scopes with data.rules as rules_real
}

# sca's override lists TWO real prefixes (device, scaChallenge) — neither is a casing
# variant of "sca", which never appears as an action prefix in the real service at all.
test_money_path_scopes_supports_multiple_override_prefixes if {
	"device" in rest.money_path_scopes with data.rules as rules_real
	"scaChallenge" in rest.money_path_scopes with data.rules as rules_real
	not "sca" in rest.money_path_scopes with data.rules as rules_real
}

# ---------------------------------------------------------------------------------------
# End-to-end wiring (issue #395): four_eyes_required must reach the actual `allow` object
# an OpaSidecarPolicyDecisionPoint caller receives, not just the standalone rule — the
# bug this closes was that `allow` never carried an "attributes" key at all.
# ---------------------------------------------------------------------------------------
test_allow_attributes_surface_four_eyes_required if {
	decision := rest.allow with input as {
		"principal": {"id": "op-1", "type": "HUMAN", "roles": ["ROLE_OPERATOR"], "attributes": {"tenant": "t-1"}},
		"action": "ledger.reverse",
		"resource": {"type": "ledger", "id": "j-1", "attributes": {"tenant": "t-1"}},
	}
		with data.openbank.bundle as bundle
		with data.rules as rules_real

	decision.allow == true
	decision.attributes.four_eyes_required == true
}

# Sparse by design: no attributes key content when four-eyes isn't required.
test_allow_attributes_empty_when_four_eyes_not_required if {
	decision := rest.allow with input as {
		"principal": {"id": "op-1", "type": "HUMAN", "roles": ["ROLE_OPERATOR"], "attributes": {"tenant": "t-1"}},
		"action": "party.update",
		"resource": {"type": "party", "id": "p-1", "attributes": {"tenant": "t-1"}},
	}
		with data.openbank.bundle as bundle
		with data.rules as rules_real

	decision.allow == true
	decision.attributes == {}
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

# ---------------------------------------------------------------------------------------
# edge-service-notification: the customer-edge M2M caller presents a client_credentials
# JWT that AuthorizeInterceptor classifies as HUMAN, principal.id
# "service-account-openbank-edge" (Keycloak never issues ROLE_SERVICE, and
# principalType() never emits "SERVICE" — see rest.rego). The rule matches on that exact
# identity, NOT on ROLE_OPERATOR alone — a real operator/admin also carries ROLE_OPERATOR
# and must NOT gain this rule's device.enroll reach (test_deny_operator_* below).
# ---------------------------------------------------------------------------------------
test_allow_service_notification_mark_read if {
	decision := rest.allow with input as {
		"principal": {"id": "service-account-openbank-edge", "type": "HUMAN", "roles": ["ROLE_OPERATOR"]},
		"action": "notification.mark-read",
		"resource": {"type": "notification", "id": "n-1"},
	}
		with data.openbank.bundle as bundle

	decision.allow == true
	decision.reason == "edge-service-notification"
}

test_allow_service_notification_list if {
	decision := rest.allow with input as {
		"principal": {"id": "service-account-openbank-edge", "type": "HUMAN", "roles": ["ROLE_OPERATOR"]},
		"action": "notification.list",
	}
		with data.openbank.bundle as bundle

	decision.allow == true
}

test_allow_service_device_list if {
	decision := rest.allow with input as {
		"principal": {"id": "service-account-openbank-edge", "type": "HUMAN", "roles": ["ROLE_OPERATOR"]},
		"action": "device.list",
	}
		with data.openbank.bundle as bundle

	decision.allow == true
}

# document.readContent — the onboarding framework agreement's PDF bytes (ADR-0169/0170).
# The verb sits outside operator-read-any's {list, read} set, so before the document.
# family was added here the edge could read a document's METADATA (document.read slipped
# through operator-read-any on its name) but never its CONTENT: the app's sign screen died
# on "getDocumentContent failed: 403" and onboarding could not complete.
test_allow_service_document_read_content if {
	decision := rest.allow with input as {
		"principal": {"id": "service-account-openbank-edge", "type": "HUMAN", "roles": ["ROLE_OPERATOR"]},
		"action": "document.readContent",
		"resource": {"type": "document", "id": "d-1"},
	}
		with data.openbank.bundle as bundle

	decision.allow == true
	decision.reason == "edge-service-notification"
}

# Staff must NOT reach a customer's signed agreement bytes off the back of this fix:
# operator-read-any's {list, read} verbs do not cover readContent, and this rule is pinned
# to the edge's client_credentials identity — a real ROLE_OPERATOR session is not it.
test_deny_operator_document_read_content if {
	not rest.allow with input as {
		"principal": {"id": "alice.operator", "type": "HUMAN", "roles": ["ROLE_OPERATOR"]},
		"action": "document.readContent",
		"resource": {"type": "document", "id": "d-1"},
	}
}

# signatureCeremony.recordDecision — the "Sign with biometrics" tap (ADR-0169 D3). Exactly
# the document.readContent shape one endpoint further on: the verb is outside
# operator-read-any's {list, read}, so reading the ceremony passed (signatureCeremony.read
# slipped through on its name) while signing it 403'd, leaving the sign screen stuck with
# every tap silently failing. Adding only the document. family in #1249 fixed the read leg
# and left this one denied.
test_allow_service_signature_ceremony_record_decision if {
	decision := rest.allow with input as {
		"principal": {"id": "service-account-openbank-edge", "type": "HUMAN", "roles": ["ROLE_OPERATOR"]},
		"action": "signatureCeremony.recordDecision",
		"resource": {"type": "signatureCeremony", "id": "c-1"},
	}
		with data.openbank.bundle as bundle

	decision.allow == true
	decision.reason == "edge-service-notification"
}

# The same containment as document.readContent: staff carrying ROLE_OPERATOR must not be
# able to record a signing decision on a customer's agreement. Signing is the customer's
# act — this rule is pinned to the edge's client_credentials identity, and a human operator
# session authenticates through a different client, so it can never match.
test_deny_operator_signature_ceremony_record_decision if {
	not rest.allow with input as {
		"principal": {"id": "alice.operator", "type": "HUMAN", "roles": ["ROLE_OPERATOR"]},
		"action": "signatureCeremony.recordDecision",
		"resource": {"type": "signatureCeremony", "id": "c-1"},
	}
}

# edge-service-consent: the customer's own PSD2 consent screen (ADR-0126). Same edge
# principal + guard as the notification/document grants — list is party-scoped by the
# path partyId the edge injects, revoke is ownership-enforced downstream.
test_allow_service_consent_list if {
	decision := rest.allow with input as {
		"principal": {"id": "service-account-openbank-edge", "type": "HUMAN", "roles": ["ROLE_OPERATOR"]},
		"action": "consent.list",
		"resource": {"type": "party", "id": "p-1"},
	}
		with data.openbank.bundle as bundle

	decision.allow == true
	decision.reason == "edge-service-consent"
}

test_allow_service_consent_revoke if {
	decision := rest.allow with input as {
		"principal": {"id": "service-account-openbank-edge", "type": "HUMAN", "roles": ["ROLE_OPERATOR"]},
		"action": "consent.revoke",
		"resource": {"type": "consent", "id": "c-1"},
	}
		with data.openbank.bundle as bundle

	decision.allow == true
	decision.reason == "edge-service-consent"
}

# edge-service-audit-customer: the app's privacy centre reads the customer's own access log
# (P2-27). The endpoint's @RolesAllowed had to include ROLE_OPERATOR — the edge's service
# account carries it and ROLE_API alone 403'd before the PDP ran — so this identity match is
# what keeps the surface narrow.
test_allow_service_audit_customer_read if {
	decision := rest.allow with input as {
		"principal": {"id": "service-account-openbank-edge", "type": "HUMAN", "roles": ["ROLE_OPERATOR"]},
		"action": "audit.customerRead",
		"resource": {"type": "party", "id": "p-1"},
	}
		with data.openbank.bundle as bundle

	decision.allow == true
	decision.reason == "edge-service-audit-customer"
}

# Staff carrying ROLE_OPERATOR must NOT reach the customer access log off this rule: it is
# pinned to the edge's client_credentials identity. This is the assertion that makes the
# widened @RolesAllowed safe — note `audit.customerRead` deliberately does not end in `.read`,
# so operator-read-any cannot pick it up either.
test_deny_operator_audit_customer_read if {
	not rest.allow with input as {
		"principal": {"id": "alice.operator", "type": "HUMAN", "roles": ["ROLE_OPERATOR"]},
		"action": "audit.customerRead",
		"resource": {"type": "party", "id": "p-1"},
	}
}

# The grant is one action, not the `audit.` family — the edge principal must never reach the
# auditor/compliance trail (GET /entries/{aggregateId}, /entries/by-actor/{actorId}).
test_deny_service_audit_read if {
	not rest.allow with input as {
		"principal": {"id": "service-account-openbank-edge", "type": "HUMAN", "roles": ["ROLE_API"]},
		"action": "audit.read",
		"resource": {"type": "party", "id": "p-1"},
	}
}

# Least privilege: the grant is the two exact actions the app uses, NOT the consent. family
# — the edge must not be able to create/activate/validate consents on the customer's behalf.
test_deny_service_consent_create if {
	not rest.allow with input as {
		"principal": {"id": "service-account-openbank-edge", "type": "HUMAN", "roles": ["ROLE_OPERATOR"]},
		"action": "consent.create",
		"resource": {"type": "consent", "id": "c-1"},
	}
}

# Staff carrying ROLE_OPERATOR must not revoke a customer's consent off this rule — it is
# pinned to the edge's client_credentials identity, and a human session is a different client.
test_deny_operator_consent_revoke if {
	not rest.allow with input as {
		"principal": {"id": "alice.operator", "type": "HUMAN", "roles": ["ROLE_OPERATOR"]},
		"action": "consent.revoke",
		"resource": {"type": "consent", "id": "c-1"},
	}
}

# The rule must NOT open other action families to the edge M2M caller (deny-by-default
# holds) — operator-read-any also does not cover a write like party.update.
test_deny_service_outside_notification_family if {
	not rest.allow with input as {
		"principal": {"id": "service-account-openbank-edge", "type": "HUMAN", "roles": ["ROLE_OPERATOR"]},
		"action": "party.update",
		"resource": {"type": "party", "id": "p-1"},
	}
}

# A SERVICE-typed principal (should one ever reach OPA) gets NOTHING from this rule —
# it is intentionally HUMAN-only, matching what AuthorizeInterceptor actually emits.
test_deny_service_typed_principal_never_matches if {
	not rest.allow with input as {
		"principal": {"id": "service-account-openbank-edge", "type": "SERVICE", "roles": ["ROLE_OPERATOR"]},
		"action": "notification.list",
	}
}

# ---------------------------------------------------------------------------------------
# agent-charter-allows: an AI_AGENT principal calling a REST action directly. rest.allow
# delegates to agents.allow by setting input.tool := input.action -- a charter declaring
# "query.ledger.readonly" in tools.allow (the MCP tool-tier vocabulary) must still grant a
# same-domain REST read like "ledger.list" (the @Authorize action-string vocabulary), via
# the rest_domains bridge in agents.rego. Before that bridge existed, glob_match
# ("query.ledger.readonly", "ledger.list") never matched and every AI_AGENT REST read
# 403'd the moment a service flipped OPA to enforce mode.
#
# principal.id here uses the REAL production shape: AuthorizeInterceptor.principalType()
# classifies AI_AGENT from a JWT `sub` prefixed "agent:" (its own test uses "agent:onboarding"),
# and principal.id is that sub VERBATIM, prefix included -- agents.yaml charter ids are bare
# ("ui-assistant"). agents.rego's charter lookup strips the prefix before comparing; these
# tests use the prefixed form so a regression there (e.g. someone removing the trim_prefix)
# fails here instead of only in a bare-id unit test that doesn't reflect the real JWT shape.
# ---------------------------------------------------------------------------------------
agent_charters_for_rest_bridge := {
	"agents": [
		{
			"id": "ui-assistant",
			"plane": "control",
			"tools": {
				"allow": ["query.ledger.readonly", "query.catalog.readonly", "draft.ticket"],
				"deny": ["money.*", "gh.pr.*"],
			},
		},
	],
	"tool_tiers": {"deny": ["money.transfer", "money.post.ledger", "gh.pr.merge", "gh.pr.approve", "secrets.read.raw"]},
}

test_allow_ai_agent_ledger_list_via_charter_bridge if {
	decision := rest.allow with input as {
		"principal": {"id": "agent:ui-assistant", "type": "AI_AGENT", "roles": []},
		"action": "ledger.list",
		"resource": null,
	}
		with data.openbank.bundle as bundle
		with data.agents as agent_charters_for_rest_bridge

	decision.allow == true
	decision.reason == "agent-charter-allows"
}

# REGRESSION (openbank-mcp-service, ADR-0181): the PDP OMITS `resource` from the query when it
# is null (OpaSidecarPolicyDecisionPoint.toInput), so the bridge input has NO resource key at
# all — the real production shape for the MCP server, which is the first AI_AGENT caller routed
# through rest.allow. A bare `input.resource` in the bridge was undefined here, denying the call;
# every other agent test sends an explicit `"resource": null` and so never covered this path.
test_allow_ai_agent_via_charter_bridge_without_resource_key if {
	decision := rest.allow with input as {
		"principal": {"id": "agent:ui-assistant", "type": "AI_AGENT", "roles": []},
		"action": "ledger.list",
	}
		with data.openbank.bundle as bundle
		with data.agents as agent_charters_for_rest_bridge

	decision.allow == true
	decision.reason == "agent-charter-allows"
}

# The bridge is read-only -- an AI_AGENT can never reach a write action through it.
test_deny_ai_agent_ledger_create_via_charter_bridge if {
	not rest.allow with input as {
		"principal": {"id": "agent:ui-assistant", "type": "AI_AGENT", "roles": []},
		"action": "ledger.create",
		"resource": null,
	}
		with data.agents as agent_charters_for_rest_bridge
}

# An AI_AGENT whose charter holds no matching tool stays denied (deny-by-default holds).
test_deny_ai_agent_without_matching_charter_tool if {
	not rest.allow with input as {
		"principal": {"id": "agent:rca-investigator", "type": "AI_AGENT", "roles": []},
		"action": "ledger.list",
		"resource": null,
	}
		with data.agents as agent_charters_for_rest_bridge
}

# The fleet-wide hard-denied tier still blocks a REST action reachable via the bridge --
# rest.allow MUST delegate to agents.allow (which checks hard_denied), not to
# agents.charter_allowed alone (which doesn't). Regression coverage for that exact bug:
# hard-denying "ledger.list" here (an artificial hard-deny entry, since no real fleet entry
# collides with a read verb today) would otherwise still be granted by rest_action_allowed.
test_deny_ai_agent_hard_denied_tool_via_bridge if {
	not rest.allow with input as {
		"principal": {"id": "agent:ui-assistant", "type": "AI_AGENT", "roles": []},
		"action": "ledger.list",
		"resource": null,
	}
		with data.agents as {
			"agents": agent_charters_for_rest_bridge.agents,
			"tool_tiers": {"deny": ["ledger.list"]},
		}
}

# A charter's own tools.deny glob still blocks a REST action reachable via the bridge --
# same regression class as the hard-denied case above, at the charter_denied layer instead.
test_deny_ai_agent_charter_denied_tool_via_bridge if {
	not rest.allow with input as {
		"principal": {"id": "agent:ui-assistant", "type": "AI_AGENT", "roles": []},
		"action": "ledger.list",
		"resource": null,
	}
		with data.agents as {
			"agents": [
				{
					"id": "ui-assistant",
					"plane": "control",
					"tools": {"allow": ["query.ledger.readonly"], "deny": ["ledger.*"]},
				},
			],
			"tool_tiers": {"deny": []},
		}
}

# ---------------------------------------------------------------------------------------
# ADR-0195 step 5 (#3292): the bridge MUST forward `attributes` into agents.allow. This is
# behaviourally observable through agents.rego's skill_ok else-branch, which reads
# input.attributes.skill: a chartered run.skill arriving via the REST bridge is granted ONLY
# when the attributes map survives the bridge rewrite. The MCP endpoint's consentId rides the
# same channel ({tool, consentId}), so this test is the forwarding proof for consent-state
# gating too — without it a future consent policy would evaluate against an empty map.
# ---------------------------------------------------------------------------------------
skill_charter_for_attributes_bridge := {
	"agents": [
		{
			"id": "ledger-domain-engineer",
			"plane": "development",
			"skills": ["ship-check"],
			"tools": {"allow": ["run.skill"], "deny": []},
		},
	],
	"tool_tiers": {"deny": []},
}

test_allow_ai_agent_run_skill_via_bridge_when_attributes_forwarded if {
	decision := rest.allow with input as {
		"principal": {"id": "agent:ledger-domain-engineer", "type": "AI_AGENT", "roles": []},
		"action": "run.skill",
		"resource": null,
		"attributes": {"skill": "ship-check"},
	}
		with data.openbank.bundle as bundle
		with data.agents as skill_charter_for_attributes_bridge

	decision.allow == true
	decision.reason == "agent-charter-allows"
}

# Same call with an unchartered skill stays denied — the forwarded attributes are really the
# input skill_ok evaluates (not merely present).
test_deny_ai_agent_run_skill_via_bridge_with_unchartered_skill if {
	not rest.allow with input as {
		"principal": {"id": "agent:ledger-domain-engineer", "type": "AI_AGENT", "roles": []},
		"action": "run.skill",
		"resource": null,
		"attributes": {"skill": "deploy-prod"},
	}
		with data.agents as skill_charter_for_attributes_bridge
}

# A caller that sends NO attributes key must not make the bridged query undefined — the
# bridge defaults attributes to {} (same object.get pattern as resource), and skill_ok's
# else-branch then denies run.skill cleanly instead of disappearing the whole decision.
test_deny_ai_agent_run_skill_via_bridge_without_attributes_key if {
	not rest.allow with input as {
		"principal": {"id": "agent:ledger-domain-engineer", "type": "AI_AGENT", "roles": []},
		"action": "run.skill",
		"resource": null,
	}
		with data.agents as skill_charter_for_attributes_bridge
}

# A real operator/admin staff member (ROLE_OPERATOR, but NOT the edge's identity) must
# NOT gain this rule's reach — critically, must NOT be able to device.enroll (SCA
# WebAuthn registration is an account-takeover primitive if grantable to arbitrary staff).
# See also test_deny_operator_read_any_does_not_cover_write above for the same invariant.
test_deny_human_operator_who_is_not_the_edge if {
	not rest.allow with input as {
		"principal": {"id": "operator-1", "type": "HUMAN", "roles": ["ROLE_OPERATOR"]},
		"action": "device.enroll",
		"resource": {"type": "device", "id": "any-party-id"},
	}
}

# A HUMAN caller with the edge's identity but somehow missing ROLE_OPERATOR is still
# irrelevant to this rule — it gates on identity, not role, and must still allow.
test_allow_edge_identity_without_explicit_operator_role_check if {
	decision := rest.allow with input as {
		"principal": {"id": "service-account-openbank-edge", "type": "HUMAN", "roles": []},
		"action": "notification.list",
	}
		with data.openbank.bundle as bundle

	decision.allow == true
}

# ---------------------------------------------------------------------------------------
# m2m-sanctions-screening (issue #746, found via issue #669's load benchmark): a resourceless
# M2M sanctions.create call has no other matching rule (operator-on-own-tenant requires
# input.resource; sanctions.create has none). account-service's client_credentials token
# (client "openbank-services") is the confirmed real-world caller.
# ---------------------------------------------------------------------------------------
test_allow_m2m_sanctions_create if {
	decision := rest.allow with input as {
		"principal": {"id": "service-account-openbank-services", "type": "HUMAN", "roles": ["ROLE_OPERATOR"]},
		"action": "sanctions.create",
		"resource": "",
	}
		with data.openbank.bundle as bundle

	decision.allow == true
	decision.reason == "m2m-sanctions-screening"
}

# Any service-account caller works, not just openbank-services — the rule is deliberately
# not pinned to one client id (see rest.rego comment): more than one service may screen
# entities. This is the "different caller, same carve-out" counterpart to the identity-
# pinned edge-service-notification rule above.
test_allow_m2m_sanctions_create_from_a_different_service_account if {
	decision := rest.allow with input as {
		"principal": {"id": "service-account-openbank-kyc", "type": "HUMAN", "roles": ["ROLE_OPERATOR"]},
		"action": "sanctions.create",
		"resource": "",
	}
		with data.openbank.bundle as bundle

	decision.allow == true
}

# Deny-by-default still holds outside sanctions.create — an M2M caller does NOT gain the
# whole "sanctions." family. sanctions.clear (renamed from sanctions.review, issue #938
# follow-up) lets an operator dismiss/confirm a hit; a service self-clearing its own
# screening result would defeat the compliance control.
test_deny_m2m_sanctions_clear_not_covered if {
	not rest.allow with input as {
		"principal": {"id": "service-account-openbank-services", "type": "HUMAN", "roles": ["ROLE_OPERATOR"]},
		"action": "sanctions.clear",
		"resource": "",
	}
}

# A real human operator session (not a service-account principal) does NOT gain this rule's
# reach via ROLE_OPERATOR alone — it gates on the "service-account-" identity prefix, not
# the role, mirroring the edge-service-notification invariant above.
test_deny_human_operator_sanctions_create_via_m2m_rule if {
	not rest.allow with input as {
		"principal": {"id": "operator-1", "type": "HUMAN", "roles": ["ROLE_OPERATOR"]},
		"action": "sanctions.create",
		"resource": "",
	}
}

# ---------------------------------------------------------------------------------------
# opsmessage.compose (ADR-0176 D4/D5): operator-initiated customer messaging. Its own action
# namespace, deliberately not notification.*, and its own four-eyes trigger, independent of
# money_path_scopes.
# ---------------------------------------------------------------------------------------
test_allow_operator_compose_message if {
	decision := rest.allow with input as {
		"principal": {"id": "operator-1", "type": "HUMAN", "roles": ["ROLE_OPERATOR"]},
		"action": "opsmessage.compose",
		"resource": "",
	}
		with data.openbank.bundle as bundle

	decision.allow == true
	decision.reason == "operator-compose-message"
}

test_allow_admin_compose_message if {
	rest.allow with input as {
		"principal": {"id": "admin-1", "type": "HUMAN", "roles": ["ROLE_ADMIN"]},
		"action": "opsmessage.compose",
		"resource": "",
	}
		with data.openbank.bundle as bundle
}

# The finding that made D4's namespace split necessary rather than merely tidy:
# service-account-openbank-edge carries ROLE_OPERATOR in the realm and is classified HUMAN
# (Keycloak client_credentials tokens never produce principal.type == SERVICE), so a rule
# gated on HUMAN + ROLE_OPERATOR alone would re-admit it. This is the regression test for
# that specific identity, not just the class of service-account ids.
test_deny_edge_service_account_compose_message if {
	not rest.allow with input as {
		"principal": {"id": "service-account-openbank-edge", "type": "HUMAN", "roles": ["ROLE_OPERATOR"]},
		"action": "opsmessage.compose",
		"resource": "",
	}
}

# Any service-account caller is excluded, not only the edge identity — the rule gates on the
# id prefix, not a specific client.
test_deny_any_service_account_compose_message if {
	not rest.allow with input as {
		"principal": {"id": "service-account-openbank-kyc", "type": "HUMAN", "roles": ["ROLE_OPERATOR"]},
		"action": "opsmessage.compose",
		"resource": "",
	}
}

# A role that is neither operator nor admin does not gain this action.
test_deny_viewer_compose_message if {
	not rest.allow with input as {
		"principal": {"id": "viewer-1", "type": "HUMAN", "roles": ["ROLE_VIEWER"]},
		"action": "opsmessage.compose",
		"resource": "",
	}
}

# edge-service-notification's plain notification.*/device.* prefix match does NOT extend to
# opsmessage.* — this is the namespace split (ADR-0176 D4) actually holding, not just asserted
# in a comment.
test_deny_edge_service_notification_does_not_cover_opsmessage if {
	not rest.allow with input as {
		"principal": {"id": "service-account-openbank-edge", "type": "HUMAN", "roles": []},
		"action": "opsmessage.compose",
		"resource": "",
	}
}

# The checker-side decide action gets the identical shape.
test_allow_operator_decide_message_approval if {
	decision := rest.allow with input as {
		"principal": {"id": "operator-2", "type": "HUMAN", "roles": ["ROLE_OPERATOR"]},
		"action": "opsmessage.approval.decide",
		"resource": "approval-1",
	}
		with data.openbank.bundle as bundle

	decision.allow == true
	decision.reason == "operator-decide-message-approval"
}

test_deny_edge_service_account_decide_message_approval if {
	not rest.allow with input as {
		"principal": {"id": "service-account-openbank-edge", "type": "HUMAN", "roles": ["ROLE_OPERATOR"]},
		"action": "opsmessage.approval.decide",
		"resource": "approval-1",
	}
}

# four_eyes_required fires for opsmessage.compose via the NEW data.rules.four_eyes.actions
# list, not via money_path_scopes — notification-service is not and will never be in
# money_path_services, so this proves the exact-action clause is what is actually firing.
test_four_eyes_required_for_opsmessage_compose if {
	rest.four_eyes_required with input as {"action": "opsmessage.compose"}
		with data.rules as rules_real
}

# Confirms the exact-action clause is scoped, not a wildcard: an arbitrary action outside
# both four_eyes.verbs and four_eyes.actions is not flagged.
test_four_eyes_not_required_for_unrelated_action if {
	not rest.four_eyes_required with input as {"action": "notification.list"}
		with data.rules as rules_real
}

# The exact-action clause never fires against a bundle with no rules.yaml override (the
# undefined-collection case the rest.rego comment documents) — proves this is a genuinely
# additive change: a service whose bundle predates four_eyes.actions sees no behaviour change.
test_four_eyes_not_required_when_actions_key_absent if {
	not rest.four_eyes_required with input as {"action": "opsmessage.compose"}
		with data.rules as {"four_eyes": {"verbs": []}}
}

# ---------------------------------------------------------------------------------------
# ADR-0280 / #8360 — caller-aware four-eyes exemptions. device.enroll and
# scaChallenge.consume are in four_eyes.actions with their verified M2M callers exempt, so
# the HUMAN ops-console path pauses while SCA automation keeps flowing. These tests are the
# falsification: dropping the exemption map, or exempting the wrong identity, turns one of
# them red.
# ---------------------------------------------------------------------------------------

# The human operator path IS flagged — this is the whole point of the wiring.
test_four_eyes_required_for_device_enroll_human if {
	rest.four_eyes_required with input as {
		"action": "device.enroll",
		"principal": {"type": "HUMAN", "id": "operator-1", "roles": ["ROLE_OPERATOR"]},
	}
		with data.rules as rules_real
}

# The verified automation caller is NOT flagged.
test_four_eyes_exempt_edge_device_enroll if {
	not rest.four_eyes_required with input as {
		"action": "device.enroll",
		"principal": {"type": "HUMAN", "id": "service-account-openbank-edge", "roles": ["ROLE_OPERATOR"]},
	}
		with data.rules as rules_real
}

# Exemptions are PER-ACTION: the shared backend client may consume challenges (delegation /
# document ceremonies) but must never inherit the edge's enrollment exemption.
test_four_eyes_not_exempt_shared_client_device_enroll if {
	rest.four_eyes_required with input as {
		"action": "device.enroll",
		"principal": {"type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_OPERATOR"]},
	}
		with data.rules as rules_real
}

# scaChallenge.consume: both ceremony callers exempt, a human operator is not.
test_four_eyes_exempt_shared_client_consume if {
	not rest.four_eyes_required with input as {
		"action": "scaChallenge.consume",
		"principal": {"type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_OPERATOR"]},
	}
		with data.rules as rules_real
}

test_four_eyes_required_for_consume_human if {
	rest.four_eyes_required with input as {
		"action": "scaChallenge.consume",
		"principal": {"type": "HUMAN", "id": "operator-1", "roles": ["ROLE_OPERATOR"]},
	}
		with data.rules as rules_real
}

# An actions-listed action with NO exemptions entry flags every caller, service accounts
# included — the pre-ADR-0280 behaviour is the default.
test_four_eyes_actions_entry_without_exemptions_flags_all if {
	rest.four_eyes_required with input as {
		"action": "party.merge",
		"principal": {"type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_OPERATOR"]},
	}
		with data.rules as rules_real
}

# A bundle whose rules.yaml predates the exemptions key behaves exactly as before (undefined
# collection does not fire) — additive change, no flag-day across the fleet.
test_four_eyes_exemptions_key_absent_is_backward_compatible if {
	rest.four_eyes_required with input as {
		"action": "opsmessage.compose",
		"principal": {"type": "HUMAN", "id": "operator-1", "roles": ["ROLE_OPERATOR"]},
	}
		with data.rules as {"four_eyes": {"verbs": [], "actions": ["opsmessage.compose"]}}
}


# ---------------------------------------------------------------------------------------
# The shared M2M identity may never reach a WRITE through a role-only operator reason
# (GHSA-58jq-9hq3-66jr). `service-account-openbank-services` carries ROLE_OPERATOR in the
# realm, so every `operator-<domain>-write` rule — which checks only type == HUMAN plus the
# role — admitted any backend service to any write in that domain until the `prohibited`
# guard in rest.rego.
#
# These tests are the falsification: removing that guard must turn the first one green in
# the wrong direction. The three after it are the ones that would break if the guard were
# too broad, which is the real risk of a fix at the allow head.
# ---------------------------------------------------------------------------------------

# The regression itself: a role-only write reason is the ONLY thing admitting this caller.
test_deny_shared_m2m_write_via_role_only_reason if {
	not rest.allow with input as {
		"principal": {"id": "service-account-openbank-services", "type": "HUMAN", "roles": ["ROLE_OPERATOR"]},
		"action": "ledger.reverse",
		"resource": {"type": "ledger", "id": "e-1"},
	}
		with data.openbank.bundle as bundle
		with data.rules.shared_m2m_write_prohibition.reasons as ["operator-ledger-write"]
		with rest.allowed_reasons as {"operator-ledger-write"}
}

# READS must be untouched: party-service's GDPR Art. 15 aggregation calls kyc-service and
# card-issuance-service with exactly this identity and relies on operator-read-any.
test_allow_shared_m2m_read_still_works if {
	decision := rest.allow with input as {
		"principal": {"id": "service-account-openbank-services", "type": "HUMAN", "roles": ["ROLE_OPERATOR"]},
		"action": "card.list",
		"resource": {"type": "card", "id": "p-1"},
	}
		with data.openbank.bundle as bundle
		with rest.allowed_reasons as {"operator-read-any"}

	decision.allow == true
}

# An identity-scoped reason is the sanctioned way to grant an M2M write: it names the caller
# and enumerates the actions. One such reason is enough, even alongside a role-only one.
test_allow_shared_m2m_write_via_identity_scoped_reason if {
	decision := rest.allow with input as {
		"principal": {"id": "service-account-openbank-services", "type": "HUMAN", "roles": ["ROLE_OPERATOR"]},
		"action": "consent.grant",
		"resource": {"type": "consent", "id": "party-service:marketing-comms"},
	}
		with data.openbank.bundle as bundle
		with data.rules.shared_m2m_write_prohibition.reasons as ["operator-consent-write"]
		with rest.allowed_reasons as {"operator-consent-write", "service-consent-m2m-marketing"}

	decision.allow == true
}

# A real human operator is unaffected — the guard keys on one service-account identity
# string, which no human user can hold.
test_allow_human_operator_write_unaffected if {
	decision := rest.allow with input as {
		"principal": {"id": "u-op", "type": "HUMAN", "roles": ["ROLE_OPERATOR"]},
		"action": "ledger.reverse",
		"resource": {"type": "ledger", "id": "e-1"},
	}
		with data.openbank.bundle as bundle
		with data.rules.shared_m2m_write_prohibition.reasons as ["operator-ledger-write"]
		with rest.allowed_reasons as {"operator-ledger-write"}

	decision.allow == true
}

# Role-independent: ROLE_ADMIN on the shared identity is denied exactly as ROLE_OPERATOR is.
# (An earlier version of this test claimed to prove coverage of a DIFFERENT service-account
# while reusing the same id — it demonstrated nothing about other accounts. The guard IS keyed
# solely on `service-account-openbank-services`; other service-accounts are deliberately out of
# scope, because their callers have not been enumerated.)
test_deny_shared_m2m_write_regardless_of_role if {
	not rest.allow with input as {
		"principal": {"id": "service-account-openbank-services", "type": "HUMAN", "roles": ["ROLE_ADMIN"]},
		"action": "ledger.reverse",
		"resource": {"type": "ledger", "id": "e-1"},
	}
		with data.openbank.bundle as bundle
		with data.rules.shared_m2m_write_prohibition.reasons as ["operator-ledger-write"]
		with rest.allowed_reasons as {"operator-ledger-write"}
}

# The load-bearing property of the opt-in design: a role-only write reason that is NOT in the
# register still allows. This is what keeps transaction.create working for its six verified
# callers — the first revision of this fix matched every `operator-*-write` by name and would
# have 403'd them on an AUTHZ_ENFORCE=true money path.
test_allow_role_only_write_reason_not_in_the_register if {
	decision := rest.allow with input as {
		"principal": {"id": "service-account-openbank-services", "type": "HUMAN", "roles": ["ROLE_OPERATOR"]},
		"action": "transaction.create",
		"resource": "",
	}
		with data.openbank.bundle as bundle
		with data.rules.shared_m2m_write_prohibition.reasons as ["operator-ledger-write"]
		with rest.allowed_reasons as {"operator-transaction-write"}

	decision.allow == true
}

# A bundle whose rules.yaml predates the key sees no behaviour change: membership over an
# undefined collection does not fire, so nothing is newly denied.
test_allow_when_the_register_key_is_absent if {
	decision := rest.allow with input as {
		"principal": {"id": "service-account-openbank-services", "type": "HUMAN", "roles": ["ROLE_OPERATOR"]},
		"action": "ledger.reverse",
		"resource": {"type": "ledger", "id": "e-1"},
	}
		with data.openbank.bundle as bundle
		with data.rules as {}
		with rest.allowed_reasons as {"operator-ledger-write"}

	decision.allow == true
}

# ADR-0224 D2: the session lifecycle grant is operator/admin-only and only reaches mcp.session.*.
test_operator_mcp_session_grants_operator if {
	rest.allowed_reasons["operator-mcp-session"] with input as {
		"principal": {"id": "op-1", "type": "HUMAN", "roles": ["ROLE_OPERATOR"]},
		"action": "mcp.session.create",
	}
}

test_operator_mcp_session_denies_a_viewer if {
	not rest.allowed_reasons["operator-mcp-session"] with input as {
		"principal": {"id": "v-1", "type": "HUMAN", "roles": ["ROLE_VIEWER"]},
		"action": "mcp.session.create",
	}
}

test_operator_mcp_session_does_not_leak_to_other_domains if {
	not rest.allowed_reasons["operator-mcp-session"] with input as {
		"principal": {"id": "op-1", "type": "HUMAN", "roles": ["ROLE_OPERATOR"]},
		"action": "account.freeze",
	}
}

# ADR-0223 D2 phase 1 (shadow): the matrix grants a seeded read for a staff role — and the
# reason surfaces, so the retirement triage can count matrix-carried calls.
test_matrix_allows_grants_a_seeded_read if {
	decision := rest.allow with input as {
		"principal": {"id": "op-1", "type": "HUMAN", "roles": ["ROLE_OPERATOR"]},
		"action": "account.read",
	}
		with data.openbank.bundle as bundle
		with data.rules.authz as {"role_action_matrix": {"ROLE_OPERATOR": {"grant": ["account.read"]}}}

	decision.allow == true
	"matrix-allows" in rest.allowed_reasons with input as {
		"principal": {"id": "op-1", "type": "HUMAN", "roles": ["ROLE_OPERATOR"]},
		"action": "account.read",
	}
		with data.rules.authz as {"role_action_matrix": {"ROLE_OPERATOR": {"grant": ["account.read"]}}}
}

# An action outside the seed is NOT granted by the matrix (and by nothing else here) —
# the matrix is an exact-action gate, not a prefix one.
test_matrix_allows_does_not_grant_an_unseeded_action if {
	not rest.allow with input as {
		"principal": {"id": "op-1", "type": "HUMAN", "roles": ["ROLE_OPERATOR"]},
		"action": "account.freeze",
	}
		with data.openbank.bundle as bundle
		with data.rules.authz as {"role_action_matrix": {"ROLE_OPERATOR": {"grant": ["account.read"]}}}
}

# A role absent from the matrix: the lookup is undefined, the rule does not fire, and a
# bundle whose rules.yaml predates the key sees no behaviour change.
test_matrix_allows_absent_role_and_absent_key_are_silent if {
	not rest.allowed_reasons["matrix-allows"] with input as {
		"principal": {"id": "op-1", "type": "HUMAN", "roles": ["ROLE_NOPE"]},
		"action": "account.read",
	}
		with data.rules.authz as {"role_action_matrix": {"ROLE_OPERATOR": {"grant": ["account.read"]}}}

	not rest.allowed_reasons["matrix-allows"] with input as {
		"principal": {"id": "op-1", "type": "HUMAN", "roles": ["ROLE_OPERATOR"]},
		"action": "account.read",
	}
		with data.rules as {}
}

# ADR-0223 phase 1.5: one-level inheritance — ADMIN inherits OPERATOR's full matrix; a role
# with an empty own grant list still resolves via the parent.
test_matrix_inherits_one_level if {
	rest.allowed_reasons["matrix-allows"] with input as {
		"principal": {"id": "admin-1", "type": "HUMAN", "roles": ["ROLE_ADMIN"]},
		"action": "account.freeze",
	}
		with data.rules.authz as {"role_action_matrix": {
			"ROLE_OPERATOR": {"grant": ["account.freeze"]},
			"ROLE_ADMIN": {"grant": [], "inherits": "ROLE_OPERATOR"},
		}}
}

test_matrix_inheritance_does_not_grant_unlisted if {
	not rest.allowed_reasons["matrix-allows"] with input as {
		"principal": {"id": "admin-1", "type": "HUMAN", "roles": ["ROLE_ADMIN"]},
		"action": "account.close",
	}
		with data.rules.authz as {"role_action_matrix": {
			"ROLE_OPERATOR": {"grant": ["account.freeze"]},
			"ROLE_ADMIN": {"grant": [], "inherits": "ROLE_OPERATOR"},
		}}
}
