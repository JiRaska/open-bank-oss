# SPDX-License-Identifier: Apache-2.0
# Unit tests for card_issuance_rest_ext.rego (ADR-0034 Phase 5 bootstrap, issue #938).
#
# This is the pattern established by issue #1322: 22 of 27 `gen-*-opa-bundle.sh` generators
# embed their per-service REST extension as a bash heredoc, so there was nothing on disk for
# `opa test` to load and no test covered any of them. card-issuance-service was chosen as the
# first extraction because it is also the generator that shipped the bug #1322 cites as the
# motivating example: `card.block` was granted to ROLE_COMPLIANCE only, while
# `CardResource.block()` carries `@RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN", "ROLE_COMPLIANCE")`
# — a disjunction. The extension was therefore strictly narrower than the resource it guards, and
# flipping AUTHZ_ENFORCE=true would have 403'd every card.block by an operator (the fraud response
# for a lost/stolen card). Fixed in #1323, but only because the action list was enumerated by
# hand — this suite is the mechanical check that would have caught it, and stays in place to
# catch a future regression the same way (either direction: narrower-than-@RolesAllowed fails
# closed, wider-than-@RolesAllowed fails open per the resource comparison the base rego docs).
#
# Run from repo root: opa test openbank-infra/gitops/components/payments
# (self-contained — allowed_reasons here is a partial-set rule with no dependency on rules.yaml
# or agents.yaml, unlike rest.rego's `allow`, so this suite does not need rest.rego or the mocked
# data those tests stage. See rest_test.rego for the base-policy suite.)

package openbank.rest_test

import data.openbank.rest

# --- card.block: the #1323 regression this suite exists to pin ---

# ROLE_OPERATOR alone must grant card.block (the role #1323 found silently excluded).
test_card_block_allowed_for_operator if {
	"operator-card-write" in rest.allowed_reasons with input as {
		"principal": {"type": "HUMAN", "roles": ["ROLE_OPERATOR"]},
		"action": "card.block",
	}
}

# ROLE_ADMIN alone must also grant card.block.
test_card_block_allowed_for_admin if {
	"operator-card-write" in rest.allowed_reasons with input as {
		"principal": {"type": "HUMAN", "roles": ["ROLE_ADMIN"]},
		"action": "card.block",
	}
}

# ROLE_COMPLIANCE alone must grant card.block too — @RolesAllowed is a disjunction, so this role
# is an ADDITIONAL grantee, not a requirement layered on top of ROLE_OPERATOR/ROLE_ADMIN.
test_card_block_allowed_for_compliance_alone if {
	"compliance-card-block" in rest.allowed_reasons with input as {
		"principal": {"type": "HUMAN", "roles": ["ROLE_COMPLIANCE"]},
		"action": "card.block",
	}
}

# A principal with none of the three roles gets no allow reason for card.block.
test_card_block_denied_for_unrelated_role if {
	count(rest.allowed_reasons) == 0 with input as {
		"principal": {"type": "HUMAN", "roles": ["ROLE_VIEWER"]},
		"action": "card.block",
	}
}

# --- other CardResource actions covered by the same operator-card-write rule ---

test_card_create_allowed_for_operator if {
	"operator-card-write" in rest.allowed_reasons with input as {
		"principal": {"type": "HUMAN", "roles": ["ROLE_OPERATOR"]},
		"action": "card.create",
	}
}

test_card_suspend_allowed_for_operator if {
	"operator-card-write" in rest.allowed_reasons with input as {
		"principal": {"type": "HUMAN", "roles": ["ROLE_OPERATOR"]},
		"action": "card.suspend",
	}
}

test_card_resume_allowed_for_operator if {
	"operator-card-write" in rest.allowed_reasons with input as {
		"principal": {"type": "HUMAN", "roles": ["ROLE_OPERATOR"]},
		"action": "card.resume",
	}
}

test_card_activate_allowed_for_operator if {
	"operator-card-write" in rest.allowed_reasons with input as {
		"principal": {"type": "HUMAN", "roles": ["ROLE_OPERATOR"]},
		"action": "card.activate",
	}
}

# ROLE_COMPLIANCE alone does NOT grant card.create/.activate/.suspend/.resume — the third
# @RolesAllowed role on CardResource applies to block() only, not the fleet-wide "compliance can
# write cards" grant this extension is careful not to create.
test_card_create_denied_for_compliance_alone if {
	count(rest.allowed_reasons) == 0 with input as {
		"principal": {"type": "HUMAN", "roles": ["ROLE_COMPLIANCE"]},
		"action": "card.create",
	}
}

# An AI_AGENT principal never matches this extension's HUMAN-only rules (agent access to card
# actions is mediated by agents.rego/charter_allowed via rest.rego's allow rule, not here).
test_card_block_denied_for_ai_agent if {
	count(rest.allowed_reasons) == 0 with input as {
		"principal": {"type": "AI_AGENT", "roles": ["ROLE_OPERATOR"]},
		"action": "card.block",
	}
}

# --- card.outbox.requeue (#4005): ROLE_ADMIN only ---

# The one grant. ROLE_ADMIN matches CardOutboxAdminResource.requeueDead's @RolesAllowed exactly.
test_card_outbox_requeue_allowed_for_admin if {
	"admin-card-outbox-requeue" in rest.allowed_reasons with input as {
		"principal": {"type": "HUMAN", "roles": ["ROLE_ADMIN"]},
		"action": "card.outbox.requeue",
	}
}

# ROLE_OPERATOR must NOT reach it. This is the must-deny half, and it is the assertion that fails
# if someone later folds card.outbox.requeue into the operator-card-write action set for tidiness:
# replaying a dead-lettered event appends a permanent, undeletable duplicate to the audit chain,
# so the policy must stay exactly as narrow as the @RolesAllowed it mirrors.
test_card_outbox_requeue_denied_for_operator if {
	count(rest.allowed_reasons) == 0 with input as {
		"principal": {"type": "HUMAN", "roles": ["ROLE_OPERATOR"]},
		"action": "card.outbox.requeue",
	}
}

test_card_outbox_requeue_denied_for_compliance if {
	count(rest.allowed_reasons) == 0 with input as {
		"principal": {"type": "HUMAN", "roles": ["ROLE_COMPLIANCE"]},
		"action": "card.outbox.requeue",
	}
}

# An AI_AGENT holding ROLE_ADMIN still does not match — this extension is HUMAN-only.
test_card_outbox_requeue_denied_for_ai_agent if {
	count(rest.allowed_reasons) == 0 with input as {
		"principal": {"type": "AI_AGENT", "roles": ["ROLE_ADMIN"]},
		"action": "card.outbox.requeue",
	}
}
