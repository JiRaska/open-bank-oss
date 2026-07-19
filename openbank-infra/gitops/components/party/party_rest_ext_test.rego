# SPDX-License-Identifier: Apache-2.0
# Unit tests for party_rest_ext.rego (ADR-0034 Phase 5).
#
# Context: party-service enforced authz (`authz.enforce: "${AUTHZ_ENFORCE:true}"`) while its
# gitops manifest declared no OPA sidecar, so AuthorizeInterceptor failed closed on every
# @Authorize action — the endpoints were bricked. This suite pins the policy that PR wires up.
#
# The negative tests matter more than the positive ones here. The customer-edge M2M identity
# (service-account-openbank-edge) is classified HUMAN and carries ROLE_OPERATOR in the realm,
# so the natural one-line "operator writes party.*" rule would silently hand the public
# customer edge the ability to merge and retire arbitrary party identities. The
# not-startswith(service-account-) guard is what prevents that, and
# test_party_merge_denied_for_edge_m2m is the assertion that would fail if anyone removes it.
#
# Run from repo root, naming the two .rego files explicitly:
#   opa test openbank-infra/gitops/components/party/party_rest_ext.rego \
#            openbank-infra/gitops/components/party/party_rest_ext_test.rego
# Do NOT pass the directory — `opa test <dir>` also tries to load every .yaml in it as data and
# dies with "merge error" on the Rollout/ConfigMap manifests. (The same reason the directory
# form documented at the top of card_issuance_rest_ext_test.rego does not work either.)
#
# Self-contained — allowed_reasons here is a partial-set rule with no dependency on rules.yaml
# or agents.yaml, unlike rest.rego's `allow`. See rest_test.rego for the base-policy suite.

package openbank.rest_test

import data.openbank.rest

# --- party.merge (ADR-0179): the action that exposed the missing PDP ---

test_party_merge_allowed_for_operator if {
	"operator-party-write" in rest.allowed_reasons with input as {
		"principal": {"type": "HUMAN", "id": "alice", "roles": ["ROLE_OPERATOR"]},
		"action": "party.merge",
	}
}

test_party_merge_allowed_for_admin if {
	"operator-party-write" in rest.allowed_reasons with input as {
		"principal": {"type": "HUMAN", "id": "bob", "roles": ["ROLE_ADMIN"]},
		"action": "party.merge",
	}
}

# The guard rail. PartyResource.mergeParty retires an identity; reaching it from the public
# customer edge would be an identity-takeover primitive. The edge's service account is HUMAN
# with ROLE_OPERATOR, so ONLY the service-account- exclusion keeps it out.
test_party_merge_denied_for_edge_m2m if {
	count(rest.allowed_reasons) == 0 with input as {
		"principal": {
			"type": "HUMAN",
			"id": "service-account-openbank-edge",
			"roles": ["ROLE_OPERATOR"],
		},
		"action": "party.merge",
	}
}

# Any other M2M service account is equally excluded — the guard is a prefix, not an allow-list
# of one client.
test_party_merge_denied_for_other_m2m if {
	count(rest.allowed_reasons) == 0 with input as {
		"principal": {
			"type": "HUMAN",
			"id": "service-account-openbank-services",
			"roles": ["ROLE_OPERATOR"],
		},
		"action": "party.merge",
	}
}

test_party_merge_denied_for_unrelated_role if {
	count(rest.allowed_reasons) == 0 with input as {
		"principal": {"type": "HUMAN", "id": "carol", "roles": ["ROLE_VIEWER"]},
		"action": "party.merge",
	}
}

# --- party.consent.update: the one action with a real M2M caller ---

# CustomerEdgeResource.updateConsent (PATCH /profile/consent) forwards the mobile app's
# marketing-consent toggle under the edge's client_credentials token. Without this grant the
# wiring of the PDP would turn today's 422 into a 403 — same broken toggle, new status code.
test_party_consent_update_allowed_for_edge_m2m if {
	"service-edge-party-consent-m2m" in rest.allowed_reasons with input as {
		"principal": {
			"type": "HUMAN",
			"id": "service-account-openbank-edge",
			"roles": ["ROLE_OPERATOR"],
		},
		"action": "party.consent.update",
	}
}

test_party_consent_update_allowed_for_operator if {
	"operator-party-write" in rest.allowed_reasons with input as {
		"principal": {"type": "HUMAN", "id": "alice", "roles": ["ROLE_OPERATOR"]},
		"action": "party.consent.update",
	}
}

# --- party.update ---

test_party_update_allowed_for_operator if {
	"operator-party-write" in rest.allowed_reasons with input as {
		"principal": {"type": "HUMAN", "id": "alice", "roles": ["ROLE_OPERATOR"]},
		"action": "party.update",
	}
}

# The edge grant is action-scoped to party.consent.update, NOT a "party." family prefix.
# party.update has no in-repo caller (every customer-edge call to /parties/{id} is a GET), so
# granting it would be speculative — the account-service rego's standing rule.
test_party_update_denied_for_edge_m2m if {
	count(rest.allowed_reasons) == 0 with input as {
		"principal": {
			"type": "HUMAN",
			"id": "service-account-openbank-edge",
			"roles": ["ROLE_OPERATOR"],
		},
		"action": "party.update",
	}
}

# --- party:resolve — deliberately NOT granted ---

# Dead endpoint: no in-repo caller (the live ADR-0072 dedup gate is pid-service's own
# /parties/resolve), and its @RolesAllowed requires ROLE_SERVICE, which no realm client holds.
# Note the COLON separator — a future rule written as startswith(input.action, "party.") would
# silently not match it. This test pins the deny so reviving the endpoint is a deliberate act.
test_party_resolve_not_granted_to_operator if {
	count(rest.allowed_reasons) == 0 with input as {
		"principal": {"type": "HUMAN", "id": "alice", "roles": ["ROLE_OPERATOR"]},
		"action": "party:resolve",
	}
}

test_party_resolve_not_granted_to_admin if {
	count(rest.allowed_reasons) == 0 with input as {
		"principal": {"type": "HUMAN", "id": "bob", "roles": ["ROLE_ADMIN"]},
		"action": "party:resolve",
	}
}

# --- principal-type hygiene ---

# rules.yaml: authz_policy.principal_type_service_unreachable — AuthorizeInterceptor never emits
# principal.type == "SERVICE", so a rule gated on it is dead code that fails closed. Asserting
# the absence here keeps a future edit from "fixing" the M2M grant that way.
test_service_principal_type_grants_nothing if {
	count(rest.allowed_reasons) == 0 with input as {
		"principal": {
			"type": "SERVICE",
			"id": "service-account-openbank-edge",
			"roles": ["ROLE_OPERATOR"],
		},
		"action": "party.consent.update",
	}
}
