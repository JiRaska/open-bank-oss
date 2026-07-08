# OpenBank security assurance case

This document is the project's **assurance case** (OpenSSF Best Practices, Silver
`assurance_case`): a structured argument, with links to evidence, for why OpenBank's
security requirements are met. It ties together the artifacts that already govern the
platform — the STRIDE threat models, the machine-enforced rules in
[`openbank-libs/governance/rules.yaml`](../openbank-libs/governance/rules.yaml), the
architecture ADRs, and the CI gates — into one place.

It follows the claim → argument → evidence structure. Sections: security requirements
(§1), threat model (§2), trust boundaries (§3), secure-design argument (§4),
common-weaknesses argument (§5), residual risks (§6).

## 1. Security requirements (claims)

OpenBank is a retail-banking platform reference implementation. Its top-level security
claims, from which everything below derives:

- **C1 — Money movement is correct and authorized.** No payment, posting, or card
  operation happens without an authenticated principal, an authorization decision, and
  (for money-path changes) four-eyes control. Double-entry invariants hold.
- **C2 — The platform is tamper-evident.** Every security-relevant action is recorded
  in an audit trail whose integrity is cryptographically verifiable.
- **C3 — Personal and financial data are protected.** Access is deny-by-default and
  scoped per principal type (customer, operator, AI agent, service).
- **C4 — The supply chain is trustworthy.** What runs in production is provably built
  from reviewed source with known dependencies.

## 2. Threat model

Threat modeling is per-service and CI-enforced, not a one-off document:

- **33 STRIDE threat models** live in [`docs/threat-models/`](threat-models/), one per
  service, each with data-flow diagram, trust-boundary inventory, and mitigations.
- For every **money-path service** (the list is machine-readable in
  [`rules.yaml: money_path_services`](../openbank-libs/governance/rules.yaml)) a threat
  model is **required by CI** before changes merge (ADR-0030); such PRs additionally
  need two approvals.
- Platform-level threat actors considered: external customers (PSU), third-party
  providers (TPP, PSD2 surface), operators, **AI agents** (first-class, least-privileged
  principal type — ADR-0034), inbound clearing counterparties (untrusted ISO 20022 XML),
  and a compromised CI or dependency (supply chain).

## 3. Trust boundaries

The platform's trust boundaries, each with its enforcement mechanism:

