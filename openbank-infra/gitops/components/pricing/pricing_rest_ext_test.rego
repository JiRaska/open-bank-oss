# SPDX-License-Identifier: Apache-2.0
# Unit tests for pricing_rest_ext.rego (2026-09-28).
#
# IDOR regression: "pricing-oauth-scope" originally granted access on scope possession alone,
# with no check that a resource-scoped action targeted the caller's OWN tenant — unlike the
# shared "operator-on-own-tenant" rule in rest.rego. A tenant-A principal holding the right
# scope could act on a tenant-B resource. Fixed by requiring input.resource's tenant (when
# present) to equal the principal's tenant.
#
#   opa test openbank-libs/governance/policies/rest.rego \
#            openbank-infra/gitops/components/pricing/pricing_rest_ext.rego \
#            openbank-infra/gitops/components/pricing/pricing_rest_ext_test.rego

package openbank.rest_test

import rego.v1

import data.openbank.rest

tenant_a_deal_reader := {
	"service_account": false,
	"type": "HUMAN",
	"id": "u-tenant-a",
	"attributes": {"tenant": "tenant-A", "scopes": ["pricing.deal.read"]},
}

tenant_a_quoter := {
	"service_account": false,
	"type": "HUMAN",
	"id": "u-tenant-a-quote",
	"attributes": {"tenant": "tenant-A", "scopes": ["pricing.quote"]},
}

service_account := {
	"service_account": true,
	"type": "HUMAN",
	"id": "service-account-openbank-services",
	"attributes": {"tenant": "tenant-A", "scopes": ["pricing.deal.read"]},
}

# ── must-DENY: cross-tenant resource access is an IDOR, scope alone must not grant it ──────────

test_cross_tenant_deal_read_denied if {
	not rest.allow with input as {
		"principal": tenant_a_deal_reader,
		"action": "deal.read",
		"resource": {"id": "deal-999", "attributes": {"tenant": "tenant-B"}},
	}
}

test_cross_tenant_deal_read_not_granted_by_pricing_rule if {
	not "pricing-oauth-scope" in rest.allowed_reasons with input as {
		"principal": tenant_a_deal_reader,
		"action": "deal.read",
		"resource": {"id": "deal-999", "attributes": {"tenant": "tenant-B"}},
	}
}

# A resource carrying no tenant attribute at all must not be treated as a match either
# (fail closed, never fail open on a missing attribute).
test_resource_with_no_tenant_attribute_denied if {
	not rest.allow with input as {
		"principal": tenant_a_deal_reader,
		"action": "deal.read",
		"resource": {"id": "deal-999", "attributes": {}},
	}
}

# A caller must not turn an existing-resource action into a scope-only grant by
# leaving resource out of the PDP query altogether.
test_missing_deal_resource_denied if {
	not rest.allow with input as {
		"principal": tenant_a_deal_reader,
		"action": "deal.read",
	}
}

test_missing_quote_resource_denied if {
	not rest.allow with input as {
		"principal": tenant_a_quoter,
		"action": "pricingDecision.read",
	}
}

# ── must-ALLOW controls ─────────────────────────────────────────────────────────────────────

test_same_tenant_deal_read_allowed if {
	rest.allow with input as {
		"principal": tenant_a_deal_reader,
		"action": "deal.read",
		"resource": {"id": "deal-123", "attributes": {"tenant": "tenant-A"}},
	}
}

test_resourceless_pricing_decision_allowed if {
	rest.allow with input as {
		"principal": tenant_a_quoter,
		"action": "pricingDecision.calculate",
	}
}

# ── unchanged behaviour: no blanket service-account grant ──────────────────────────────────────

test_service_account_still_denied if {
	not rest.allow with input as {
		"principal": service_account,
		"action": "deal.read",
		"resource": {"id": "deal-123", "attributes": {"tenant": "tenant-A"}},
	}
}

test_missing_scope_still_denied if {
	not rest.allow with input as {
		"principal": {
			"service_account": false,
			"type": "HUMAN",
			"id": "u-tenant-a-no-scope",
			"attributes": {"tenant": "tenant-A", "scopes": []},
		},
		"action": "deal.read",
		"resource": {"id": "deal-123", "attributes": {"tenant": "tenant-A"}},
	}
}
