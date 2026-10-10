# SPDX-License-Identifier: Apache-2.0
# Tests for treasury_pension_co_rest_ext.rego, evaluated with the real base rest.rego, the bank's
# treasury_rest_ext.rego and the REAL derived rules data (the bundle this instance mounts) — not a
# mock: operator_read_any_excluded_actions is part of what is under test. Run by file:
#   opa test rest.rego agents.rego treasury/treasury_rest_ext.rego \
#            treasury-pension-co/treasury_pension_co_rest_ext*.rego <dir with rules/data.yaml, agents/data.yaml>
package openbank.rest_test

import rego.v1

import data.openbank.rest

tax := {"type": "HUMAN", "id": "service-account-openbank-tax-reporting", "roles": ["ROLE_API"]}

shared := {"type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_OPERATOR", "ROLE_API", "ROLE_COMPLIANCE"]}

approver := {"type": "HUMAN", "id": "alice", "roles": ["ROLE_TREASURY_APPROVER"]}

dealer := {"type": "HUMAN", "id": "bob", "roles": ["ROLE_TREASURY_DEALER"]}

admin := {"type": "HUMAN", "id": "carol", "roles": ["ROLE_ADMIN", "ROLE_OPERATOR"]}

allow(p, a) if {
	rest.allow.allow with input as {"principal": p, "action": a, "resource": "2026-12-31"}
}

# must-ALLOW controls
test_tax_reporting_reads_portfolio if allow(tax, "treasury.portfolio.read")

test_approver_uploads_statement if allow(approver, "treasury.portfolio.upload")

test_staff_reads_portfolio if allow(dealer, "treasury.portfolio.read")

# must-DENY
test_tax_reporting_cannot_upload if not allow(tax, "treasury.portfolio.upload")

test_tax_reporting_no_other_treasury_read if not allow(tax, "treasury.deal.read")

test_shared_client_cannot_read_portfolio if not allow(shared, "treasury.portfolio.read")

test_shared_client_cannot_read_deals if not allow(shared, "treasury.deal.read")

test_other_service_account_with_api_role_denied if {
	not allow({"type": "HUMAN", "id": "service-account-openbank-treasury", "roles": ["ROLE_API"]}, "treasury.portfolio.read")
}

test_dealer_cannot_upload if not allow(dealer, "treasury.portfolio.upload")

test_no_deal_booking_on_this_instance if not allow(dealer, "treasury.deal.draft")

test_no_deal_approval_on_this_instance if not allow(approver, "treasury.deal.approve")

test_no_settle_on_this_instance if not allow(approver, "treasury.deal.settle")

test_no_nostro_upload_on_this_instance if not allow(approver, "treasury.nostro.upload")

test_admin_cannot_read_deals_here if not allow(admin, "treasury.deal.read")

test_agent_gets_nothing if not allow({"type": "AI_AGENT", "id": "treasury-dealing-assistant", "roles": []}, "treasury.deal.draft")

test_anonymous_denied if not allow({"type": "ANONYMOUS", "id": "", "roles": []}, "treasury.portfolio.read")
