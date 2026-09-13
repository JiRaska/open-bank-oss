# Security SLOs — measurable targets, not posture prose

> The security-service-level objectives of the platform: each one has a **metric, a data
> source that exists today, a target, and an owner-visible surface**. An SLO without a live
> number is a wish; this document lists only objectives whose numerator and denominator are
> computable from artifacts we already produce (ADR-0279 WS2, item #12). Tracked in #8590.

## The SLOs

| # | SLO | Metric / computation | Source of truth | Target | Surface |
|---|-----|----------------------|-----------------|--------|---------|
| S1 | **Critical-CVE remediation time (MTTR)** | days from a Critical/High CVE first appearing in a release SBOM to the first release whose SBOM no longer contains it | release `.cdx.json` bundle (release-please `release-evidence` job) + Trivy scan of the same | Critical ≤ 7 d, High ≤ 30 d | Security Excellence hub, Supply-chain pillar |
| S2 | **Signed-SBOM release coverage** | releases in the last 90 d carrying a cosign-signed `.cdx.json` + `.sig` ÷ all releases | GitHub Releases asset list | 100 % | hub; `verify-release-evidence.yml` weekly |
| S3 | **Vulnerability age in production** | age of the oldest Critical/High CVE present in the *running* image set (fleet attestation digests ↔ SBOM match) | fleet-attestation + SBOM, #8590 #11 wires the diff | oldest Critical ≤ 14 d | hub Supply-chain pillar |
| S4 | **Honey-endpoint dwell time** | minutes from first `honey endpoint hit` log line to `HoneyEndpointHit` alert delivery | Loki (rule pack, #8583) ↔ Alertmanager | ≤ 5 min | runbook-0018 purple-team drill measurement |
| S5 | **Authz decision integrity** | `openbank_security_authz_decisions_total` emitted by every service with micrometer; deny-ratio alert evaluated and not dead | `AuthzDenyRatioElevated` rule (#8583) + alert-metric-emitted gate | alert live 100 % of days | Prometheus rule console |
| S6 | **Security-incident regression coverage** | closed `security`+`incident` issues whose fixing PR touched `src/test`/`e2e` ÷ all closed security incidents | `security-regression-test.yml` PR gate (#8590 #5) | 100 % of new incidents | issue #8590 dashboard row |
| S7 | **Standing critical alerts** | count of critical-severity alerts firing > 24 h | ADR-0241 alert hygiene | 0 | existing alert-hygiene surface |

## Rules this document follows

1. **No SLO without a computable source.** When the source does not exist yet (S3, S4 end-to-end), the SLO says what wires it, and the row names the issue.
2. **Targets tighten, never loosen.** A target is raised only by an ADR, so a bad quarter cannot be edited away.
3. **Error budgets are release gates, not blame.** S1/S2 breaching budget pauses non-fix releases of the affected component until back inside target.

## References

- ADR-0279 (roadmap), ADR-0241 (alert hygiene), ADR-0278 (CRA — S1/S2 are the SBOM/evidence legs),
  `docs/compliance/cra-conformity-assessment.md`.
- Metrics: `SecurityTelemetry` (`openbank-libs-runtime`, #8554), rule pack (#8583),
  release evidence bundle (`release-please.yml`).
