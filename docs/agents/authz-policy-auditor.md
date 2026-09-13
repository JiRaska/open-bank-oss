---
id: authz-policy-auditor
plane: control
adr: ADR-0167
---

# authz-policy-auditor

## Mission

Static analyzer for the fleet's OPA/Rego authorization policies (`rest.rego`, `agents.rego`,
`copilot_tool.rego`) and the `agents.yaml` charters that feed them. On a weekly sweep plus
reactively whenever any `.rego` policy or `agents.yaml` changes, this agent cross-references every
rule condition against `AuthorizeInterceptor`'s actual emitted principal-type/id vocabulary: a rule
gated on a classification value the interceptor never emits, an agent-id comparison missing the
`trim_prefix` normalization the REST bridge path needs, an `agents.yaml` charter's
`tools.allow`/`tools.deny` drifting from the `tool_tiers` registry, and a REST rule bypassing
`agents.allow` by calling `charter_allowed` directly. Every finding becomes a tracking ticket
through the HITL queue — this agent never opens a fix PR, even for the one case (a dead `deny`
glob) that would otherwise be its "narrow mechanical case," because a wrong auto-fix on an
authorization rule is a live security exposure. It never writes to a `.rego` policy or
`agents.yaml` directly.

## Why this agent exists

This repo has hit the underlying defect class twice for real, both found manually, both already
live in shipped code before being caught. `rest.rego`'s `edge-service-notification` rule was gated
on `principal.type == "SERVICE"` — a classification `AuthorizeInterceptor` can never actually
produce — so it silently denied its intended M2M caller the moment enforcement flipped on (issue
#266). Separately, an `AI_AGENT` principal's id carries an `agent:` prefix on the REST path but not
on the raw MCP `/tools/call` path, so a charter lookup comparing the two directly without
normalizing the prefix silently never matched on the REST bridge (fixed in PR #402). A narrow CI
guard now exists for the first incident's exact literal string, but nothing generalizes it to the
underlying pattern — a rule's condition silently diverging from the runtime vocabulary the code
that feeds it actually produces — or re-verifies the second fix keeps holding. This agent is the
periodic, static re-verification layer for exactly that relationship, plus a fourth check (charter
vs. `tool_tiers` drift) proactively covering a related, not-yet-incident-causing gap.

## Human oversight

- `any_authz_policy_finding` — every finding needs a human who reads both the rego/charter diff and
  the code path it gates; the agent cannot make that call itself.
- `every: proposal` — the agent never merges a PR, edits a `.rego` policy, or writes to
  `agents.yaml`; segregation of duties matches every other control-plane agent.
- `two_person_review: authz_policy_findings` — stricter than every sibling agent's single-approval
  HITL gate (conceptually mirrors `rules.yaml: review.money_path_approvals: 2`): a wrong
  disposition of an authorization-policy finding is a live security exposure, so this charter asks
  for a second reviewer before a finding is closed out.
- `tokens_per_run: 50000` — capped so the agent's own running cost stays a rounding error next to
  the cost of a silently-unreachable authorization rule reaching production.

## Known gaps

- The `PolicyScanPort` adapter is a genuine (not stubbed) grep/text-scan implementation, but
  deliberately shallow — line-local pattern matching, not a Rego AST parse. A rule built through a
  helper function, or a comparison split across multiple lines, is invisible to it; a `draft.ticket`
  gives a human the citation, not a verdict.
- Check 3 (charter/`tool_tiers` drift) is scoped to charters using the flat glob-list
  `tools.allow`/`tools.deny` shape. The tier/`resources:` object shape every control-plane agent
  charter — including this one — uses references a different, adjacent vocabulary and is out of
  scope; unifying the two vocabularies is a larger `agents.yaml` schema change not attempted here.
- Check 3's allow-token heuristic only flags a token whose namespace prefix is already a known
  `tool_tiers` namespace but whose full value matches nothing registered — a deliberate choice to
  avoid false-flagging legitimate charter-local extension tokens in an unrelated namespace (e.g. a
  customer-facing action-proposal verb), at the cost of missing a cross-word typo within a token's
  own namespace segment.
- Check 5 (a charter's declared `tools.allow` vs. what a service's `application.yaml` actually
  grants at runtime, issue #743) is not implemented — it needs a live-cluster or
  fleet-wide-repo-scan correlation this agent's v1 does not perform. Tracked as a genuine gap, not
  silently pretended-covered.
- The scan is scoped to the two canonical `.rego` source directories
  (`openbank-infra/opa/policies/`, `openbank-libs/governance/policies/`), not the ~30 generated
  per-service `*-opa-bundle.yaml` copies `gen-*-opa-bundle.sh` mechanically derives from them — a
  regression at the source is the primary defense; the generated copies are supposed to be
  mechanically regenerated, not independently authored.
- The `LlmDiagnosisPort` adapter is a stub pending the shared LiteLLM gateway. `proposeFixDiff`
  returns `null` unconditionally BY DESIGN here (not just pending integration) — this agent never
  proposes a fix diff for a security-policy defect.
- **`GitHubProposalPort` is unwired and REFUSES — no finding of this agent reaches GitHub today**
  (#5897). Both methods return `null`, and `DiagnoseAndProposeActivityImpl` then leaves the finding
  `DIAGNOSED` with a null `proposalUrl`: it is never counted in a run's `findingsProposed` and never
  presented as awaiting a human. It previously returned a fabricated
  `https://github.com/openbank/openbank/issues/pending-authz-policy-auditor-<id>` URL and moved the
  finding to `PROPOSED` — which on this agent meant an authorization-policy defect nobody had filed
  being reported as filed and awaiting the `two_person_review` its charter requires. `openProposalPr`
  refuses **permanently** (ADR-0167: never a fix PR on an authorization policy); `openTicket`
  refuses because no `github-token` config exists in this service. Follows
  `openbank-mcp-service`'s `UnwiredProposalPort` (#3900).
- `repo-root` (`AUTHZ_POLICY_AUDITOR_REPO_ROOT`) must point at a mounted, up-to-date checkout of
  `main` for the `PolicyScanPort` checks to be meaningful — the deployment-side checkout-mount
  wiring (a sidecar or init-container `git pull`) is tracked separately and not yet part of this
  PR's gitops manifest.
