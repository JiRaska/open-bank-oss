# SPDX-License-Identifier: Apache-2.0
# Unit tests for dispute_rest_ext.rego (issue #1797).
#
# Run EXPLICITLY by file (the sibling *-opa-bundle.yaml is not valid rego, and `opa test <dir>`
# would try to load it as data and die with a merge error). Load the base policy alongside the
# extension — exactly the pair the sidecar mounts — because the assertions below include
# "this read is covered by base rest.rego, so the extension deliberately does not restate it",
# which is only meaningful with rest.rego in the same evaluation:
#   opa test openbank-libs/governance/policies/rest.rego \
#            openbank-infra/gitops/components/dispute-service/dispute_rest_ext.rego \
#            openbank-infra/gitops/components/dispute-service/dispute_rest_ext_test.rego
#
# Do NOT add another service's *_rest_ext.rego to the same invocation: they all extend the
# same package and would cross-contaminate allowed_reasons (issue #1797 follow-up 2).

package openbank.rest

import rego.v1

# Real back-office staff — a human Keycloak user, NOT a service account.
operator := {"type": "HUMAN", "id": "u-op", "roles": ["ROLE_OPERATOR"]}

admin := {"type": "HUMAN", "id": "u-admin", "roles": ["ROLE_ADMIN"]}

viewer := {"type": "HUMAN", "id": "u-view", "roles": ["ROLE_VIEWER"]}

# The customer-edge M2M identity: classified HUMAN, carries ROLE_OPERATOR, reachable from a
# customer-facing service. THE reason the write rule carries a service-account exclusion.
edge_m2m := {"type": "HUMAN", "id": "service-account-openbank-edge", "roles": ["ROLE_OPERATOR"]}

# The shared backend M2M identity agent-service authenticates as on its outbound hop.
services_m2m := {"type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_OPERATOR"]}

anon := {"type": "ANONYMOUS", "id": "anonymous", "roles": []}

# --- the write plane: exactly the three actions, for real staff only ---

test_operator_updates_dispute if {
	"dispute-staff-write" in allowed_reasons with input as {
		"principal": operator,
		"action": "dispute.update",
		"resource": {"type": "dispute", "id": "d-1"},
	}
}

test_operator_resolves_dispute if {
	"dispute-staff-write" in allowed_reasons with input as {
		"principal": operator,
		"action": "dispute.resolve",
		"resource": {"type": "dispute", "id": "d-1"},
	}
}

test_operator_updates_complaint if {
	"dispute-staff-write" in allowed_reasons with input as {
		"principal": operator,
		"action": "complaint.update",
		"resource": {"type": "complaint", "id": "c-1"},
	}
}

test_admin_resolves_dispute if {
	"dispute-staff-write" in allowed_reasons with input as {
		"principal": admin,
		"action": "dispute.resolve",
		"resource": {"type": "dispute", "id": "d-1"},
	}
}

# --- THE load-bearing guard: an M2M service account with ROLE_OPERATOR must NOT write ---
#
# Both identities below satisfy `HUMAN + ROLE_OPERATOR` and both can reach the pod through
# the NetworkPolicy. If the `not startswith(input.principal.id, "service-account-")` line is
# ever deleted, these four assertions are the only thing that fails.

test_edge_m2m_cannot_resolve_dispute if {
	count(allowed_reasons) == 0 with input as {
		"principal": edge_m2m,
		"action": "dispute.resolve",
		"resource": {"type": "dispute", "id": "d-1"},
	}
}

test_edge_m2m_cannot_update_dispute if {
	count(allowed_reasons) == 0 with input as {
		"principal": edge_m2m,
		"action": "dispute.update",
		"resource": {"type": "dispute", "id": "d-1"},
	}
}

test_edge_m2m_cannot_close_complaint if {
	count(allowed_reasons) == 0 with input as {
		"principal": edge_m2m,
		"action": "complaint.update",
		"resource": {"type": "complaint", "id": "c-1"},
	}
}

test_services_m2m_cannot_resolve_dispute if {
	count(allowed_reasons) == 0 with input as {
		"principal": services_m2m,
		"action": "dispute.resolve",
		"resource": {"type": "dispute", "id": "d-1"},
	}
}

# --- the write grant is action-scoped, not a dispute.* / complaint.* prefix ---

test_operator_denied_unknown_dispute_write_action if {
	count(allowed_reasons) == 0 with input as {"principal": operator, "action": "dispute.chargeback"}
}

test_operator_denied_unknown_complaint_write_action if {
	count(allowed_reasons) == 0 with input as {"principal": operator, "action": "complaint.reopen"}
}

# The grant must not leak into another service's action namespace (defence in depth against a
# future shared-bundle refactor — the bundle is per-service today).
test_write_grant_does_not_leak_to_other_namespace if {
	count(allowed_reasons) == 0 with input as {"principal": operator, "action": "ledger.update"}
	count(allowed_reasons) == 0 with input as {"principal": operator, "action": "psd2.update"}
}

# --- viewers are on neither plane (caller-audit item 6: a deliberate non-grant) ---

test_viewer_cannot_write if {
	count(allowed_reasons) == 0 with input as {
		"principal": viewer,
		"action": "dispute.resolve",
		"resource": {"type": "dispute", "id": "d-1"},
	}
}

# Documents the CURRENT divergence between @RolesAllowed (which admits ROLE_VIEWER to every
# GET) and the rego (which does not grant a pure viewer). If a future change intentionally
# widens this, this test is the one that must be updated deliberately — not silently.
test_viewer_read_is_not_granted_today if {
	count(allowed_reasons) == 0 with input as {"principal": viewer, "action": "dispute.read"}
	count(allowed_reasons) == 0 with input as {"principal": viewer, "action": "complaint.list"}
}

# --- anonymous gets nothing: this service has no eIDAS/mTLS plane (unlike psd2) ---

test_anonymous_denied_reads if {
	count(allowed_reasons) == 0 with input as {"principal": anon, "action": "dispute.read"}
	count(allowed_reasons) == 0 with input as {"principal": anon, "action": "complaint.list"}
}

test_anonymous_denied_writes if {
	count(allowed_reasons) == 0 with input as {"principal": anon, "action": "dispute.resolve"}
	count(allowed_reasons) == 0 with input as {"principal": anon, "action": "complaint.update"}
}

# --- reads ride on base rest.rego; the extension deliberately does not restate them ---
#
# These assert the caller-audit claim that no read rule is NEEDED here. They fire on
# rest.rego's operator-read-any, which is why the base policy must be loaded alongside.

test_staff_read_covered_by_base_rule if {
	"operator-read-any" in allowed_reasons with input as {"principal": operator, "action": "dispute.read"}
	"operator-read-any" in allowed_reasons with input as {"principal": admin, "action": "complaint.read"}
}

test_edge_m2m_account_scoped_list_covered_by_base_rule if {
	"operator-read-any" in allowed_reasons with input as {
		"principal": edge_m2m,
		"action": "dispute.list",
		"resource": {"type": "dispute", "id": "a-1"},
	}
}

test_staff_complaint_list_covered_by_base_rule if {
	"operator-read-any" in allowed_reasons with input as {"principal": operator, "action": "complaint.list"}
}

# The extension must NOT be the thing granting those reads — if a future edit adds a broad
# read rule here, this catches the duplication that least-privilege review would otherwise miss.
test_extension_does_not_restate_base_reads if {
	not "dispute-staff-write" in allowed_reasons with input as {"principal": operator, "action": "dispute.read"}
	not "dispute-staff-write" in allowed_reasons with input as {"principal": operator, "action": "complaint.list"}
}
