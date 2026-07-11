# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# Unit tests for the agent tool-call authorization policy (ADR-0031 D2). These prove the
# deny-by-default contract end-to-end with inline charter data, so the policy is verified
# before it is wired into a live OPA sidecar. Run: `opa test openbank-infra/opa/policies`.
#
# The mock data mirrors the two sample charters and tool_tiers in
# openbank-libs/governance/agents.yaml (the production data source).

package openbank.agents_test

import data.openbank.agents

# Mirror of openbank-libs/governance/agents.yaml (the bits the policy reads).
charters := {
	"agents": [
		{
			"id": "compliance-officer",
			"plane": "control",
			"tools": {
				"allow": ["query.ledger.readonly", "query.gl.readonly", "query.catalog.readonly", "read.logs", "read.governance", "draft.ticket"],
				"deny": ["money.*", "gh.pr.*", "*.write"],
			},
		},
		{
			"id": "ledger-domain-engineer",
			"plane": "development",
			"skills": ["ship-check", "bump", "open-pr", "release"],
			"tools": {
				"allow": ["git.branch", "git.commit.signed", "gh.pr.open", "draft.adr", "run.skill", "read.governance"],
				"deny": ["gh.pr.merge", "gh.pr.approve", "money.*"],
			},
		},
		# Mirrors agents.yaml: rca-investigator (control, HolmesGPT read-only RCA, ADR-0088).
		{
			"id": "rca-investigator",
			"plane": "control",
			"tools": {
				"allow": ["query.observability.readonly", "read.logs", "read.governance", "draft.ticket"],
				"deny": ["money.*", "gh.pr.*", "*.write", "secrets.read.raw"],
			},
		},
		# Mirrors agents.yaml: customer-copilot (customer plane, ADR-0089). Note: its charter deny
		# does NOT carry "*.write" — out-of-scope tools fall through to deny-by-default instead.
		{
			"id": "customer-copilot",
			"plane": "customer",
			"tools": {
				"allow": ["query.balance.readonly", "query.transaction.readonly", "propose.payment", "propose.card_freeze", "propose.dispute"],
				"deny": ["money.*", "gh.pr.*", "secrets.read.raw"],
			},
		},
	],
	"tool_tiers": {"deny": ["money.transfer", "money.post.ledger", "gh.pr.merge", "gh.pr.approve", "secrets.read.raw"]},
}

# A chartered control agent may call a read tool on its allowlist.
test_allow_read_for_chartered_control_agent if {
	agents.allow with data.agents as charters
		with input as {"agent": "compliance-officer", "tool": "query.ledger.readonly", "resource": "acct-1"}
}

# query.gl.readonly (GL aggregate / trial-balance read, #1966 / issue #401) is a first-class
# read capability once declared in a charter's allowlist — the MCP gate must let get_trial_balance
# through for an agent that holds it, instead of the deny-by-default that made it unreachable.
test_allow_gl_readonly_for_chartered_agent if {
	agents.allow with data.agents as charters
		with input as {"agent": "compliance-officer", "tool": "query.gl.readonly", "resource": "trial-balance"}
}

# An agent whose charter does NOT list query.gl.readonly is still denied it (deny-by-default holds).
test_deny_gl_readonly_when_not_chartered if {
	not agents.allow with data.agents as charters
		with input as {"agent": "ledger-domain-engineer", "tool": "query.gl.readonly", "resource": "trial-balance"}
}

test_allow_decision_reason_and_passthrough if {
	d := agents.decision with data.agents as charters
		with input as {"agent": "compliance-officer", "tool": "read.governance", "resource": "rules.yaml"}
	d.allow == true
	d.reason == "allowed by charter"
	d.agent == "compliance-officer"
	d.tool == "read.governance"
	d.resource == "rules.yaml"
}

# An unknown agent (no charter) is denied by default.
test_deny_unknown_agent if {
	not agents.allow with data.agents as charters
		with input as {"agent": "ghost", "tool": "query.catalog.readonly", "resource": null}
	agents.decision.reason == "no matching allow rule" with data.agents as charters
		with input as {"agent": "ghost", "tool": "query.catalog.readonly", "resource": null}
}

