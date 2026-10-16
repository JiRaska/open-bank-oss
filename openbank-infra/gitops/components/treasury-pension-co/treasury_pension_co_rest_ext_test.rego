# SPDX-License-Identifier: Apache-2.0
# Tests for treasury_pension_co_rest_ext.rego, evaluated with the real base rest.rego, the bank's
# treasury_rest_ext.rego and the REAL derived rules data (the bundle this instance mounts) — not a
# mock: the base read-any rules are part of what is under test. Run by file:
#   opa test rest.rego agents.rego treasury/treasury_rest_ext.rego \
#            treasury-pension-co/treasury_pension_co_rest_ext*.rego <dir with rules/data.yaml, agents/data.yaml>
# CI (opa-policy.yml) composes it from these directives:
# opa-test-with: openbank-infra/opa/policies/agents.rego
# opa-test-with: openbank-infra/gitops/components/treasury/treasury_rest_ext.rego
# opa-test-data: real
package openbank.rest_test

import rego.v1

import data.openbank.rest

tax := {"type": "HUMAN", "client_id": "openbank-tax-reporting", "service_account": true, "id": "service-account-openbank-tax-reporting", "roles": ["ROLE_API"]}

shared := {"type": "HUMAN", "service_account": true, "id": "service-account-openbank-services", "roles": ["ROLE_OPERATOR", "ROLE_API", "ROLE_COMPLIANCE"]}

approver := {"type": "HUMAN", "service_account": false, "id": "alice", "roles": ["ROLE_TREASURY_APPROVER"]}

dealer := {"type": "HUMAN", "service_account": false, "id": "bob", "roles": ["ROLE_TREASURY_DEALER"]}

admin := {"type": "HUMAN", "service_account": false, "id": "carol", "roles": ["ROLE_ADMIN", "ROLE_OPERATOR"]}

allow(p, a) if {
	rest.allow.allow with input as {"principal": p, "action": a, "resource": "2026-12-31"}
}

# must-ALLOW controls
test_tax_reporting_reads_portfolio if allow(tax, "treasury.portfolio.inspect")

test_approver_uploads_statement if allow(approver, "treasury.portfolio.upload")

test_staff_reads_portfolio if allow(dealer, "treasury.portfolio.inspect")

# must-DENY
test_tax_reporting_cannot_upload if not allow(tax, "treasury.portfolio.upload")

test_tax_reporting_no_other_treasury_read if not allow(tax, "treasury.deal.read")

test_shared_client_cannot_read_portfolio if not allow(shared, "treasury.portfolio.inspect")

test_shared_client_cannot_read_deals if not allow(shared, "treasury.deal.read")

test_other_service_account_with_api_role_denied if {
	not allow({"type": "HUMAN", "service_account": true, "id": "service-account-openbank-treasury", "roles": ["ROLE_API"]}, "treasury.portfolio.inspect")
}

test_dealer_cannot_upload if not allow(dealer, "treasury.portfolio.upload")

test_no_deal_booking_on_this_instance if not allow(dealer, "treasury.deal.draft")

test_no_deal_approval_on_this_instance if not allow(approver, "treasury.deal.approve")

test_no_settle_on_this_instance if not allow(approver, "treasury.deal.settle")

test_no_nostro_upload_on_this_instance if not allow(approver, "treasury.nostro.upload")

test_admin_cannot_read_deals_here if not allow(admin, "treasury.deal.read")

test_agent_gets_nothing if not allow({"type": "AI_AGENT", "id": "treasury-dealing-assistant", "roles": []}, "treasury.deal.draft")

test_anonymous_denied if not allow({"type": "ANONYMOUS", "id": "", "roles": []}, "treasury.portfolio.inspect")

# The verified producer flag added on main must not grant staff authority to renamed machines.
test_renamed_machine_cannot_upload if {
	not allow({"type": "HUMAN", "id": "renamed-client", "service_account": true, "roles": ["ROLE_TREASURY_APPROVER"]}, "treasury.portfolio.upload")
}

test_missing_flag_cannot_upload if {
	not allow({"type": "HUMAN", "id": "alice", "roles": ["ROLE_TREASURY_APPROVER"]}, "treasury.portfolio.upload")
}

# The instance exception must itself be bound to the verified named client, even
# though the bank grant independently checks the same identity.
test_instance_tax_exception_rejects_person_marker if {
	p := object.union(tax, {"service_account": false})
	not rest.treasury_pension_co_tax_reporting_read with input as {"principal": p, "action": "treasury.portfolio.inspect"}
	not allow(p, "treasury.portfolio.inspect")
}

test_instance_tax_exception_requires_current_client_binding if {
	every p in [
		object.union(tax, {"client_id": "openbank-other"}),
		object.remove(tax, {"client_id"}),
		object.union(tax, {"service_account": "true"}),
	] {
		not rest.treasury_pension_co_tax_reporting_read with input as {"principal": p, "action": "treasury.portfolio.inspect"}
		not allow(p, "treasury.portfolio.inspect")
	}
}

test_instance_veto_blocks_renamed_or_unclassified_machine_even_with_parent_grant if {
	every p in [
		{"type": "HUMAN", "id": "renamed-client", "service_account": true, "client_id": "openbank-other", "roles": ["ROLE_OPERATOR"]},
		{"type": "HUMAN", "id": "unclassified", "roles": ["ROLE_OPERATOR"]},
	] {
		not rest.allow.allow with input as {"principal": p, "action": "treasury.portfolio.inspect"} with data.openbank.rest.allowed_reasons as {"future-parent-read"}
	}
}

test_instance_retains_absent_marker_tax_compatibility if {
	p := object.remove(tax, {"service_account", "client_id"})
	allow(p, "treasury.portfolio.inspect")
	not allow(p, "treasury.portfolio.upload")
}

# The company exception itself must not confuse JSON null with the shared
# helper's old absent-marker compatibility path, even if the bank grant denies it.
test_company_explicit_null_marker_is_not_legacy if {
	every claims in [
		{"service_account": null},
		{"service_account": null, "client_id": ""},
		{"service_account": null, "client_id": "openbank-tax-reporting"},
		{"service_account": null, "client_id": "openbank-other"},
	] {
		p := object.union(object.remove(tax, {"client_id"}), claims)
		not rest.treasury_pension_co_tax_reporting_read with input as {"principal": p, "action": "treasury.portfolio.inspect"}
		not allow(p, "treasury.portfolio.inspect")
	}
}
