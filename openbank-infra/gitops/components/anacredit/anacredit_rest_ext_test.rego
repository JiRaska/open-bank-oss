# SPDX-License-Identifier: Apache-2.0
# Unit tests for anacredit_rest_ext.rego (ADR-0034 Phase 5 bootstrap, issue #938).
#
# Follows the extract-and-test pattern of issue #1322 (see card_issuance_rest_ext_test.rego): the
# extension lives on disk as a real .rego file so `opa test` loads and covers it, rather than as a
# bash heredoc inside the generator. Until #4228 this extension WAS a heredoc, so the rule below
# had never been covered by any suite — opa-policy.yml discovers suites by the
# *_rest_ext_test.rego / *_rest_ext.rego file pair, and a heredoc has no file to pair with.
#
# Run from repo root (name the files explicitly — `opa test <dir>` also tries to load every
# .yaml in the dir as data and dies with a merge error):
#   opa test openbank-libs/governance/policies/rest.rego \
#            openbank-infra/gitops/components/anacredit/anacredit_rest_ext.rego \
#            openbank-infra/gitops/components/anacredit/anacredit_rest_ext_test.rego
# (allowed_reasons here is a partial-set rule with no dependency on rules.yaml or agents.yaml.)

package openbank.rest_test

import data.openbank.rest

# ── The shared-M2M exclusion (GHSA-58jq-9hq3-66jr, issue #4228) ───────────────────────────────
#
# THE point of this file. Strip `not startswith(input.principal.id, "service-account-")` from
# operator-anacredit-create and the next two tests go red while every other test here stays
# green — which is what makes the exclusion falsifiable rather than merely present.

# The shared backend identity carries ROLE_OPERATOR (docker + CI realms) and is classified HUMAN,
# so without the exclusion every backend service could feed the ECB regulatory return.
test_anacredit_create_denied_for_shared_service_account if {
	count(rest.allowed_reasons) == 0 with input as {
		"principal": {
			"type": "HUMAN",
			"id": "service-account-openbank-services",
			"roles": ["ROLE_OPERATOR", "ROLE_ADMIN"],
		},
		"action": "anacredit.create",
	}
}

# ...and the exclusion is not narrowed to one client id: ANY service-account is barred, including
# the edge client, which holds ROLE_OPERATOR in the deployed realm template.
test_anacredit_create_denied_for_edge_service_account if {
	count(rest.allowed_reasons) == 0 with input as {
		"principal": {
			"type": "HUMAN",
			"id": "service-account-openbank-edge",
			"roles": ["ROLE_OPERATOR"],
		},
		"action": "anacredit.create",
	}
}

# ── The human path, unaffected by the above ───────────────────────────────────────────────────

# A real operator keeps the grant, and gets exactly the one reason — asserting the SET rather
# than `allow` is what distinguishes "still works" from "still works for the right reason".
test_anacredit_create_allowed_for_human_operator if {
	rest.allowed_reasons == {"operator-anacredit-create"} with input as {
		"principal": {"type": "HUMAN", "id": "alice", "roles": ["ROLE_OPERATOR"]},
		"action": "anacredit.create",
	}
}

test_anacredit_create_allowed_for_human_admin if {
	"operator-anacredit-create" in rest.allowed_reasons with input as {
		"principal": {"type": "HUMAN", "id": "alice", "roles": ["ROLE_ADMIN"]},
		"action": "anacredit.create",
	}
}

# A principal with an unrelated role gets no allow reason.
test_anacredit_create_denied_for_unrelated_role if {
	count(rest.allowed_reasons) == 0 with input as {
		"principal": {"type": "HUMAN", "id": "bob", "roles": ["ROLE_VIEWER"]},
		"action": "anacredit.create",
	}
}

# An AI_AGENT principal never matches this HUMAN-only extension (agent access is mediated by
# agents.rego/charter_allowed via rest.rego's allow rule, not here).
test_anacredit_create_denied_for_ai_agent if {
	count(rest.allowed_reasons) == 0 with input as {
		"principal": {"type": "AI_AGENT", "id": "agent-1", "roles": ["ROLE_OPERATOR"]},
		"action": "anacredit.create",
	}
}

# The extension must not widen beyond the single write it guards. anacredit.list/.read are served
# by base rest.rego's operator-read-any, which this suite does not load data for; asserting the
# absence of THIS reason is the scoped claim.
test_other_anacredit_action_not_granted_by_this_extension if {
	not "operator-anacredit-create" in rest.allowed_reasons with input as {
		"principal": {"type": "HUMAN", "id": "alice", "roles": ["ROLE_OPERATOR"]},
		"action": "anacredit.list",
	}
}