# A tool on a charter's own allowlist but unknown to any agent still denies.
test_deny_tool_not_in_allowlist if {
	not agents.allow with data.agents as charters
		with input as {"agent": "compliance-officer", "tool": "read.unknown", "resource": null}
	agents.decision.reason == "no matching allow rule" with data.agents as charters
		with input as {"agent": "compliance-officer", "tool": "read.unknown", "resource": null}
}

# The hard-denied tier is forbidden for every agent, regardless of charter.
test_hard_denied_money_transfer if {
	not agents.allow with data.agents as charters
		with input as {"agent": "ledger-domain-engineer", "tool": "money.transfer", "resource": null}
	agents.decision.reason == "hard-denied tool tier" with data.agents as charters
		with input as {"agent": "ledger-domain-engineer", "tool": "money.transfer", "resource": null}
}

# Segregation of duties: even the owning dev agent cannot merge/approve its own PRs.
test_hard_denied_pr_merge if {
	not agents.allow with data.agents as charters
		with input as {"agent": "ledger-domain-engineer", "tool": "gh.pr.merge", "resource": null}
	agents.decision.reason == "hard-denied tool tier" with data.agents as charters
		with input as {"agent": "ledger-domain-engineer", "tool": "gh.pr.merge", "resource": null}
}

# Charter-level deny via glob ("*.write") — not in the hard tier, blocked by the charter.
test_charter_denied_write_glob if {
	not agents.allow with data.agents as charters
		with input as {"agent": "compliance-officer", "tool": "account.write", "resource": null}
	agents.decision.reason == "denied by charter" with data.agents as charters
		with input as {"agent": "compliance-officer", "tool": "account.write", "resource": null}
}

# Control plane is proposal-only: opening PRs is denied by its "gh.pr.*" charter glob.
test_charter_denied_pr_glob_for_control_agent if {
	not agents.allow with data.agents as charters
		with input as {"agent": "compliance-officer", "tool": "gh.pr.open", "resource": null}
	agents.decision.reason == "denied by charter" with data.agents as charters
		with input as {"agent": "compliance-officer", "tool": "gh.pr.open", "resource": null}
}

# run.skill is allowed only when the requested skill is on the agent's skills allowlist.
test_run_skill_allowed_when_in_allowlist if {
	agents.allow with data.agents as charters
		with input as {"agent": "ledger-domain-engineer", "tool": "run.skill", "resource": null, "attributes": {"skill": "ship-check"}}
}

test_run_skill_denied_when_skill_not_listed if {
	not agents.allow with data.agents as charters
		with input as {"agent": "ledger-domain-engineer", "tool": "run.skill", "resource": null, "attributes": {"skill": "deploy-prod"}}
	agents.decision.reason == "skill not in agent allowlist" with data.agents as charters
		with input as {"agent": "ledger-domain-engineer", "tool": "run.skill", "resource": null, "attributes": {"skill": "deploy-prod"}}
}

test_run_skill_denied_when_no_skill_attribute if {
	not agents.allow with data.agents as charters
		with input as {"agent": "ledger-domain-engineer", "tool": "run.skill", "resource": null}
}

# ---------------------------------------------------------------------------------------
# rca-investigator (control plane, HolmesGPT read-only RCA). Allowed only its read/draft
# charter tools; deny-by-default holds for every write and every money tool.
# ---------------------------------------------------------------------------------------

# A charter read tool: observability telemetry for root-cause analysis.
test_allow_rca_observability_readonly if {
	agents.allow with data.agents as charters
		with input as {"agent": "rca-investigator", "tool": "query.observability.readonly", "resource": "alert-1"}
}

# Read-only oversight: any write tool is blocked by the charter's "*.write" glob, not the hard tier.
test_deny_rca_write_glob if {
	not agents.allow with data.agents as charters
		with input as {"agent": "rca-investigator", "tool": "ledger.write", "resource": null}
	agents.decision.reason == "denied by charter" with data.agents as charters
		with input as {"agent": "rca-investigator", "tool": "ledger.write", "resource": null}
}

# A hard-tier money tool is forbidden regardless of charter.
test_deny_rca_money_transfer_hard_tier if {
	not agents.allow with data.agents as charters
		with input as {"agent": "rca-investigator", "tool": "money.transfer", "resource": null}
	agents.decision.reason == "hard-denied tool tier" with data.agents as charters
		with input as {"agent": "rca-investigator", "tool": "money.transfer", "resource": null}
}

