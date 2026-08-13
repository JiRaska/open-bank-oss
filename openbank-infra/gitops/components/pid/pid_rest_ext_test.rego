# SPDX-License-Identifier: Apache-2.0
# Unit tests for pid_rest_ext.rego (ADR-0034, ADR-0094, ADR-0072).
#
# Follows the extract-and-test pattern of issue #1322 (see card_issuance_rest_ext_test.rego): the
# extension lives on disk as a real .rego file so `opa test` loads and covers it, rather than as a
# bash heredoc inside the generator. Until #4228 this extension WAS a heredoc, so none of its
# rules had ever been covered by any suite — opa-policy.yml discovers suites by the
# *_rest_ext_test.rego / *_rest_ext.rego file pair, and a heredoc has no file to pair with.
#
# Run from repo root (name the files explicitly — `opa test <dir>` also tries to load every
# .yaml in the dir as data and dies with a merge error):
#   opa test openbank-libs/governance/policies/rest.rego \
#            openbank-infra/gitops/components/pid/pid_rest_ext.rego \
#            openbank-infra/gitops/components/pid/pid_rest_ext_test.rego
# (allowed_reasons here is a partial-set rule with no dependency on rules.yaml or agents.yaml.)

package openbank.rest_test

import data.openbank.rest

# ── The shared-M2M exclusion on party.changeStatus (GHSA-58jq-9hq3-66jr, issue #4228) ─────────
#
# THE point of the next two tests. Strip `not startswith(input.principal.id, "service-account-")`
# from operator-party-status and both go red while every other test here stays green — which is
# what makes the exclusion falsifiable rather than merely present. pid-service runs
# AUTHZ_ENFORCE=true, so this rule is enforced in production, not advisory.

# The shared backend identity carries ROLE_OPERATOR (docker + CI realms) and is classified HUMAN,
# so without the exclusion it reached a party lifecycle write. It must not.
test_party_status_denied_for_shared_service_account if {
	not "operator-party-status" in rest.allowed_reasons with input as {
		"principal": {
			"type": "HUMAN",
			"id": "service-account-openbank-services",
			"roles": ["ROLE_OPERATOR", "ROLE_ADMIN"],
		},
		"action": "party.changeStatus",
	}
}

# ...and the exclusion is not narrowed to one client id: ANY service-account is barred, including
# the edge client, which holds ROLE_OPERATOR in the deployed realm template.
test_party_status_denied_for_edge_service_account if {
	not "operator-party-status" in rest.allowed_reasons with input as {
		"principal": {
			"type": "HUMAN",
			"id": "service-account-openbank-edge",
			"roles": ["ROLE_OPERATOR"],
		},
		"action": "party.changeStatus",
	}
}

# The human path is unaffected — asserting the exact reason set rather than `allow` is what
# distinguishes "still works" from "still works for the right reason".
test_party_status_allowed_for_human_operator if {
	rest.allowed_reasons == {"operator-party-status"} with input as {
		"principal": {"type": "HUMAN", "id": "alice", "roles": ["ROLE_OPERATOR"]},
		"action": "party.changeStatus",
	}
}

test_party_status_allowed_for_human_admin if {
	"operator-party-status" in rest.allowed_reasons with input as {
		"principal": {"type": "HUMAN", "id": "alice", "roles": ["ROLE_ADMIN"]},
		"action": "party.changeStatus",
	}
}

test_party_status_denied_for_unrelated_role if {
	count(rest.allowed_reasons) == 0 with input as {
		"principal": {"type": "HUMAN", "id": "bob", "roles": ["ROLE_VIEWER"]},
		"action": "party.changeStatus",
	}
}

# ── pid.resolve is a READ, and keeps its role-only grant deliberately ─────────────────────────
#
# It is `@GET /api/v1/parties/pid/resolve` behind `@RolesAllowed(Roles.API)`. The reason was
# renamed `operator-pid-resolve` -> `operator-pid-resolve-read` in #4228 so the name states what
# the rule does; these tests pin the new name so a silent revert to the old one is caught.
test_pid_resolve_read_allowed_for_human_operator if {
	"operator-pid-resolve-read" in rest.allowed_reasons with input as {
		"principal": {"type": "HUMAN", "id": "alice", "roles": ["ROLE_OPERATOR"]},
		"action": "pid.resolve",
	}
}

# The old write-shaped name must not resolve any more — this is the assertion that would fail if
# someone reinstated it, and it is why the rename is not merely cosmetic.
test_old_pid_resolve_reason_name_is_gone if {
	not "operator-pid-resolve" in rest.allowed_reasons with input as {
		"principal": {"type": "HUMAN", "id": "alice", "roles": ["ROLE_OPERATOR"]},
		"action": "pid.resolve",
	}
}

# ── The identity.* family and the customer EUDI path, previously untested entirely ────────────

test_identity_family_allowed_for_human_operator if {
	"operator-identity-write" in rest.allowed_reasons with input as {
		"principal": {"type": "HUMAN", "id": "alice", "roles": ["ROLE_OPERATOR"]},
		"action": "identity.case.decide",
	}
}

# The family prefix must not leak past `identity.` — party.changeStatus and pid.resolve have
# their own rules precisely because operator-identity-write does not cover them.
test_identity_family_does_not_cover_party_status if {
	not "operator-identity-write" in rest.allowed_reasons with input as {
		"principal": {"type": "HUMAN", "id": "alice", "roles": ["ROLE_OPERATOR"]},
		"action": "party.changeStatus",
	}
}

# An authenticated customer (no role) may request their own EUDI presentation; the partyId match
# is enforced in the handler, OPA grants the action class only.
test_customer_eudi_request_allowed_for_authenticated_customer if {
	"customer-eudi-request" in rest.allowed_reasons with input as {
		"principal": {"type": "HUMAN", "id": "customer-42", "roles": []},
		"action": "identity.eudi.request",
	}
}

# ...but an unauthenticated principal has an empty id and gets nothing.
test_customer_eudi_request_denied_for_empty_id if {
	count(rest.allowed_reasons) == 0 with input as {
		"principal": {"type": "HUMAN", "id": "", "roles": []},
		"action": "identity.eudi.request",
	}
}

# An AI_AGENT principal never matches this HUMAN-only extension (agent access is mediated by
# agents.rego/charter_allowed via rest.rego's allow rule, not here).
test_pid_ext_denied_for_ai_agent if {
	count(rest.allowed_reasons) == 0 with input as {
		"principal": {"type": "AI_AGENT", "id": "agent-1", "roles": ["ROLE_OPERATOR"]},
		"action": "party.changeStatus",
	}
}
