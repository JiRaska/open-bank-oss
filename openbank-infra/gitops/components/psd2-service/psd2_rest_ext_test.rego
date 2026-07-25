# SPDX-License-Identifier: Apache-2.0
# Unit tests for psd2_rest_ext.rego (issue #1797).
#
# Run EXPLICITLY by file (the sibling *-opa-bundle.yaml is not valid rego, and `opa test <dir>`
# would try to load it as data and die with a merge error). Load the base policy alongside the
# extension — exactly the pair the sidecar mounts — because the assertions below include
# "this staff read is covered by base rest.rego, so the extension deliberately does not
# restate it", which is only meaningful with rest.rego in the same evaluation:
#   opa test openbank-libs/governance/policies/rest.rego \
#            openbank-infra/gitops/components/psd2-service/psd2_rest_ext.rego \
#            openbank-infra/gitops/components/psd2-service/psd2_rest_ext_test.rego
#
# Do NOT add another service's *_rest_ext.rego to the same invocation: they all extend the
# same package and would cross-contaminate allowed_reasons (issue #1797 follow-up 2).

package openbank.rest

import rego.v1

# A TPP after EidasMtlsFilter has passed it: eIDAS QWAC identity, no OIDC bearer, so
# AuthorizeInterceptor builds exactly this principal (id defaults to "anonymous").
tpp := {"type": "ANONYMOUS", "id": "anonymous", "roles": []}

operator := {"type": "HUMAN", "id": "u-op", "roles": ["ROLE_OPERATOR"]}

admin := {"type": "HUMAN", "id": "u-admin", "roles": ["ROLE_ADMIN"]}

viewer := {"type": "HUMAN", "id": "u-view", "roles": ["ROLE_VIEWER"]}

# The shared M2M identity — classified HUMAN, carries ROLE_OPERATOR. No such caller exists for
# psd2-service today; these are regression guards that none is accidentally admitted.
services_m2m := {"type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_OPERATOR"]}

# --- the TPP plane: all five actions the 21 annotated endpoints use ---

test_tpp_lists_accounts if {
	"psd2-tpp-eidas-qwac" in allowed_reasons with input as {"principal": tpp, "action": "psd2.list"}
}

test_tpp_reads_balances if {
	"psd2-tpp-eidas-qwac" in allowed_reasons with input as {
		"principal": tpp,
		"action": "psd2.read",
		"resource": {"type": "psd2", "id": "CZ6508000000192000145399"},
	}
}

test_tpp_creates_consent if {
	"psd2-tpp-eidas-qwac" in allowed_reasons with input as {"principal": tpp, "action": "psd2.create"}
}

test_tpp_initiates_payment if {
	"psd2-tpp-eidas-qwac" in allowed_reasons with input as {"principal": tpp, "action": "psd2.initiate"}
}

test_tpp_deletes_consent if {
	"psd2-tpp-eidas-qwac" in allowed_reasons with input as {
		"principal": tpp,
		"action": "psd2.delete",
		"resource": {"type": "psd2", "id": "c-1"},
	}
}

# --- the anonymous grant is action-scoped, not a psd2.* prefix, and not fleet-wide ---

# A psd2.* action that is NOT in the enumerated set must not inherit the grant by name —
# this is the guard on the "explicit set, never startswith" decision in the rule.
test_anonymous_denied_unknown_psd2_action if {
	count(allowed_reasons) == 0 with input as {"principal": tpp, "action": "psd2.approve"}
}

# The anonymous grant must not leak to any other service's action namespace, even though the
# bundle is per-service (defence in depth against a future shared-bundle refactor).
test_anonymous_denied_other_namespace if {
	count(allowed_reasons) == 0 with input as {"principal": tpp, "action": "ledger.post"}
}

test_anonymous_denied_consent_namespace if {
	count(allowed_reasons) == 0 with input as {"principal": tpp, "action": "consent.revoke"}
}

# --- staff: reads ride on base rest.rego's operator-read-any; writes are NOT granted ---

test_operator_reads_via_base_rule if {
	"operator-read-any" in allowed_reasons with input as {"principal": operator, "action": "psd2.read"}
}

test_admin_lists_via_base_rule if {
	"operator-read-any" in allowed_reasons with input as {"principal": admin, "action": "psd2.list"}
}

# An operator must not be able to initiate a payment or revoke a consent through the TPP
# surface — no rule in this file grants a HUMAN principal a psd2 write, and operator-read-any
# is verb-scoped to {list, read}.
test_operator_cannot_initiate_payment if {
	count(allowed_reasons) == 0 with input as {"principal": operator, "action": "psd2.initiate"}
}

test_admin_cannot_create_consent if {
	count(allowed_reasons) == 0 with input as {"principal": admin, "action": "psd2.create"}
}

test_admin_cannot_delete_consent if {
	count(allowed_reasons) == 0 with input as {
		"principal": admin,
		"action": "psd2.delete",
		"resource": {"type": "psd2", "id": "c-1"},
	}
}

test_viewer_denied_everything if {
	count(allowed_reasons) == 0 with input as {"principal": viewer, "action": "psd2.read"}
	count(allowed_reasons) == 0 with input as {"principal": viewer, "action": "psd2.initiate"}
}

# --- no M2M service account is admitted to the psd2 write surface ---

test_services_m2m_cannot_initiate_payment if {
	count(allowed_reasons) == 0 with input as {"principal": services_m2m, "action": "psd2.initiate"}
}

test_services_m2m_cannot_delete_consent if {
	count(allowed_reasons) == 0 with input as {
		"principal": services_m2m,
		"action": "psd2.delete",
		"resource": {"type": "psd2", "id": "c-1"},
	}
}