| # | Boundary | Enforcement |
|---|----------|-------------|
| B1 | Internet → edge | TLS everywhere; OIDC via Keycloak; PSD2 SCA flows (sca-service) |
| B2 | Between principal types (USER / OPERATOR / AI_AGENT / SERVICE) | Central **deny-by-default OPA policy** for both REST and MCP surfaces ([ADR-0034](adr/0034-unified-opa-authz-mcp-and-rest.md)); per-service Rego with CI coverage reporting |
| B3 | Service → service | Kubernetes NetworkPolicies generated from declared config (default-deny); mTLS in-cluster; per-service DB credentials (no shared schemas) |
| B4 | Inbound clearing files → domain | Typed parsers with totality guarantees; **fuzzed continuously** (Jazzer targets for `Pacs008Reader`, identity parsers — `fuzz/ossfuzz/`, ClusterFuzzLite on PRs) |
| B5 | Event bus | Outbox pattern (no dual writes), versioned backward-compatible schemas (rule #4 in [CONTRIBUTING.md](../CONTRIBUTING.md)) |
| B6 | Source → production (supply chain) | Signed commits (ruleset-enforced), PR-only merges, SLSA provenance + cosign-signed SBOM per release, digest-pinned images |

## 4. Secure design principles applied (argument for C1–C3)

- **Least privilege & complete mediation.** Authorization is centralized in OPA and
  deny-by-default: an endpoint without an explicit allow rule returns 403. AI agents are
  a separate, narrower principal class rather than inheriting operator rights
  (ADR-0034). CI workflow tokens follow least privilege (Scorecard Token-Permissions
  10/10).
- **Separation of concerns / economy of mechanism.** Hexagonal architecture per service
  ([ADR-0002](adr/0002-hexagonal-architecture-per-service.md)); the domain layer has
  **zero framework imports** (CI-enforced purity gate), so business invariants —
  double-entry balance, idempotency, four-eyes — are testable in isolation from any
  transport concern.
- **Defense in depth for money movement (C1).** A money-path change passes: 2 human
  approvals + threat model (CI gate) → OPA authz at runtime → four-eyes approval flow on
  money verbs → idempotency keys against replay → double-entry ledger invariants →
  tamper-evident audit record.
- **Tamper evidence (C2).** The audit trail is a hash-chained, verifiable log
  ([ADR-0133](adr/0133-tamper-evident-audit-chain.md)); release evidence bundles (SBOM,
  SLSA provenance, VEX) are cosign-signed and attached to every GitHub release.
- **Fail-safe defaults.** Deny-by-default authz (B2); default-deny NetworkPolicies
  (B3); new services must opt *in* to event dispatch and coverage floors rather than
  silently opting out.

## 5. Common implementation weaknesses countered (argument for C1–C4)

| Weakness class | Countermeasure (with evidence) |
|---|---|
| Injection, SSRF, deserialization, path traversal | CodeQL SAST on **all** commits (Scorecard SAST 10/10); Jazzer sanitizers (SQLi, LDAP, SSRF, deserialization hooks) run against the parser boundary in CI fuzzing |
| Malformed-input crashes (XXE, entity expansion, stack overflow) | Continuous fuzzing of untrusted-input parsers (B4); parser totality is an asserted property, not an assumption |
| Known-vulnerable dependencies | Dependabot (7 ecosystems, weekly) + dependency-review gate + Trivy image scans + OSV over the committed dependency graph (`gradle/verification-metadata.xml`) — 0 known vulnerabilities at last scan (Scorecard Vulnerabilities 10/10) |
| Dependency tampering | Gradle dependency verification (SHA-256 checksums committed); digest-pinned container images; SHA-pinned GitHub Actions (Scorecard Pinned-Dependencies) |
| Secret leakage | Gitleaks CI gate + GitHub push protection; secrets live in AWS Secrets Manager / OpenBao, never in the repo |
| Weak cryptography | No home-grown crypto: OIDC/JWT via Keycloak (RS256), cosign/KMS (ECDSA P-256, SHA-256) for signing, GPG-signed commits; no SHA-1/CBC-mode dependence in project defaults |
| Regression of fixed bugs | Ratchet-only coverage rule (coverage may never drop, rule #5); Pact contract tests broker-verified in CI; mutation testing lane (pitest) |
| Unreviewed changes | PR-only merges to `main` (ruleset), Conventional-Commit-driven release notes, independent LLM reviewer + human review, 2 approvals on money path |

## 6. Residual risks and roadmap

Known, openly tracked gaps (honesty is part of the assurance argument):

- **Fleet statement coverage is ~59 %**, below the 80 % Silver bar; the ratchet
  roadmap (issue #321) raises per-service floors quarterly, money-path first.
- **Bus factor 1.** Mitigated by the continuity mechanism in
  [GOVERNANCE.md](../GOVERNANCE.md#continuity-if-the-maintainer-is-incapacitated); a
  second maintainer remains the target (GOVERNANCE.md, "Path to distributed
  governance").
- **Reproducible builds** are not yet bit-for-bit verified (jar timestamps); candidate
  follow-up tracked in the SSDLC audit backlog.

## Maintenance

This assurance case is updated whenever a new ADR changes a trust boundary or a
mitigation, and reviewed as part of each periodic security audit (ADR-0030). Evidence
links intentionally point at CI-enforced artifacts (rules.yaml, workflows, threat-model
gate) rather than prose, so drift between the argument and reality is caught by the
same machinery it describes.
