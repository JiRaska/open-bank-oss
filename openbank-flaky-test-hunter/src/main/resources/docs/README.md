# Flaky Test Hunter API

Internal development-plane agent API.

## Interface

The committed `src/main/resources/openapi.yaml` defines the API contract. Read `/q/openapi` on the running service for the exact paths, request bodies, responses, and contract version of its image.

## GitHub proposal egress

The proposal adapter uses `SafeHttpClient` for its configurable GitHub API URL. `openbank.egress.allowed-hosts` defaults to `api.github.com`; explicitly allow the hostname when using a different GitHub API. DNS answers are checked before a connection to a pinned public address; TLS still verifies the original hostname, and redirects are refused. An unlisted host produces no outbound request.

## PR-path policy gate (ADR-0031 phase 3)

Before any GitHub write — branch creation included — every PR-opening disposition passes `PrPathPolicyGate` in `DiagnoseAndProposeActivityImpl`. Two deny-by-default layers:

- `AgentCharterGrantVerifier` — in-process re-check of the packaged `agents.yaml` charter (no network dependency; the local fail-safe). The tool→tier binding (`gh.pr.open` on `github-pr`, charter tier `write_proposal`) lives in code; OPA re-validates the `(tier, resources)` grant against the served charter data rather than trusting the caller's tool name.
- `OpaAgentDecisionClient` — the OPA sidecar decision at `data.openbank.agents.decision`, extended for phase 3 with object-shaped `{tier, resources}` charter rules so a background agent's write path is decidable there. Callers without `input.tier` are unchanged.

The gate **fails closed**. A charter denial (`charter_unavailable`, `charter_denied`) or any OPA connectivity/HTTP/parse failure is a DENY with reason `pdp_unreachable`; there is deliberately no advisory degradation on this path. This deviates from the agent-service MCP gate on purpose: that gate fronts read-only tools, where fail-closed would take the assistant offline over a sidecar blip and the worst case of a wrong ALLOW is a wrong answer. This gate fronts a **write** that opens a real PR under the agent's identity — an allow granted while the PDP is down, slow, or spoofed is an irreversible code change. A denial vetoes the entire PR path; the finding still reaches the human ticket path.

Config (`flaky-test-hunter.policy-gate`):

| Key | Default | Purpose |
|---|---|---|
| `enabled` | `true` | PR writer kill switch: false denies writes with `policy_gate_disabled` before charter or OPA calls; true requires both policy layers |
| `opa-url` | `http://localhost:8181` | OPA sidecar base URL |
| `timeout` | `PT0.5S` | per-call connect + request timeout; exceeding it fails closed (`pdp_unreachable`) |

The mechanism is implemented while the governance rollout remains in phase 2. GitOps explicitly
sets `FLAKY_TEST_HUNTER_POLICY_GATE_ENABLED=false`: the gate denies branch, commit and PR creation
with `policy_gate_disabled`; it never permits a policy bypass. Temporal finding workflows remain
enabled. Controlled writer activation is separate rollout work. Phase 3 requires the first real
agent PR's human merge verified in both the merge-record row and audit, plus deployment attestation
for agent-service and flaky-test-hunter cross-checked against their deployed-version records.
Implementation and local tests do not supply those runtime proofs.

Audit: one AI-attributed event per disposition (`actorType=AI_AGENT`) — `agent.github.pr_open` for every attempted PR path, including denials, and `flaky-test-hunter.github.ticket_opened` when no fix enters the PR path. The event carries `policy_decision` (ALLOW only when the gate allowed **and** the PR was actually opened), `policy_blocked`, `policy_denial_reason`, the evaluated `tool`/`resource`/`pr_branch`, `pr_url` for an opened PR, and `proposal_url` for the selected PR or ticket. A refusal is as auditable as an opened PR; a blocked mechanical fix (`policy_blocked: true`) is distinguishable from a finding that had no fix at all.

## Input and dispositions

The deployed agent's input is the Admin UI Test Intelligence snapshot, posted after every collection to `POST /api/v1/flaky-test-hunter/evidence/analyze`. The static source scan reads a repository checkout under `openbank.flaky-test-hunter.repo-root`, and the deployed pod mounts none. The weekly scheduled sweep was therefore removed on 2026-10-17, because it scanned an empty tree. The scan remains reachable through `POST /check/trigger` and the CI-failure webhook, and `RunTrigger` has no `SCHEDULED` value.

A finding with no mechanical fix opens a GitHub issue labelled `flaky-test` with the finding and diagnosis. An open issue with the same title is reused, so a re-run does not flood the tracker. The writer uses the same token as the PR path and fails closed without `FLAKY_TEST_HUNTER_GITHUB_TOKEN`. Ticket and diagnosis text from an issue is treated as untrusted input.

## Build identity

The generated `00-build` page in this same documentation index reports the release version, full source commit, and API contract version packaged in the running image. The index is available at `/q/openbank/docs`.
