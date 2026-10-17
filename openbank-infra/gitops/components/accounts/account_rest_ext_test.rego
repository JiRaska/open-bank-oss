# SPDX-License-Identifier: Apache-2.0
# Unit tests for account_rest_ext.rego (2026-08-05, #3734).
#
# Run EXPLICITLY by file:
#   opa test openbank-libs/governance/policies/rest.rego \
#            openbank-infra/gitops/components/accounts/account_rest_ext.rego \
#            openbank-infra/gitops/components/accounts/account_rest_ext_test.rego

package openbank.rest_test

import rego.v1

import data.openbank.rest

operator := {"service_account": false, "type": "HUMAN", "id": "u-op", "roles": ["ROLE_OPERATOR"]}

admin := {"service_account": false, "type": "HUMAN", "id": "u-admin", "roles": ["ROLE_ADMIN"]}

edge := {"service_account": true, "client_id": "openbank-edge", "type": "HUMAN", "id": "service-account-openbank-edge", "roles": ["ROLE_OPERATOR"]}

shared := {"service_account": true, "client_id": "openbank-services", "type": "HUMAN", "id": "service-account-openbank-services", "roles": ["ROLE_OPERATOR"]}

# The bundle's role_action_matrix grants ROLE_OPERATOR ALL ten account.* actions — this is what
# matrix-allows re-admits to both M2M clients without the exclusion + veto pair. Shape mirrors
# data.rules.authz.role_action_matrix[role].grant[_] (rules-opa-data.yaml).
rules_mock := {"authz": {"role_action_matrix": {"ROLE_OPERATOR": {"grant": [
	"account.approval.decide",
	"account.authorize",
	"account.close",
	"account.create",
	"account.freeze",
	"account.list",
	"account.read",
	"account.search",
	"account.unfreeze",
	"account.update",
]}}}}

# --- humans: unchanged ---

test_operator_closes_account if {
	rest.allow with input as {"principal": operator, "action": "account.close"}
		with data.rules as rules_mock
}

test_admin_freezes_account if {
	rest.allow with input as {"principal": admin, "action": "account.freeze"}
		with data.rules as rules_mock
}

# --- edge: verified customer self-service preserved via the identity rule ---

test_edge_may_create_via_identity_rule if {
	rest.allow with input as {"principal": edge, "action": "account.create"}
		with data.rules as rules_mock
}

test_edge_may_update_via_identity_rule if {
	rest.allow with input as {"principal": edge, "action": "account.update"}
		with data.rules as rules_mock
}

# --- edge: sensitive lifecycle + four-eyes decisions closed on BOTH paths ---

test_edge_denied_close if {
	not rest.allow with input as {"principal": edge, "action": "account.close"}
		with data.rules as rules_mock
}

test_edge_denied_freeze if {
	not rest.allow with input as {"principal": edge, "action": "account.freeze"}
		with data.rules as rules_mock
}

test_edge_denied_unfreeze if {
	not rest.allow with input as {"principal": edge, "action": "account.unfreeze"}
		with data.rules as rules_mock
}

test_edge_denied_authorize if {
	not rest.allow with input as {"principal": edge, "action": "account.authorize"}
		with data.rules as rules_mock
}

test_edge_denied_approval_decide if {
	not rest.allow with input as {"principal": edge, "action": "account.approval.decide"}
		with data.rules as rules_mock
}

test_edge_veto_fires_on_close if {
	rest.prohibited with input as {"principal": edge, "action": "account.close"}
		with data.rules as rules_mock
}

test_edge_no_operator_rule_on_close if {
	not "operator-account-write" in rest.allowed_reasons
		with input as {"principal": edge, "action": "account.close"}
		with data.rules as rules_mock
}

# --- shared client: read-only identity grant preserved, no operator write path ---

test_shared_may_read_via_identity_rule if {
	rest.allow with input as {"principal": shared, "action": "account.read"}
		with data.rules as rules_mock
}

test_shared_no_operator_rule_on_update if {
	not "operator-account-write" in rest.allowed_reasons
		with input as {"principal": shared, "action": "account.update"}
		with data.rules as rules_mock
}

# --- #10486 batch 5: per-service read identities (ROLE_API only, exactly as the realm grants) ---