# A non-hard-tier money tool is still blocked by the charter's "money.*" glob.
test_deny_rca_money_glob if {
	not agents.allow with data.agents as charters
		with input as {"agent": "rca-investigator", "tool": "money.refund", "resource": null}
	agents.decision.reason == "denied by charter" with data.agents as charters
		with input as {"agent": "rca-investigator", "tool": "money.refund", "resource": null}
}

# Opening PRs is denied by the "gh.pr.*" charter glob (oversight emits findings, never acts).
test_deny_rca_pr_glob if {
	not agents.allow with data.agents as charters
		with input as {"agent": "rca-investigator", "tool": "gh.pr.open", "resource": null}
	agents.decision.reason == "denied by charter" with data.agents as charters
		with input as {"agent": "rca-investigator", "tool": "gh.pr.open", "resource": null}
}

# A read tool outside its allowlist still denies by default.
test_deny_rca_tool_not_in_allowlist if {
	not agents.allow with data.agents as charters
		with input as {"agent": "rca-investigator", "tool": "query.ledger.readonly", "resource": null}
	agents.decision.reason == "no matching allow rule" with data.agents as charters
		with input as {"agent": "rca-investigator", "tool": "query.ledger.readonly", "resource": null}
}

# ---------------------------------------------------------------------------------------
# REST-action bridge (rest_domains / rest_action_allowed): rest.rego delegates an AI_AGENT
# REST call by setting input.tool := the raw REST action string (e.g. "ledger.list"), which
# lives in a different vocabulary than a charter's tools.allow (e.g. "query.ledger.readonly").
# These prove the bridge grants exactly the intended REST reads and nothing more.
# ---------------------------------------------------------------------------------------

# compliance-officer's query.ledger.readonly grant bridges to a same-domain REST read action.
test_allow_rest_action_via_readonly_tool_ledger_list if {
	agents.allow with data.agents as charters
		with input as {"agent": "compliance-officer", "tool": "ledger.list", "resource": null}
}

test_allow_rest_action_via_readonly_tool_ledger_read if {
	agents.allow with data.agents as charters
		with input as {"agent": "compliance-officer", "tool": "ledger.read", "resource": null}
}

# The bridge is read-only: a write verb in the same domain is NOT granted by the readonly
# tool, even though "ledger" is in its mapped domain set.
test_deny_rest_action_write_verb_not_bridged if {
	not agents.allow with data.agents as charters
		with input as {"agent": "compliance-officer", "tool": "ledger.create", "resource": null}
	agents.decision.reason == "no matching allow rule" with data.agents as charters
		with input as {"agent": "compliance-officer", "tool": "ledger.create", "resource": null}
}

# A domain outside the tool's mapped set stays denied (rca-investigator only holds
# query.observability.readonly -- no bridge to ledger.*).
test_deny_rest_action_domain_not_in_tool_map if {
	not agents.allow with data.agents as charters
		with input as {"agent": "rca-investigator", "tool": "ledger.list", "resource": null}
}

# rest.rego sources input.agent from the JWT `sub` via principal.id, which for a real
# AI_AGENT is prefixed "agent:" (AuthorizeInterceptor's own convention/test uses
# "agent:onboarding") -- agents.yaml charter ids are bare. The charter lookup strips a
# leading "agent:" so the bridge still resolves the charter with the REAL production id
# shape, not just the bare id agents_test.rego's other cases use.
test_allow_rest_action_with_agent_colon_prefixed_id if {
	agents.allow with data.agents as charters
		with input as {"agent": "agent:compliance-officer", "tool": "ledger.list", "resource": null}
}

# A bare id (no "agent:" prefix) still resolves too -- trim_prefix is a no-op when the
# prefix isn't present, so the MCP path (which already passes bare ids) is unaffected.
test_allow_rest_action_with_bare_id_unaffected if {
	agents.allow with data.agents as charters
		with input as {"agent": "compliance-officer", "tool": "ledger.list", "resource": null}
}

