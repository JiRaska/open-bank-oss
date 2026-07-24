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

# ROLE_OPERATOR alone must grant standingOrder.pause.
test_standing_order_pause_allowed_for_operator if {
	"operator-standing-order-pause" in rest.allowed_reasons with input as {
		"principal": {"type": "HUMAN", "roles": ["ROLE_OPERATOR"]},
		"action": "standingOrder.pause",
	}
}

# ROLE_ADMIN alone must also grant standingOrder.pause.
test_standing_order_pause_allowed_for_admin if {
	"operator-standing-order-pause" in rest.allowed_reasons with input as {
		"principal": {"type": "HUMAN", "roles": ["ROLE_ADMIN"]},
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
