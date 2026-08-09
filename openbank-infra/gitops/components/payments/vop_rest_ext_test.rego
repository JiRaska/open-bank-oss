# SPDX-License-Identifier: Apache-2.0
# Unit tests for vop_rest_ext.rego (ADR-0171, ADR-0034 Phase 5).
#
# Follows the extract-and-test pattern of issue #1322 (see card_issuance_rest_ext_test.rego): the
# extension lives on disk as a real .rego file so `opa test` loads and covers it, rather than as a
# bash heredoc inside the generator. Until #4228 this extension WAS a heredoc, so the rules below
# had never been covered by any suite — opa-policy.yml discovers suites by the
# *_rest_ext_test.rego / *_rest_ext.rego file pair, and a heredoc has no file to pair with.
#
# Run from repo root (name the files explicitly — `opa test <dir>` also tries to load every
# .yaml in the dir as data and dies with a merge error):
#   opa test openbank-libs/governance/policies/rest.rego \
#            openbank-infra/gitops/components/payments/vop_rest_ext.rego \
#            openbank-infra/gitops/components/payments/vop_rest_ext_test.rego
# (allowed_reasons here is a partial-set rule with no dependency on rules.yaml or agents.yaml.)

package openbank.rest_test

import data.openbank.rest

# ── The shared-M2M exclusion (GHSA-58jq-9hq3-66jr, issue #4228) ───────────────────────────────
#
# THE point of this file. Strip `not startswith(input.principal.id, "service-account-")` from
# operator-vop-verify and this test goes red while every other test here stays green — which is
# what makes the exclusion falsifiable rather than merely present.

# The shared backend identity carries ROLE_OPERATOR and is classified HUMAN, so without the
# exclusion it reaches vop.verify through the OPERATOR branch. It must not.
test_operator_vop_verify_denied_for_shared_service_account if {
	not "operator-vop-verify" in rest.allowed_reasons with input as {
		"principal": {
			"type": "HUMAN",
			"id": "service-account-openbank-services",
			"roles": ["ROLE_OPERATOR"],
		},
		"action": "vop.verify",
	}
}

# ...and the exclusion must not be narrowed to one client id: ANY service-account is barred from
# the role-only branch, including the edge client, which also holds ROLE_OPERATOR in some realms.
test_operator_vop_verify_denied_for_edge_service_account if {
	not "operator-vop-verify" in rest.allowed_reasons with input as {
		"principal": {
			"type": "HUMAN",
			"id": "service-account-openbank-edge",
			"roles": ["ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_PAYMENTS", "ROLE_VIEWER"],
		},
		"action": "vop.verify",
	}
}

# The rails keep their access — via their OWN identity-pinned reason, not the operator branch.
# This is why the exclusion strands no caller, and asserting the exact reason set (rather than
# just `allow`) is what distinguishes "still works" from "still works for the right reason".
test_service_account_keeps_m2m_reason_only if {
	rest.allowed_reasons == {"m2m-vop-verify"} with input as {
		"principal": {
			"type": "HUMAN",
			"id": "service-account-openbank-sepa-payment",
			"roles": ["ROLE_OPERATOR"],
		},
		"action": "vop.verify",
	}
}

# ── The human paths, unchanged by #4228 ───────────────────────────────────────────────────────
#
# Every role in the rule's set is asserted separately: a set membership test passes on the first
# match, so covering only one role would let the others be dropped silently.

test_operator_vop_verify_allowed_for_operator if {
	"operator-vop-verify" in rest.allowed_reasons with input as {
		"principal": {"type": "HUMAN", "id": "alice", "roles": ["ROLE_OPERATOR"]},
		"action": "vop.verify",
	}
}

test_operator_vop_verify_allowed_for_admin if {
	"operator-vop-verify" in rest.allowed_reasons with input as {
		"principal": {"type": "HUMAN", "id": "alice", "roles": ["ROLE_ADMIN"]},
		"action": "vop.verify",
	}
}

test_operator_vop_verify_allowed_for_payments_desk if {
	"operator-vop-verify" in rest.allowed_reasons with input as {
		"principal": {"type": "HUMAN", "id": "alice", "roles": ["ROLE_PAYMENTS"]},
		"action": "vop.verify",
	}
}

# ROLE_VIEWER is deliberately included by the rule — verification is a pre-payment check, not a
# money movement, and it mirrors the resource's own @RolesAllowed.
test_operator_vop_verify_allowed_for_viewer if {
	"operator-vop-verify" in rest.allowed_reasons with input as {
		"principal": {"type": "HUMAN", "id": "alice", "roles": ["ROLE_VIEWER"]},
		"action": "vop.verify",
	}
}

# A principal with no relevant role gets nothing, and a human id that merely CONTAINS the prefix
# elsewhere is not a service-account — the exclusion is anchored with startswith, not a substring.
test_vop_verify_denied_for_unrelated_role if {
	count(rest.allowed_reasons) == 0 with input as {
		"principal": {"type": "HUMAN", "id": "alice", "roles": ["ROLE_SUPPORT"]},
		"action": "vop.verify",
	}
}

test_operator_vop_verify_allowed_for_human_id_containing_the_prefix if {
	"operator-vop-verify" in rest.allowed_reasons with input as {
		"principal": {"type": "HUMAN", "id": "not-a-service-account-openbank", "roles": ["ROLE_OPERATOR"]},
		"action": "vop.verify",
	}
}

# An AI_AGENT principal never matches this HUMAN-only extension (agent access is mediated by
# agents.rego/charter_allowed via rest.rego's allow rule, not here).
test_vop_verify_denied_for_ai_agent if {
	count(rest.allowed_reasons) == 0 with input as {
		"principal": {"type": "AI_AGENT", "id": "agent:ui-assistant", "roles": ["ROLE_OPERATOR"]},
		"action": "vop.verify",
	}
}

# ── Scope: the extension must not widen beyond vop.verify ─────────────────────────────────────
#
# Both rules are deliberately action-scoped rather than `vop.` family-prefixed, so that a future
# write action (a threshold flip — the change the threat model flags as having real fraud
# consequence) is not silently pre-authorised. Assert that for the human AND the M2M branch.

test_other_vop_action_not_granted_for_human if {
	count(rest.allowed_reasons) == 0 with input as {
		"principal": {"type": "HUMAN", "id": "alice", "roles": ["ROLE_OPERATOR"]},
		"action": "vop.updateThreshold",
	}
}

test_other_vop_action_not_granted_for_service_account if {
	count(rest.allowed_reasons) == 0 with input as {
		"principal": {
			"type": "HUMAN",
			"id": "service-account-openbank-services",
			"roles": ["ROLE_OPERATOR"],
		},
		"action": "vop.updateThreshold",
	}
}