# The fleet-wide hard-denied tier still blocks a tool reachable via the REST-action bridge:
# agents.allow (not charter_allowed alone) must be the thing rest.rego delegates to, since
# only allow applies hard_denied. Regression for that exact class of bug.
test_deny_rest_action_hard_denied_via_bridge if {
	not agents.allow with data.agents as {
		"agents": charters.agents,
		"tool_tiers": {"deny": ["ledger.list"]},
	}
		with input as {"agent": "compliance-officer", "tool": "ledger.list", "resource": null}
	agents.decision.reason == "hard-denied tool tier" with data.agents as {
		"agents": charters.agents,
		"tool_tiers": {"deny": ["ledger.list"]},
	}
		with input as {"agent": "compliance-officer", "tool": "ledger.list", "resource": null}
}

# A charter's own tools.deny glob still blocks a tool reachable via the REST-action bridge.
test_deny_rest_action_charter_denied_via_bridge if {
	denying_charters := {
		"agents": [
			{
				"id": "compliance-officer",
				"plane": "control",
				"tools": {"allow": ["query.ledger.readonly"], "deny": ["ledger.*"]},
			},
		],
		"tool_tiers": {"deny": []},
	}
	not agents.allow with data.agents as denying_charters
		with input as {"agent": "compliance-officer", "tool": "ledger.list", "resource": null}
	agents.decision.reason == "denied by charter" with data.agents as denying_charters
		with input as {"agent": "compliance-officer", "tool": "ledger.list", "resource": null}
}

# ---------------------------------------------------------------------------------------
# customer-copilot (customer plane, ADR-0089). Reads the signed-in customer's own data and
# emits PROPOSALS only; money-path tools are hard-denied and money never moves on its word.
# ---------------------------------------------------------------------------------------

# Charter reads: own balance and own transactions.
test_allow_copilot_balance_readonly if {
	agents.allow with data.agents as charters
		with input as {"agent": "customer-copilot", "tool": "query.balance.readonly", "resource": "self"}
}

test_allow_copilot_transaction_readonly if {
	agents.allow with data.agents as charters
		with input as {"agent": "customer-copilot", "tool": "query.transaction.readonly", "resource": "self"}
}

# An action tool is allowed only as a PROPOSAL artifact (execution is HITL + SCA in the edge flow).
test_allow_copilot_propose_card_freeze if {
	agents.allow with data.agents as charters
		with input as {"agent": "customer-copilot", "tool": "propose.card_freeze", "resource": "card-1"}
}

# Hard tier: the customer assistant can never move money directly.
test_deny_copilot_money_transfer_hard_tier if {
	not agents.allow with data.agents as charters
		with input as {"agent": "customer-copilot", "tool": "money.transfer", "resource": null}
	agents.decision.reason == "hard-denied tool tier" with data.agents as charters
		with input as {"agent": "customer-copilot", "tool": "money.transfer", "resource": null}
}

test_deny_copilot_money_post_ledger_hard_tier if {
	not agents.allow with data.agents as charters
		with input as {"agent": "customer-copilot", "tool": "money.post.ledger", "resource": null}
	agents.decision.reason == "hard-denied tool tier" with data.agents as charters
		with input as {"agent": "customer-copilot", "tool": "money.post.ledger", "resource": null}
}

# Segregation of duties: the customer plane has no business merging PRs (hard tier).
test_deny_copilot_pr_merge_hard_tier if {
	not agents.allow with data.agents as charters
		with input as {"agent": "customer-copilot", "tool": "gh.pr.merge", "resource": null}
	agents.decision.reason == "hard-denied tool tier" with data.agents as charters
		with input as {"agent": "customer-copilot", "tool": "gh.pr.merge", "resource": null}
}

# A non-hard-tier money tool is blocked by the charter's "money.*" glob.
test_deny_copilot_money_glob if {
	not agents.allow with data.agents as charters
		with input as {"agent": "customer-copilot", "tool": "money.send", "resource": null}
	agents.decision.reason == "denied by charter" with data.agents as charters
		with input as {"agent": "customer-copilot", "tool": "money.send", "resource": null}
}

# customer-copilot's charter has NO "*.write" deny, so an out-of-scope write is caught purely by
# deny-by-default (no matching allow rule) — proving the default, not an explicit charter deny.
test_deny_copilot_out_of_scope_write_by_default if {
	not agents.allow with data.agents as charters
		with input as {"agent": "customer-copilot", "tool": "account.write", "resource": null}
	agents.decision.reason == "no matching allow rule" with data.agents as charters
		with input as {"agent": "customer-copilot", "tool": "account.write", "resource": null}
}
