#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# Assemble + verify the OPA policy bundle that gates the MCP /tools/call endpoint
# (ADR-0031 D2/D8). This is the "bundle wiring" step: the agent charters in
# openbank-libs/governance/agents.yaml are the single source of truth; here they are
# staged under the `agents` data namespace (so the policy sees data.agents.agents and
# data.agents.tool_tiers, the shape agents.rego expects) and built into a loadable bundle.
#
# Run locally before shipping a charter or policy change:
#   ./openbank-infra/opa/build-bundle.sh
# Steps: stage -> opa check --strict -> opa test (unit) -> decision assertions against the
# REAL agents.yaml (drift guard: the policy and the committed charter must agree) -> opa build.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

# The DERIVED subset, not agents.yaml itself: this is what every deployed bundle mounts at
# /bundle/agents/data.yaml (gen-agents-opa-data.py, #3927). Staging the full document here
# would validate a shape the cluster never runs — the probe must read the artifact the
# service uses, never a second copy of its source.
AGENTS_YAML="$REPO_ROOT/openbank-libs/governance/agents-opa-data.yaml"
RULES_YAML="$REPO_ROOT/openbank-libs/governance/rules.yaml"
POLICIES_DIR="$SCRIPT_DIR/policies"                                  # agents.rego (MCP gate, ADR-0031)
LIBS_POLICIES_DIR="$REPO_ROOT/openbank-libs/governance/policies"    # rest.rego (REST PEP, ADR-0034)
BUNDLE_MANIFEST="$SCRIPT_DIR/bundle.manifest"   # bundle roots; shared with the docker-compose opa sidecar
DIST_DIR="$SCRIPT_DIR/dist"
BUNDLE_TARBALL="$DIST_DIR/openbank-agents-bundle.tar.gz"

command -v opa >/dev/null || { echo "ERROR: opa not on PATH (https://www.openpolicyagent.org/docs/latest/#running-opa)"; exit 1; }
[ -f "$AGENTS_YAML" ] || { echo "ERROR: derived charter data not found: $AGENTS_YAML (run .github/scripts/gen-agents-opa-data.py)"; exit 1; }
[ -f "$RULES_YAML" ] || { echo "ERROR: rules source not found: $RULES_YAML"; exit 1; }
[ -f "$BUNDLE_MANIFEST" ] || { echo "ERROR: bundle manifest not found: $BUNDLE_MANIFEST"; exit 1; }

STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

echo "==> opa test (unit, mocked data)"
opa test "$POLICIES_DIR" "$LIBS_POLICIES_DIR"

# Charters land at data.agents.* (agents.yaml top-level keys become data.agents.{agents,tool_tiers,...}).
# rules.yaml lands at data.rules.* (money_path_services, four_eyes, feature_flags) — the REST PEP
# (rest.rego, ADR-0034) reads these; without this staging data.rules is undefined and four_eyes /
# prohibited-flip rules silently never fire in production. Test files are excluded from the bundle.
mkdir -p "$STAGE/agents" "$STAGE/rules"
cp "$AGENTS_YAML" "$STAGE/agents/data.yaml"
cp "$RULES_YAML" "$STAGE/rules/data.yaml"
for dir in "$POLICIES_DIR" "$LIBS_POLICIES_DIR"; do
	for f in "$dir"/*.rego; do
		# case_collaboration.rego belongs only to the case-coordinator bundle (ADR-0271 D8).
		# Including it here would couple every shared MCP/REST policy consumer to its root and
		# silently turn a case-policy edit into a fleet rollout.
		case "$f" in *_test.rego|*/case_collaboration.rego) continue;; esac
		cp "$f" "$STAGE/"
	done
done
cp "$BUNDLE_MANIFEST" "$STAGE/.manifest"

echo "==> opa check --strict (bundle)"
opa check --strict -b "$STAGE"

echo "==> decision assertions against the derived charter data (agents-opa-data.yaml)"
fail=0
assert() { # desc | input-json | expected (true|false)
	local desc="$1" input="$2" expect="$3" got
	got="$(opa eval -b "$STAGE" -I 'data.openbank.agents.allow' --format=raw <<<"$input" 2>/dev/null || true)"
	if [ "$got" = "$expect" ]; then
		echo "  ok   $desc (allow=$got)"
	else
		echo "  FAIL $desc: expected allow=$expect, got allow=${got:-<undefined>}"
		fail=1
	fi
}