sa_api(name) := {"service_account": true, "client_id": sprintf("openbank-%v", [name]), "type": "HUMAN", "id": sprintf("service-account-openbank-%v", [name]), "roles": ["ROLE_API"]}

b5_rules := {
	"authz": {"role_action_matrix": {"ROLE_OPERATOR": {"grant": [
		"account.approval.decide", "account.authorize", "account.close", "account.create",
		"account.freeze", "account.list", "account.read", "account.search", "account.unfreeze",
		"account.update",
	]}}},
	"money_path_services": [],
	"money_path_action_prefixes": {},
	"four_eyes": {"verbs": [], "actions": []},
	"feature_flags": {"prohibited_flag_combinations": [], "money_path_flags": []},
	"shared_m2m_write_prohibition": {"reasons": []},
}

b5_grants := {
	"analytics-sink": {"reason": "service-analytics-sink-account-read", "actions": {"account.list"}},
	"billing": {"reason": "service-billing-account-read", "actions": {"account.list", "account.read"}},
	"interest": {"reason": "service-interest-account-read", "actions": {"account.list", "account.read"}},
	"document": {"reason": "service-document-account-read", "actions": {"account.list"}},
	"lending": {"reason": "service-lending-account-read", "actions": {"account.list"}},
	"party": {"reason": "service-party-account-read", "actions": {"account.list"}},
	"tax-reporting": {"reason": "service-tax-reporting-account-read", "actions": {"account.list"}},
}

b5_all_account_actions := {
	"account.approval.decide", "account.authorize", "account.close", "account.create",
	"account.freeze", "account.list", "account.read", "account.search", "account.unfreeze",
	"account.update",
}

test_b5_each_identity_may_perform_its_granted_reads if {
	every name, g in b5_grants {
		every action in g.actions {
			d := rest.allow with input as {"principal": sa_api(name), "action": action}
				with data.rules as b5_rules
			d.allow == true
			g.reason in rest.allowed_reasons with input as {"principal": sa_api(name), "action": action}
				with data.rules as b5_rules
		}
	}
}

test_b5_each_identity_denied_everything_else if {
	every name, g in b5_grants {
		every action in b5_all_account_actions - g.actions {
			rest.allow == false with input as {"principal": sa_api(name), "action": action}
				with data.rules as b5_rules
		}
	}
}

# Another service account holding ROLE_API (mcp-service's future identity) and the shared client
# once it holds ROLE_API only: both must be denied every account read. Remove a principal.id line
# from a batch-5 rule and widen it to a prefix, and this goes red.
test_other_role_api_sa_denied_account_reads if {
	every p in [sa_api("mcp"), sa_api("kyb")] {
		every action in {"account.list", "account.read"} {
			rest.allow == false with input as {"principal": p, "action": action}
				with data.rules as b5_rules
		}
	}
}

# The shared client keeps its pre-existing account.read identity rule (service-backend-account-m2m)
# but loses account.list with ROLE_OPERATOR: that is the edge batch 5 moves off it.
test_shared_role_api_only_denied_account_list if {
	rest.allow == false with input as {"principal": sa_api("services"), "action": "account.list"}
		with data.rules as b5_rules
}

# --- ADR-0335 D2: pension-service gets the ownership projection and nothing else ---

pension := {"type": "HUMAN", "service_account": true, "client_id": "openbank-pension", "id": "service-account-openbank-pension", "roles": ["ROLE_API"]}

billing_sa := {"type": "HUMAN", "id": "service-account-openbank-billing", "roles": ["ROLE_API"]}

pension_as_agent := {"type": "AI_AGENT", "id": "service-account-openbank-pension", "roles": ["ROLE_API"]}

verify_input(principal) := {
	"principal": principal,
	"action": "account.verifyOwnership",
	"resource": {"type": "account", "id": "00000000-0000-0000-0000-0000000000aa"},
}

test_pension_may_verify_ownership if {
	rest.allow with input as verify_input(pension) with data.rules as rules_mock
	"service-account-verify-ownership" in rest.allowed_reasons with input as verify_input(pension)
		with data.rules as rules_mock
}

