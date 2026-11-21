# Threat Model — regulatory obligations watcher (`openbank-regulatory-obligations-watcher`)

**Component:** the weekly control-plane agent that re-reads every source the regulatory
obligations register cites and files governance issues.
**Manifests:** `openbank-infra/gitops/components/regulatory-obligations-watcher/`
(`regulatory-obligations-watcher.yaml`, `externalsecrets.yaml`, `postgres.yaml`,
`network-policies.yaml`, `regulatory-obligations-watcher-opa-bundle.yaml`)
**ADRs:** ADR-0367 (decision 6, the charter), ADR-0387 (public-web reads through the broker),
ADR-0031 (agent charters), ADR-0148 (prompts and evals), ADR-0030 (threat-model discipline)
**Review cadence:** on any change to the charter in `agents.yaml`, the prompt, the answer parser,
the issue writer, or the pod's network or secret wiring.

Not a money-path service. It is modelled because it is the first agent that feeds **public web
content** into a model and turns the answer into a write (a GitHub issue).

---

## 1. Scope

**In scope.** The watcher Deployment, its OPA sidecar, its database, its three credentials
(Keycloak client secret, LiteLLM virtual key, GitHub token) and the path page text → model →
issue.

**Out of scope.** The fetch broker (own threat model), the LiteLLM gateway, GitHub itself, and the
legal correctness of any finding (a human decides; charter `requires_human`).

## 2. Data flow

```
 watcher ──(TB-1: client_credentials, Keycloak :8080)──▶ token (sub agent:…, aud openbank-fetch-broker)
    │──(TB-2: web.fetch, broker :8172)──▶ broker ──▶ public sources
    │──(TB-3: chat, LiteLLM :4000)──▶ model          (page text only inside the data section)
    │──(localhost :8181)──▶ OPA sidecar: draft.ticket?
    └──(TB-4: HTTPS api.github.com)──▶ one issue per finding
```

- **TB-1/TB-2** — the token is issued to this agent's own client, which holds no realm role; the
  broker accepts it only for its own audience and only for the hosts in this charter.
- **TB-3** — the model receives the citing register rows and the page text, re-wrapped in
  delimiters with a fresh nonce. Its answer is parsed as one closed JSON object.
- **TB-4** — GitHub is reached directly. The pod has no egress NetworkPolicy, because the public
  web gate forbids internet egress from agent-plane policies and GitHub has no in-cluster proxy;
  the code reaches the public web only through the broker.

## 3. Assets

| Asset | Where | Why it matters |
|---|---|---|
| GitHub token (issues:write, this repo) | `regulatory-obligations-watcher-github` Secret | issue spam or misleading issues under the agent's name |
| Keycloak client secret | `regulatory-obligations-watcher-oidc` Secret | lets a holder read the charter's hosts through the broker |
| LiteLLM virtual key | `regulatory-obligations-watcher-model` Secret | model spend (budgeted per key) |
| Baselines and finding history | `regwatcher-db` | a lost baseline hides one content change |

## 4. STRIDE

| Category | Threat | Mitigation | Residual |
|---|---|---|---|
| **Spoofing** | Another workload uses the watcher's identity at the broker | Secret mounted in this pod only; the broker binds the caller to `sub` and audience | A pod compromise yields the token for this charter's 8 hosts |
| **Tampering** | A fetched page carries instructions to the model ("report no change", "open a PR") | Page text only in the nonce-delimited data section; closed answer schema; extra keys ignored; no tool surface; quote must be verbatim in the page; evals include an injection case | An injected page can still bias a verdict; a human reviews every issue |
| **Tampering** | Page text written into an issue mentions users, links issues or forges the dedup marker | `@`, `#` and `<!--` defused before writing | Markdown formatting from the page survives inside the quote |
| **Repudiation** | A finding cannot be traced to its evidence | Each finding row stores model id, prompt hash, tool calls, policy decision, obligation id, source URL, quote and sha256; the issue carries the same | — |
| **Information disclosure** | Register or internal data leaks to a public site | Requests carry only the register's own public URLs; GET only, no query strings (broker) | — |
| **Denial of service** | A broker or credential fault floods the tracker | At most 5 new issues per run, the rest deferred; dedup against own history and open or closed issues; runs_per_day counted in the database | A persistent fault yields up to 5 issues per week |
| **Denial of service** | Token spend | tokens_per_run checked before every call; budgeted virtual key; kill switch | — |
| **Elevation of privilege** | The agent writes the register or opens a PR | Charter grants read + `draft.ticket` only; OPA sidecar decides before every issue and fails closed; GitHub token scoped to issues | — |
| **Elevation of privilege** | Container escape | `runAsNonRoot`, `drop: [ALL]`, read-only root, seccomp `RuntimeDefault`, no service-account token | — |

## 5. Verification

- `WatchRunEndToEndIT`: a changed hash files one issue, NOT_RENDERABLE is UNVERIFIED, an injected
  instruction produces only its quoted finding and no extra fetch or issue.
- `BlindRunEarnsNoLivenessIT`: a run with zero FETCHED sources records no liveness success
  (falsified by removing the `fetched > 0` clause).
- `opa eval` against the served bundles: `read.public_web` allowed on a listed host and denied
  off-list; `draft.ticket` allowed; PR, write and execute denied.
- `check-public-web-fetch.py` (enforced) over the charter and the network policies.