assert "control agent may read its chartered tool" \
	'{"agent":"compliance-officer","tool":"query.ledger.readonly","resource":"acct-1"}' true
assert "control agent is proposal-only (no PR open)" \
	'{"agent":"compliance-officer","tool":"gh.pr.open","resource":null}' false
assert "dev agent may open a PR" \
	'{"agent":"ledger-domain-engineer","tool":"gh.pr.open","resource":null}' true
assert "no agent may merge/approve (segregation of duties)" \
	'{"agent":"ledger-domain-engineer","tool":"gh.pr.merge","resource":null}' false
assert "run.skill allowed when skill is chartered" \
	'{"agent":"ledger-domain-engineer","tool":"run.skill","resource":null,"attributes":{"skill":"ship-check"}}' true
assert "run.skill denied when skill not chartered" \
	'{"agent":"ledger-domain-engineer","tool":"run.skill","resource":null,"attributes":{"skill":"deploy-prod"}}' false
assert "unknown agent denied by default" \
	'{"agent":"ghost","tool":"read.catalog","resource":null}' false

# REST PEP decision assertions against the REAL rules.yaml (ADR-0034 / issue #419). These would
# all silently pass-as-undefined if rules.yaml were not staged into data.rules — the drift guard
# that catches the latent "four_eyes never fires in prod" bug.
rassert() { # desc | rule | input-json | expected (true|false)
	local desc="$1" rule="$2" input="$3" expect="$4" got
	got="$(opa eval -b "$STAGE" -I "data.openbank.rest.$rule" --format=raw <<<"$input" 2>/dev/null || true)"
	[ -z "$got" ] && got=false
	if [ "$got" = "$expect" ]; then
		echo "  ok   $desc ($rule=$got)"
	else
		echo "  FAIL $desc: expected $rule=$expect, got $rule=$got"
		fail=1
	fi
}

rassert "four-eyes fires for a money-path post (real long service names normalised)" \
	four_eyes_required '{"action":"ledger.post"}' true
# sepaPayment.transitionStatus is the REAL @Authorize action in SepaPaymentResource.kt —
# "sepa-payment.transfer" (fixed here, issue #395) was a synthetic string no service ever
# emits, so it silently proved nothing about whether money_path_action_prefixes actually
# covers the fleet's real, shipped action name.
rassert "four-eyes fires for a real sepa-payment status transition" \
	four_eyes_required '{"action":"sepaPayment.transitionStatus"}' true
rassert "four-eyes not required for a non-money-path update" \
	four_eyes_required '{"action":"party.update"}' false
rassert "four-eyes fires for a money-path feature-flag flip" \
	four_eyes_required '{"action":"featureflag.flip","attributes":{"flag":"instant-payments-enabled"}}' true
rassert "disabling SCA via a flag flip is prohibited outright" \
	prohibited '{"action":"featureflag.flip","attributes":{"flag":"sca-enforcement-disabled"}}' true
rassert "a benign flag flip is not prohibited" \
	prohibited '{"action":"featureflag.flip","attributes":{"flag":"instant-payments-enabled"}}' false

# ADR-0195 step 5 (#3292): the REST→agents bridge must forward `attributes` into agents.allow.
# Behaviourally observable via skill_ok's else-branch (input.attributes.skill) through
# data.openbank.rest.allow with the REAL agents.yaml charters: if the bridge ever drops the
# attributes map again, the first assertion goes undefined→false and this guard fails.
rassert "REST bridge forwards attributes: chartered run.skill granted via rest.allow" \
	allow.allow '{"principal":{"id":"agent:ledger-domain-engineer","type":"AI_AGENT","roles":[]},"action":"run.skill","resource":null,"attributes":{"skill":"ship-check"}}' true
rassert "REST bridge forwards attributes: unchartered skill denied via rest.allow" \
	allow.allow '{"principal":{"id":"agent:ledger-domain-engineer","type":"AI_AGENT","roles":[]},"action":"run.skill","resource":null,"attributes":{"skill":"deploy-prod"}}' false

[ "$fail" -eq 0 ] || { echo "==> drift detected: policy and the committed charter/rules disagree"; exit 1; }

echo "==> opa build -> $BUNDLE_TARBALL"
mkdir -p "$DIST_DIR"
opa build -b "$STAGE" -o "$BUNDLE_TARBALL"

echo "==> done: $(du -h "$BUNDLE_TARBALL" | cut -f1) bundle, $(opa eval -b "$STAGE" 'count(data.agents.agents)' --format=raw) charters"
