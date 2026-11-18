---
date: 2026-11-18
decision-status: proposed
delivery-status: planned
authors: [jiri.raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [ai-agents, security, compliance, networking]
summary: "Agents read the public web only via one in-cluster fetch-broker MCP tool: per-charter host allowlist, GET-only, no PII; plain HTTP first, Cloudflare Kitesurf only for JS pages; content is untrusted data."
---

# ADR-0387 — Public-web read tool for governed agents: Kitesurf behind a fetch broker

## Context

ADR-0031 decided that agents are least-privilege workloads declared in `agents.yaml`, gated by OPA
(ADR-0034) and proposal-only where a human must decide. It did not decide how an agent may read
anything **outside** the cluster. Two charters are disabled today for exactly that reason:

- `regulatory-obligations-watcher` (ADR-0367 decision 6) — its charter says it is `enabled: false`
  because "it needs an allow-listed outbound fetch of the cited public sources" (zakonyprolidi.cz,
  e-sbirka.cz, eur-lex.europa.eu, cnb.cz, gsft.cz, fau.gov.cz, financnisprava.gov.cz).
- `card-scheme-bulletin-agent` (ADR-0283) — disabled because no bulletin feed is wired.

Adjacent decisions and why they do not cover this:

- **ADR-0320** P1 decides a `SafeHttpClient` (host allowlist, RFC 1918 / metadata deny after DNS,
  no cross-host redirect) for *service* code. It is the right primitive and this ADR reuses it, but
  it says nothing about agent charters, prompt injection, rendering, or evidence.
- **ADR-0059** decides allow-listed, opt-in *outbound* oversight webhooks — data leaves; here data
  comes in. Same allow-list shape, different threat.
- **ADR-0174** holds the ICT third-party register; a new provider must be entered there.
- **ADR-0175** fixes residency and D3 gates hosted egress on the data class; this ADR keeps every
  outbound request at data class "public URL only", so D3 is satisfied by construction.
- **ADR-0148 / ADR-0265** decide prompt registry, evals and guardrails; fetched web content is a
  new untrusted input class those must treat as such.

Why now: Cloudflare released Kitesurf (Browser Run, beta, free within per-account limits), a
stateless agent browser on Workers using roughly 3–7× less CPU and memory than Chromium, with a
Quick Actions REST endpoint (content/markdown extraction, screenshot, PDF) and a CDP endpoint. It
removes the main reason not to render JS-heavy public pages — running Chromium in-cluster
(~150–400 MB per process, the reason `openbank-document-renderer` avoids a browser engine).

## Decision

We will give agents **one** way to read the public web: a `web.fetch` MCP tool served by a new
in-cluster **fetch broker** on the agent plane. No agent gets internet egress, a browser, a CDP
socket or a provider token.

1. **One tool, one resource.** `web.fetch(url, mode)` with `mode ∈ {text, markdown}` maps to the
   OPA resource `read.public_web` at tier `read`. A charter opts in under `tools.allow` and declares
   its hosts in a new field `data_scope.public_web.hosts` (exact HTTPS hostnames, no wildcards, no
   IP literals). OPA decides on `input.resource` **and** the request host; the broker re-checks the
   host itself, so neither layer alone is trusted.
2. **Read-only by construction.** GET only. No cookies, no credentials, no form submission, no
   clicks, no scripts supplied by the agent. URLs with a query string or fragment are rejected
   unless the charter lists that exact path, so no agent-chosen data can be smuggled out in a URL.
   Redirects are followed only within the allowlist.
3. **Plain HTTP first, Kitesurf second.** The broker fetches with ADR-0320's `SafeHttpClient` and
   extracts text in-cluster (HTML and PDF). Only hosts a charter marks `render: js` go to Cloudflare
   Browser Run with `browser=kitesurf` via Quick Actions (no CDP). Legal texts are mostly static, so
   most traffic never leaves to the provider; the provider is an adapter behind a port and can be
   switched off without changing any charter.
4. **Fetched content is data, never instructions.** The broker returns content wrapped in a fixed
   untrusted-content envelope with `url`, `final_url`, `fetched_at`, `sha256`, `bytes`, `renderer`.
   Size is capped per call; the agent prompt (registered under ADR-0148) states the content may
   contain instructions and must not be obeyed. Agents using this tool stay proposal-only
   (`draft.ticket`): the worst a successful injection can do is file an issue a human reviews.
5. **Evidence.** Each call is audited with the charter's `audit.capture` fields plus `source_url`,
   `sha256` and `renderer`. A finding cites the quote and the hash, so a reviewer can check the
   source as it was when read. The broker keeps the normalised text for 90 days for that purpose,
   and short quotes only go into issues.
6. **Good citizen.** Honour `robots.txt`, a generic descriptive User-Agent, per-host rate limit,
   and the charter's `limits` (`runs_per_day`, `kill_switch`).
7. **Containment, stated by layer.** NetworkPolicy here is enforced by the aws-network-policy-agent
   (ADR-0081, `openbank-infra/aws/modules/eks/main.tf`), which matches CIDRs and selectors, **not
   hostnames** — so the per-host allowlist cannot live in the network layer and this ADR does not
   claim it does. The network layer guarantees only: agent pods have no internet egress at all (the
   broker and the LLM gateway are their only destinations), and the broker may reach `0.0.0.0/0`
   on TCP 443 with RFC 1918, link-local and the metadata address excepted. The host allowlist is
   enforced twice in the application layer — OPA on the tool call, `SafeHttpClient` after DNS
   resolution in the broker. A hostname-aware egress proxy is a later hardening, not a premise.
   The Browser Run API token lives in OpenBao via ESO, mounted only into the broker.
9. **Liveness and evidence of effect.** The broker registers the shared workflow-liveness gauge
   (ADR-0237) and records success only on a fetch that returned content; it exports per-host
   counts of `fetched`, `denied`, `not_renderable` (ADR-0253). A source that cannot be read is
   reported as UNVERIFIED, never as "unchanged" — the same rule ADR-0366 applies to CI artifacts.
10. **Placement and licence.** The broker is an agent-plane module and joins
    `rules.yaml: agpl_modules` (ADR-0197). It is not on the money path; a SaaS renderer is
    consistent with ADR-0027, which forbids FaaS in the money path only.
11. **Injection is tested, not assumed.** The ADR-0148 evals for each consuming charter include
    a fetched page carrying embedded instructions; the expected output is a finding that quotes
    it, never a tool call it asked for (ADR-0235 adversarial replay).
8. **Third party.** Cloudflare Browser Run is entered in the ADR-0174 register as **non-critical**,
   no personal data, exit = disable the Kitesurf adapter (pages fall back to plain HTTP or the
   finding reports "not renderable"). Beta status is acceptable only because of that classification.

The first consumers are `regulatory-obligations-watcher` and, once a public source exists,
`card-scheme-bulletin-agent`. Each is enabled in its own PR, with its host list, its prompt and the
`opa eval` must-DENY/must-ALLOW run its charter already demands.

## Alternatives considered

- **Give agents the Kitesurf CDP endpoint directly (e.g. chrome-devtools-mcp).** Least code. Rejected:
  the agent holds the provider token, can navigate anywhere, click and submit forms, and every
  allowlist is advisory. Breaks ADR-0031 least privilege.
- **Self-hosted headless Chromium in-cluster.** No third party. Rejected as default: 150–400 MB per
  process, a large browser attack surface on our nodes, and the reason ADR-referenced renderers
  avoid it. Kept as a possible later adapter behind the same port if the provider exits.
- **Plain HTTP only, no rendering.** Simplest and fully in-house; adopted as the primary path.
  Alone it fails on JS-rendered pages (some authority portals), which would silently show up as
  "no change found" — the failure mode the disabled charters warn about.
- **Cloudflare's default Chromium browser in Browser Run.** Full fidelity, needed only for bot
  challenges or authenticated sessions — both of which this ADR forbids anyway. Higher cost.

## Consequences

**Positive**
- Unblocks two disabled charters with a control that is enforced, not described.
- One audited egress point for all agent web reads; injection blast radius bounded to a draft issue.
- Rendering cost stays off our nodes; the provider carries no personal data.

**Negative**
- New service to run (broker) and a new beta third-party dependency, with no SLA.
- Kitesurf does not render everything (no WebGL/video, no bot challenges); some pages will fail
  and must be reported as "not renderable", never as "unchanged".

**Neutral**
- Gate: new `check-public-web-fetch.py`, enforced in `Validate manifests`: every charter allowing
  `read.public_web` declares `data_scope.public_web.hosts` (HTTPS hostnames, no wildcard, no IP);
  every such charter has no `write`/`execute` allow; only the broker's ServiceAccount mounts the
  Browser Run secret; no agent-plane NetworkPolicy other than the broker's allows `0.0.0.0/0`
  egress, and the broker's excepts RFC 1918, link-local and `169.254.169.254/32`. Negative
  fixtures (a wildcard host, a write tier, a second secret mount, an agent pod with internet
  egress, a broker policy without the metadata exception) must each fail it. Shape follows the
  enforced `agent-bounded-write-surface` gate, including a `--self-test`.
- `gen-agents-opa-data.py` projects the new `data_scope.public_web.hosts` field into the bundle.

### Delivery check

```bash
# 1. the gate exists and is enforced
grep -A3 'id: public-web-fetch' .github/gates/gates.yaml | grep -q 'mode: enforced'
# 2. no agent pod can reach the internet (expect: only the fetch-broker policy listed)
grep -rl '0.0.0.0/0' openbank-infra/gitops/components/ai-platform/ | grep -v fetch-broker
# 3. OPA denies an off-list host and allows a listed one (expect: false, then true)
opa eval -d <bundle> 'data.openbank.mcp.allow' -i deny-host.json
opa eval -d <bundle> 'data.openbank.mcp.allow' -i allow-host.json
# 4. the broker carried work (expect: >0), not merely runs (ADR-0253)
#    sum(increase(openbank_web_fetch_total{outcome="fetched"}[7d]))
# 5. the liveness gauge exists for the broker (expect: one series, age < 2x schedule)
#    openbank_workflow_last_success_age_seconds{workflow="web-fetch"}
# 6. the broker is inside the AGPL boundary
grep -A40 'agpl_modules:' openbank-libs/governance/rules.yaml | grep -q fetch-broker
```

## Compliance impact

- PCI DSS: not applicable — no cardholder data reaches the broker or the provider.
- DORA: ICT third-party risk — Cloudflare Browser Run is entered in the ADR-0174 register as a
  non-critical provider with a documented exit (disable the adapter, fall back to plain HTTP).
- GDPR: not applicable by construction — requests carry only allowlisted public URLs; no personal
  data is sent, so no transfer outside the EU occurs (ADR-0175 D3 holds).
- PSD2: not applicable — no payment or account data is involved.
- CNB: supports the regulatory-obligations register (ADR-0367) by re-reading authority sources; the
  agent proposes, a human changes the register in a reviewed PR.
- EU AI Act: transparency is kept as the charters already state — every finding cites source URL,
  quote and content hash; agents stay proposal-only with human disposition.

## References

- Cloudflare Browser Run — Kitesurf: https://developers.cloudflare.com/browser-run/kitesurf/
- ADR-0027, ADR-0031, ADR-0034, ADR-0059, ADR-0081, ADR-0148, ADR-0174, ADR-0175, ADR-0197,
  ADR-0235, ADR-0237, ADR-0253, ADR-0265, ADR-0283, ADR-0320, ADR-0366, ADR-0367
- `openbank-libs/governance/agents.yaml` — `regulatory-obligations-watcher`, `card-scheme-bulletin-agent`
