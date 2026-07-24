# DORA + Supply-Chain Evidence Pack

Date: 2026-07-23
Regulation: EU 2022/2554 (DORA); EU SSDLC / supply-chain expectations (SLSA, CycloneDX, OpenVEX)
Owner: CTO / Maintainer Lead
Status: living document — regenerated when the underlying artifacts change

---

## How to read this pack

Each row maps **a regulatory requirement → the artifact in this repo that satisfies it → a command an
auditor can run to verify the artifact exists and is well-formed**. Every artifact and command is
checkable against the state of `origin/main`; nothing here is aspirational. Where a control is
partial or a gap, it is called out plainly in the [Honest gaps](#honest-gaps) section rather than
dressed up as complete.

Conventions:

- `gh` commands assume the repository `JiRaska/open-bank-oss` and an authenticated `gh` CLI.
- `cosign` commands assume cosign **v2.x** (pinned on purpose — see
  [`openbank-infra/scripts/lib/cosign-attest.sh`](../../openbank-infra/scripts/lib/cosign-attest.sh)).
- The cosign trust root is the AWS KMS key `awskms:///alias/openbank-cosign-signing`; its public key
  is embedded (for offline verification) in
  [`verify-sbom-attestation-policy.yaml`](../../openbank-infra/gitops/components/kyverno/verify-sbom-attestation-policy.yaml).

---

## 1. DORA ICT risk management (EU 2022/2554)

The authoritative Article-by-Article mapping lives in
[`docs/bcp/dora-ictrm.md`](../bcp/dora-ictrm.md); this pack points an auditor at the evidence and the
verification command. Do not duplicate the Article table here — read it there.

| DORA reference | Requirement | Evidence artifact | Auditor verification command |
|---|---|---|---|
| Art. 5–6 | ICT risk-management framework + risk system | [`docs/bcp/dora-ictrm.md`](../bcp/dora-ictrm.md) (Article map + risk register); threat models under [`docs/threat-models/`](../threat-models/) | `test -f docs/bcp/dora-ictrm.md && ls docs/threat-models/*.md \| wc -l` |
| Art. 8 | Asset identification / register | per-service SBOMs (see §2); ICT third-party register [`docs/adr/0174-*.md`](../adr/0174-ict-third-party-dependencies-and-exit-strategy.md) | `test -f docs/adr/0174-ict-third-party-dependencies-and-exit-strategy.md` |
| Art. 9 | Protection & prevention (access, encryption, patch) | OPA authz (ADR-0034), mTLS Kafka (ADR-0137), Keycloak OIDC, OpenBao secrets; PR-time CVE gate (see §2) | `grep -ni mtls docs/adr/0137-*.md; test -f docs/adr/0034-unified-opa-authz-mcp-and-rest.md` |
| Art. 10, 17 | Detection + incident classification | [`docs/bcp/incident-response.md`](../bcp/incident-response.md) — P1–P4 tiers, declaration timing | `test -f docs/bcp/incident-response.md && grep -c '\*\*P' docs/bcp/incident-response.md` |
| Art. 11 | Response & recovery (BCP/DRP, RTO/RPO) | [`docs/bcp/bcp-policy.md`](../bcp/bcp-policy.md) — T0–T3 RTO/RPO tiers | `grep -nE 'T0 — Payment' docs/bcp/bcp-policy.md` |
| Art. 11(3)(c) | Documented, tested DR plan | [`docs/bcp/dr-test-log.md`](../bcp/dr-test-log.md); automated restore design [`docs/bcp/automated-dr-restore.md`](../bcp/automated-dr-restore.md) + [`.github/workflows/dr-restore-verify.yml`](../../.github/workflows/dr-restore-verify.yml) | `test -f docs/bcp/dr-test-log.md && test -f .github/workflows/dr-restore-verify.yml` |
| Art. 12 | Backup policies (backup + restore) | CNPG S3 WAL archiving, 14-day retention, PITR (bcp-policy.md recovery-capabilities table) | `grep -nE 'WAL archiving\|Point-in-time' docs/bcp/bcp-policy.md` |
| Art. 19–20 | Notification of major incidents to regulators | [`docs/bcp/incident-response.md`](../bcp/incident-response.md) §3 (register → 4 h notification) | `grep -n 'Art. 19' docs/bcp/incident-response.md` |
| Art. 24–27 | Digital operational resilience testing (incl. TLPT) | DR table-top exercises in `dr-test-log.md`; deterministic simulation testing [`docs/adr/0100-*.md`](../adr/0100-deterministic-simulation-testing.md). **TLPT itself is planned-only** — see gaps | `grep -n TLPT docs/adr/0030-*.md docs/adr/0100-*.md` |
| Art. 28, 30 | ICT third-party risk + contractual arrangements | ICT third-party register & exit strategy [`docs/adr/0174-*.md`](../adr/0174-ict-third-party-dependencies-and-exit-strategy.md); `dora-ictrm.md` Art. 28/30 backlog | `test -f docs/adr/0174-ict-third-party-dependencies-and-exit-strategy.md` |

---

## 2. Supply-chain / release provenance

Enforced authoritatively by [`openbank-libs/governance/rules.yaml`](../../openbank-libs/governance/rules.yaml)
(`provenance:` block, ADR-0029 D2 / ADR-0030 D4).

| Control | Requirement | Evidence artifact | Auditor verification command |
|---|---|---|---|
| SBOM (per service) | CycloneDX SBOM generated per service | `./gradlew sbomAll` output; per-service SBOM job in [`security.yml`](../../.github/workflows/security.yml) | `grep -nE 'cyclonedx\|sbomAll' .github/workflows/security.yml` |
| SBOM (aggregate) | Repo-level CycloneDX SBOM attached in CI | `anchore/sbom-action` (`format: cyclonedx-json`) in `security.yml` | `grep -nE 'cyclonedx-json' .github/workflows/security.yml` |
| Image SBOM attestation | Every `openbank-*` image carries a cosign-signed CycloneDX attestation | [`cosign-attest.sh`](../../openbank-infra/scripts/lib/cosign-attest.sh); Kyverno `verify-openbank-image-sbom-attestation` policy | `cosign verify-attestation --key awskms:///alias/openbank-cosign-signing --type cyclonedx <ecr-image@sha256:...>` |
| Image signing | Images cosign-signed, verified at admission | Kyverno `verify-openbank-image-signatures`; KMS key `alias/openbank-cosign-signing` | `cosign verify --key awskms:///alias/openbank-cosign-signing <ecr-image@sha256:...>` |
| SLSA provenance | in-toto / SLSA provenance on releases | `rules.yaml: provenance.attestation: slsa`; release evidence bundle (below) | `grep -nE 'attestation: slsa' openbank-libs/governance/rules.yaml` |
| Release evidence bundle | SBOM + SLSA provenance + coverage summary, cosign-signed, attached to the GitHub release | [`verify-release-evidence.yml`](../../.github/workflows/verify-release-evidence.yml) (verify side); [`backfill-release-evidence.yml`](../../.github/workflows/backfill-release-evidence.yml) | `gh api repos/JiRaska/open-bank-oss/releases/tags/<tag> --jq '.assets[].name'` |
| Release-bundle signature verify | Each bundle document verified against the KMS cosign key + manifest digests | `verify-release-evidence.yml` (`cosign verify-blob --key awskms:///alias/openbank-cosign-signing`) | `gh workflow run verify-release-evidence.yml -f tag=<service>-v<x.y.z>` |
| Fleet attestation gate | Every image declared in gitops is provably attested (catches admission gaps at review time) | [`fleet-attestation.yml`](../../.github/workflows/fleet-attestation.yml) + [`check-fleet-attestations.sh`](../../.github/scripts/check-fleet-attestations.sh) | `gh workflow run fleet-attestation.yml` then inspect the `fleet-attestation-report` artifact |
| VEX (vuln exploitability) | Per-service OpenVEX statements to triage CVEs | [`openbank-libs/governance/vex/`](../../openbank-libs/governance/vex/) (`<service>.openvex.json`); [`vex-triage.yml`](../../.github/workflows/vex-triage.yml) | `ls openbank-libs/governance/vex/*.openvex.json \| wc -l` |
| PR-time CVE gate | Dependency graph submitted + CVE-blocked at PR time | [`dependency-submission.yml`](../../.github/workflows/dependency-submission.yml) (keeps its `pull_request` trigger) + [`dependency-review.yml`](../../.github/workflows/dependency-review.yml); `rules.yaml: dependencies` | `grep -nE 'pull_request' .github/workflows/dependency-submission.yml` |
| Threat models | Money-path services carry a threat model | [`docs/threat-models/<service>.md`](../threat-models/); `rules.yaml: money_path_services` (ADR-0030 D2) | `ls docs/threat-models/*.md \| wc -l` |
| RTO/RPO tiers | Recovery targets bound to service tiers | [`docs/bcp/bcp-policy.md`](../bcp/bcp-policy.md) T0–T3 table | `grep -nE 'RTO target' docs/bcp/bcp-policy.md` |
| OpenSSF posture | Independent supply-chain scorecard | [`scorecard.yml`](../../.github/workflows/scorecard.yml); [`docs/openssf-silver-assessment.md`](../openssf-silver-assessment.md) | `gh workflow view scorecard.yml` |

### Provenance gate rollout state (be precise with auditors)

The deploy-time provenance gate graduated Audit → Enforce independently per rule. The authoritative
rollout state is `rules.yaml: provenance.gate` and the live Kyverno policy mode. As of this pack the
`verify-openbank-image-sbom-attestation` ClusterPolicy is **Enforce** (graduated from Audit on
2026-07-12, ADR-0144; documented in-line in
[`verify-sbom-attestation-policy.yaml`](../../openbank-infra/gitops/components/kyverno/verify-sbom-attestation-policy.yaml)) —
an image lacking a cosign-signed SBOM attestation is REJECTED at admission; the image-signature
policy has been Enforce since 2026-06-11. Verify the live mode before asserting either way:

```
grep -nE 'validationFailureAction|Audit|Enforce' \
  openbank-infra/gitops/components/kyverno/verify-sbom-attestation-policy.yaml
```

---

## 3. AI-Act evidence

DORA and the EU AI Act overlap on AI-system governance for the four AI agent services
(ADR-0031 / ADR-0136). The AI-Act-specific control mapping is **owned by a separate document**,
`docs/compliance/eu-ai-act.md` (ADR-0148, issue #1918), and is **not duplicated here**. This pack's
AI-Act row is: *see [`docs/compliance/eu-ai-act.md`](./eu-ai-act.md)* — the AI-system inventory,
Annex III classification, the Art. 9–15 obligation mapping, and the LLM prompt-egress gap. That
document is **generated** from `openbank-libs/governance/agents.yaml` (`gen-eu-ai-act.py`) and
drift-guarded by `check-eu-ai-act.sh`.

In addition, the AI-governance substrate an auditor can verify directly: agents-as-code +
AI-attributed audit (ADR-0031), policy-gated MCP tool-calls via the shared OPA sidecar (ADR-0034),
agent charters as Markdown (ADR-0156), and the versioned prompt registry with its integrity guard
(`openbank-libs/governance/prompts/` + `check-prompt-registry.py`, ADR-0148).

---

## Honest gaps

These are real, current limitations. Stating them is part of the evidence, not a failure of it.

1. **ICT incident register has no purpose-built persistent store.** Declarations and resolutions are
   recorded through the tamper-evident audit chain (ADR-0086 / ADR-0133) and the issue tracker per
   [`incident-response.md`](../bcp/incident-response.md) §3. There is **no dedicated, queryable
   incident-register service** — a real institution deploying OpenBank must provide one before
   go-live. Treat any "register" reference as the audit-chain + issue-tracker composite, not a
   standalone system.
2. **TLPT (Threat-Led Penetration Testing, DORA Art. 24–27) is planned-only.** The repo documents the
   requirement (ADR-0030, ADR-0100, `docs/strategy/`) and provides deterministic simulation testing as
   partial "advanced testing" evidence, but no external TLPT engagement has been executed. It is an
   external-provider activity, not a repo artifact.
3. **Automated DR restore is a wired-once skeleton, not a passing quarterly control.**
   [`dr-restore-verify.yml`](../../.github/workflows/dr-restore-verify.yml) intentionally hard-fails
   (`WIRING INCOMPLETE`) rather than reporting a hollow PASS; `dr-test-log.md` currently records
   **table-top** exercises only. See [`automated-dr-restore.md`](../bcp/automated-dr-restore.md) for
   the remaining wiring.
4. **DORA Art. 28/30 third-party controls are partial.** A vendor risk register and DORA Art. 30
   contractual clauses are backlog items (`dora-ictrm.md` follow-up; ADR-0174 `partial`), relevant
   once the platform is commercially operated. (Note: the supply-chain *technical* gate is NOT a gap —
   both the image-signature and SBOM-attestation Kyverno policies are Enforce and reject
   non-compliant images at admission, §2 above.)
5. **Escalation is single-maintainer (bus factor 1).** `incident-response.md` §4 documents no
   secondary on-call — a deploying institution must fill this in before go-live.

---

## Related

- [`docs/compliance/finos-ccc-mapping.md`](./finos-ccc-mapping.md) — FINOS Common Cloud Controls + AI
  Governance Framework mapping.
- [`docs/bcp/`](../bcp/) — the BCP / DORA / incident-response source documents.
- [`openbank-libs/governance/rules.yaml`](../../openbank-libs/governance/rules.yaml) — the authoritative,
  CI-enforced governance ruleset.
