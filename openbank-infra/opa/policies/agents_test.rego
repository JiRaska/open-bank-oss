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
				"allow": ["query.ledger.readonly", "query.catalog.readonly", "read.logs", "read.governance", "draft.ticket"],
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