test_pension_may_not_read_or_list_accounts if {
	every action in {
		"account.read", "account.list", "account.search", "account.create", "account.update",
		"account.close", "account.freeze", "account.unfreeze", "account.authorize",
	} {
		not rest.allow with input as {"principal": pension, "action": action, "resource": {"type": "account", "id": "a-1"}}
			with data.rules as rules_mock
	}
}

test_other_machines_may_not_verify_ownership if {
	every principal in [shared, edge, billing_sa, pension_as_agent] {
		not rest.allow with input as verify_input(principal) with data.rules as rules_mock
	}
}

test_operator_may_verify_ownership if {
	rest.allow with input as verify_input(operator) with data.rules as rules_mock
}

sdd_sa := {"type": "HUMAN", "service_account": true, "client_id": "openbank-sdd", "id": "service-account-openbank-sdd", "roles": ["ROLE_API"]}

test_sdd_may_verify_ownership_and_nothing_else if {
	rest.allow with input as verify_input(sdd_sa) with data.rules as rules_mock
	not rest.allow with input as {"principal": sdd_sa, "action": "account.read", "resource": {"type": "account", "id": "a-1"}}
		with data.rules as rules_mock
}

# Current PEP marker must be explicit true with the matching client. Absent is the
# deliberate legacy ABI; explicit null is malformed and must not borrow that path.
tax_reporting_guard_identity := {"type": "HUMAN", "id": "service-account-openbank-tax-reporting", "roles": ["ROLE_API"]}

test_tax_reporting_current_and_absent_legacy_allowed if {
	every principal in [tax_reporting_guard_identity, object.union(tax_reporting_guard_identity, {"service_account": true, "client_id": "openbank-tax-reporting"})] {
		rest.allow.allow with input as {"principal": principal, "action": "account.list"}
	}
}

test_tax_reporting_explicit_null_marker_denied if {
	not rest.allow.allow with input as {"principal": object.union(tax_reporting_guard_identity, {"service_account": null, "client_id": "openbank-tax-reporting"}), "action": "account.list"}
}

test_tax_reporting_false_or_bad_client_binding_denied if {
	every changed in [
		{"service_account": false, "client_id": "openbank-tax-reporting"},
		{"service_account": true, "client_id": "another-client"},
		{"service_account": true},
		{"service_account": true, "client_id": "openbank-tax-reporting", "id": "service-account-another-client"},
	] {
		not rest.allow.allow with input as {"principal": object.union(tax_reporting_guard_identity, changed), "action": "account.list"}
	}
}

# ADR-0341 — pension-fund-service's own principal reads the published catalog (catalog.read) and
# nothing else; the shared and sibling principals stay denied.
pension_fund_catalog := {"type": "HUMAN", "service_account": true, "client_id": "openbank-pension-fund", "id": "service-account-openbank-pension-fund", "roles": ["CATALOG_SCOPE_READ"]}

test_pension_fund_catalog_read_allowed if {
	rest.allow.allow with input as {"principal": pension_fund_catalog, "action": "catalog.read", "resource": {"type": "catalog", "id": "r-1"}}
		with data.rules as rules_mock
}

test_pension_fund_catalog_grant_is_read_only_and_needs_the_scope if {
	every action in ["catalog.author", "catalog.publish", "catalog.pensionApproval.decide"] {
		not rest.allow.allow with input as {"principal": pension_fund_catalog, "action": action, "resource": {"type": "catalog", "id": "r-1"}}
			with data.rules as rules_mock
	}
	not rest.allow.allow with input as {"principal": object.union(pension_fund_catalog, {"roles": []}), "action": "catalog.read", "resource": {"type": "catalog", "id": "r-1"}}
		with data.rules as rules_mock
}

test_other_principals_denied_pension_fund_catalog_read if {
	every principal in [
		object.union(shared, {"roles": ["ROLE_API", "CATALOG_SCOPE_READ"]}),
		object.union(pension_fund_catalog, {"client_id": "another-client"}),
		object.union(pension_fund_catalog, {"id": "service-account-openbank-pension-fund-x"}),
	] {
		not rest.allow.allow with input as {"principal": principal, "action": "catalog.read", "resource": {"type": "catalog", "id": "r-1"}}
			with data.rules as rules_mock
	}
}
