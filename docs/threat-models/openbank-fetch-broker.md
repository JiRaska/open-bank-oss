# Threat Model — public-web fetch broker (`openbank-fetch-broker`)

**Component:** the in-cluster MCP server that gives governed agents their only way to read the
public web: one tool, `web.fetch` (GET only), authorised per call as resource `read.public_web`.
**Manifests:** `openbank-infra/gitops/components/openbank-fetch-broker/` (`fetch-broker.yaml`,
`networkpolicy-fetch-broker-egress.yaml`, `network-policies.yaml`, `fetch-broker-opa-bundle.yaml`,
`browser-run-externalsecret.yaml`)
**ADRs:** ADR-0387 (this tool), ADR-0031 (agent charters), ADR-0034 (OPA PDP), ADR-0081
(NetworkPolicy baseline), ADR-0174 (ICT third-party register), ADR-0030 (threat-model discipline)
**Review cadence:** on any change to the egress policy, the ingress allow-list, the host-check
rule in `agents.rego`, a charter that allows `read.public_web`, or the renderer adapter.

Not a money-path service. It is modelled because it is the **only agent-plane pod allowed to reach
the internet**, and what it returns is untrusted content an agent will read.

---

## 1. Scope

**In scope.** The broker Deployment, its OPA sidecar and bundle, its egress to public HTTPS hosts,
the optional JS renderer (Cloudflare Browser Run) and its credential.

**Out of scope.** What a consuming agent does with fetched text (its own charter, evals and
threat model), the renderer provider's internals, and the content of the public sites themselves.

## 2. Data flow

```
 agent pod ──(TB-1: in-cluster, bearer token for audience openbank-fetch-broker, :8172)──▶ broker
                                                                                          │
                                          OPA sidecar (localhost:8181, read.public_web) ◀─┤
                                                                                          ├─(TB-2: 443 only, minus private/link-local)─▶ public host
                                                                                          └─(TB-3: 443, render: js hosts only)─▶ Browser Run
```

- **TB-1** — ingress is the generated allow-list: callers that declare the broker URL, plus the
  platform's static edges (Prometheus and the security scanner on the management port, admin-ui
  discovery). Every MCP call also needs a Keycloak token issued for the broker's audience.
- **TB-2** — `openbank-fetch-broker-egress` allows DNS, Keycloak, and TCP 443 to `0.0.0.0/0`
  except `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, `169.254.0.0/16` (so the instance
  metadata address too). The network layer matches CIDRs only, never hostnames.
- **TB-3** — the renderer receives only URLs of hosts a charter marks `render: js`.

## 3. Assets

| Asset | Where | Why it matters |
|---|---|---|
| Browser Run API token and endpoint | `browser-run-api-token` Secret, env of this pod only | renderer spend, account misuse at the provider |
| The per-charter host allowlist | `agents.yaml` → OPA bundle, re-checked in the broker | it is the only thing bounding where agents can read |
| Agent identity in a call | caller's bearer token | decides which charter's hosts apply |
| Fetched content | memory, returned to the agent | untrusted, may carry prompt-injection text |

## 4. STRIDE

| Category | Threat | Mitigation | Residual |
|---|---|---|---|
| **Spoofing** | A pod calls the broker as another agent to use that agent's hosts | Token audience `openbank-fetch-broker`, caller is the token `sub`; OPA decides per charter | A compromised agent can read every host its own charter lists |
| **Spoofing** | A look-alike workload borrows the broker's egress | The egress policy selects the broker by name label; `check-public-web-fetch.py` binds the broker to its manifest, name and image and fails on any other workload matching the selector | — |
| **Tampering** | A charter edit widens the host list to "the internet" | Gate rejects wildcards, IP literals, internal names and a write/execute tier on the same charter; charter edits are reviewed PRs | A reviewed but unwise host stays readable |
| **Tampering** | A public name resolves to an internal address (DNS rebinding, SSRF) | Egress policy drops private and link-local destinations; `SafeHttpClient` re-checks the resolved address | IPv6 is allowed only to public ranges, judged by the gate |
| **Repudiation** | An agent denies having read a page | Each call writes an audit line with the charter's capture fields and the source URL (ADR-0387 D5); tracing is off, so the log is the record | Log retention bounds the window |
| **Information disclosure** | Agent data smuggled out in a query string | GET only; a URL path or query is refused unless the charter lists it (ADR-0387 D2) | Hostnames themselves are visible to DNS and the remote site |
| **Information disclosure** | The renderer token leaks to another workload | Only this Deployment references the secret (gate-enforced); keys are `optional: true` | A broker compromise exposes the token; rotate it in OpenBao |
| **Denial of service** | Huge or slow responses, decompression bombs | Response byte cap, content character cap, per-host minimum interval; PDF extraction off | Single replica: a crash stops public-web reads until restart |
| **Elevation of privilege** | Fetched text instructs the agent to act | Charters that allow `read.public_web` may not hold write or execute tiers (gate-enforced); content is labelled untrusted | Injection that changes a draft finding is possible; the ADR-0148 evals test for it |
| **Elevation of privilege** | Container escape from the internet-facing pod | `runAsNonRoot`, `drop: [ALL]`, no privilege escalation, read-only root, seccomp `RuntimeDefault`, no service-account token, PodSecurity `restricted` namespace | — |

## 5. Verification

- Policy: `opa test` over `agents_test.rego` (listed host allowed; off-list host, missing
  resource, write tier, other tool, missing host and charter deny all denied), and
  `gen-agents-opa-data.py`'s differential proof over the broker's input shape.
- Network and secret: `check-public-web-fetch.py` (enforced in `Validate manifests`) with its
  self-test.
- Any change to the egress or ingress policy, or to the host-check rule, is a review trigger for
  this document.
