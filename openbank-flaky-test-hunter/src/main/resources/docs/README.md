# Flaky Test Hunter API

Internal development-plane agent API.

## Interface

The committed `src/main/resources/openapi.yaml` defines the API contract. Read `/q/openapi` on the running service for the exact paths, request bodies, responses, and contract version of its image.

## Observe an admitted operator workflow

`GET /api/v1/flaky-test-hunter/check/workflows/{workflowId}` is a read-only endpoint for `ROLE_ADMIN`. Supply the exact identifier returned by operator admission. The service accepts only its operator workflow prefix and a valid bounded UTC date identifier; it does not expose arbitrary Temporal workflows or their payloads.

The response contains only `workflowId` and the observed execution `status`. The lookup uses the configured Temporal namespace and a three-second describe deadline. An unknown workflow returns 404; a failed status lookup remains an error, rather than being reported as a completed or absent execution.

An admitted request and a successful workflow execution are separate observations. Execution completion also does not establish approval or application of a proposed change. Clients should timestamp the last observed status and explicitly expose a paused or failed refresh.

## Temporal proposal context

Proposal activities keep coroutine continuations on the safe duplicated Vert.x context supplied by Quarkus. An IO-dispatched GitHub call may suspend; its continuation must return to that same context before opening another Hibernate Reactive session or transaction. `Dispatchers.Unconfined` cannot guarantee this return. The activity-context integration test exercises the production bridge and verifies the committed database disposition after an IO suspension.

## GitHub proposal egress

The proposal adapter uses `SafeHttpClient` for its configurable GitHub API URL. `openbank.egress.allowed-hosts` defaults to `api.github.com`; explicitly allow the hostname when using a different GitHub API. DNS answers are checked before a connection to a pinned public address; TLS still verifies the original hostname, and redirects are refused. An unlisted host produces no outbound request.

Every request identifies the application with `User-Agent: openbank-flaky-test-hunter`. GitHub requires this header for reads as well as writes; a valid credential alone does not prevent a missing-header rejection.

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

Writer activation and the governance roadmap are separate observations. GitOps declares a bounded
pilot with `FLAKY_TEST_HUNTER_POLICY_GATE_ENABLED=true` and a read-only own-test snapshot; verify the
effective switch and source revision on the running deployment. Setting the switch to false denies
branch, commit and PR creation with `policy_gate_disabled`; it never permits a policy bypass.
The authoritative roadmap is `openbank-libs/governance/ai-rollout.yaml`. Advancing it requires a
completed runtime workflow, a real agent PR's human merge verified in both the merge-record row and
audit, and deployment attestation for both AI services cross-checked against deployed-version records.
Enabling the switch or passing local tests alone does not supply those runtime proofs.

Audit: one AI-attributed event per disposition (`actorType=AI_AGENT`) — `agent.github.pr_open` for every attempted PR path, including denials, and `flaky-test-hunter.github.ticket_opened` when no fix enters the PR path. The event carries `policy_decision` (ALLOW only when the gate allowed **and** the PR was actually opened), `policy_blocked`, `policy_denial_reason`, the evaluated `tool`/`resource`/`pr_branch`, `pr_url` for an opened PR, and `proposal_url` for the selected PR or ticket. A refusal is as auditable as an opened PR; a blocked mechanical fix (`policy_blocked: true`) is distinguishable from a finding that had no fix at all.

## Input and dispositions

Fleet execution evidence comes from Admin UI Test Intelligence snapshots at `POST /api/v1/flaky-test-hunter/evidence/analyze`. The operator-triggered source pilot reads only this module's tests from a read-only sparse snapshot of public main, taken at pod startup with its exact revision logged. It never builds or executes that source. No JUnit execution reports are mounted; the scanner keeps a missing execution count unknown. A deployment refreshes the snapshot. Source scans remain reachable through the operator trigger and CI-failure webhook; no periodic fleet sweep is enabled.

A finding with no mechanical fix opens a GitHub issue labelled `flaky-test` with the finding and diagnosis. An open issue with the same title is reused, so a re-run does not flood the tracker. The writer uses the same token as the PR path and fails closed without `FLAKY_TEST_HUNTER_GITHUB_TOKEN`. Ticket and diagnosis text from an issue is treated as untrusted input.

## Build identity

The generated `00-build` page in this same documentation index reports the release version, full source commit, and API contract version packaged in the running image. The index is available at `/q/openbank/docs`.

## Shared configuration defaults

This service's `application.yaml` does not declare the HTTP security response headers (`quarkus.http.header.*`) or the management interface (`quarkus.management.*`: enabled, port 8085, host 0.0.0.0, root path `/q`). These values come from the fleet defaults in `openbank-libs-runtime/src/main/resources/application.yaml`, which is on every service classpath at a lower precedence than the service's own file. The values are identical to what this service used to declare itself. To change one for this service only, set it in this service's `application.yaml`; the service file takes precedence.

## Shared build dependencies

This service's `build.gradle.kts` does not list the dependencies every Quarkus service shares: the Quarkus BOM, quarkus-kotlin, RESTEasy Reactive with Jackson, SmallRye Health and OpenAPI, OIDC, the Micrometer Prometheus registry and config-yaml, plus the quarkus-junit5, AssertJ and REST Assured test libraries. The `openbank.quarkus-service` convention plugin in `build-logic` declares them. The resolved classpath is unchanged. `build.gradle.kts` lists only what this service adds.
