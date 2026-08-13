# SPDX-License-Identifier: Apache-2.0
# Unit tests for standing_order_rest_ext.rego (ADR-0034 Phase 5 bootstrap, issue #1797).
#
# Follows the extract-and-test pattern established by issue #1322 (see
# card_issuance_rest_ext_test.rego): the extension lives on disk as a real .rego file so
# `opa test` loads and covers it, rather than as a bash heredoc inside the generator.
#
# Run from repo root (name the files explicitly — `opa test <dir>` also tries to load every
# .yaml in the dir as data and dies with a merge error):
#   opa test openbank-libs/governance/policies/rest.rego \
#            openbank-infra/gitops/components/payments/standing_order_rest_ext.rego \
#            openbank-infra/gitops/components/payments/standing_order_rest_ext_test.rego
# (allowed_reasons here is a partial-set rule with no dependency on rules.yaml or agents.yaml.)

package openbank.rest_test

import data.openbank.rest

# ── The shared-M2M exclusion + its identity-scoped replacement (GHSA-58jq-9hq3-66jr, #4228) ───
#
# THE point of the first three tests. Strip `not startswith(input.principal.id,
# "service-account-")` from operator-standing-order-pause and the first two go red while every
# other test in this file stays green; delete m2m-standing-order-pause and the third goes red.
# That pair is what makes remediation path 3 falsifiable rather than merely present.

# The shared backend identity carries ROLE_OPERATOR (docker + CI realms) and is classified HUMAN,
# so without the exclusion it reached standingOrder.pause through the OPERATOR branch. It must
# not: it is the identity nearly every backend service authenticates as.
test_pause_denied_for_shared_service_account if {
	count(rest.allowed_reasons) == 0 with input as {
		"principal": {
			"type": "HUMAN",
			"id": "service-account-openbank-services",
			"roles": ["ROLE_OPERATOR", "ROLE_ADMIN"],
		},
		"action": "standingOrder.pause",
	}
}

# ...and no OTHER service-account gets in through the role branch either. Asserting an unrelated
# one (not the edge) proves the exclusion is the thing doing the work, not the id pin below.
test_pause_denied_for_unrelated_service_account if {
	count(rest.allowed_reasons) == 0 with input as {
		"principal": {
			"type": "HUMAN",
			"id": "service-account-openbank-mcp-service",
			"roles": ["ROLE_OPERATOR"],
		},
		"action": "standingOrder.pause",
	}
}

# The one legitimate M2M caller keeps its access — via its OWN identity-pinned reason, never the
# operator branch. Asserting the exact reason SET (not just `allow`) is what distinguishes
# "still works" from "still works for the right reason": if this returned both reasons the
# exclusion would not have taken effect.
test_edge_service_account_keeps_m2m_reason_only if {
	rest.allowed_reasons == {"m2m-standing-order-pause"} with input as {
		"principal": {
			"type": "HUMAN",
			"id": "service-account-openbank-edge",
			"roles": ["ROLE_OPERATOR"],
		},
		"action": "standingOrder.pause",
	}
}

# The identity pin must not widen to other actions on the same caller: pause() is the only
# @Authorize'd endpoint, and cancel/resume must not be pre-authorised for the edge.
test_edge_service_account_gets_nothing_for_other_actions if {
	count(rest.allowed_reasons) == 0 with input as {
		"principal": {
			"type": "HUMAN",
			"id": "service-account-openbank-edge",
			"roles": ["ROLE_OPERATOR"],
		},
		"action": "standingOrder.cancel",
	}
}

# ── The human path, unaffected by the above ───────────────────────────────────────────────────

# ROLE_OPERATOR alone must grant standingOrder.pause.
test_standing_order_pause_allowed_for_operator if {
	"operator-standing-order-pause" in rest.allowed_reasons with input as {
		"principal": {"type": "HUMAN", "id": "alice", "roles": ["ROLE_OPERATOR"]},
		"action": "standingOrder.pause",
	}
}

# ROLE_ADMIN alone must also grant standingOrder.pause.
test_standing_order_pause_allowed_for_admin if {
	"operator-standing-order-pause" in rest.allowed_reasons with input as {
		"principal": {"type": "HUMAN", "id": "alice", "roles": ["ROLE_ADMIN"]},
		"action": "standingOrder.pause",
	}
}

# A principal with an unrelated role gets no allow reason.
test_standing_order_pause_denied_for_unrelated_role if {
	count(rest.allowed_reasons) == 0 with input as {
		"principal": {"type": "HUMAN", "roles": ["ROLE_VIEWER"]},
		"action": "standingOrder.pause",
	}
}

# An AI_AGENT principal never matches this HUMAN-only extension (agent access is mediated by
# agents.rego/charter_allowed via rest.rego's allow rule, not here).
test_standing_order_pause_denied_for_ai_agent if {
	count(rest.allowed_reasons) == 0 with input as {
		"principal": {"type": "AI_AGENT", "roles": ["ROLE_OPERATOR"]},
		"action": "standingOrder.pause",
	}
}

# The extension grants nothing for a different action even with a privileged role — it must not
# widen beyond the single @Authorize'd endpoint it guards.
test_other_action_not_granted_by_this_extension if {
	not "operator-standing-order-pause" in rest.allowed_reasons with input as {
		"principal": {"type": "HUMAN", "roles": ["ROLE_OPERATOR"]},
		"action": "standingOrder.cancel",
	}
}
