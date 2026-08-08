---
date: 2026-06-28
decision-status: accepted
delivery-status: partial
followup: "#1915 — the syft-on-image axis: cosign attest on the KMS key and Kyverno verify-images Audit->Enforce"
authors: [jiri.raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [supply-chain, security-ops, ci]
summary: "SBOM is split into two axes: an operational self-reported SBOM generated host-side only and served at /q/openbank/sbom, and a syft-on-image SBOM signed with cosign attest on the existing KMS key and verified at Kyverno admission."
---

# Service self-reported SBOM and supply-chain attestation

**Delivery note (updated 2026-07-05):** promoted from Proposed — the in-flight branches this
ADR was written ahead of have long since merged and the decision held up in practice.
- **Axis 1 (operational SBOM)** — ✅ Shipped: `openbank-libs-runtime/.../web/SbomResource.kt`
  serves the live `/q/openbank/sbom` contract from the image-baked CycloneDX document; the
  admin-ui Tech Inventory SBOM viewer (`app/api/services/[name]/sbom/route.ts`) reads it live
  per-service with a fallback to the image-baked bundle. Host-side-only generation rule holds.
- **Axis 2 (attested supply-chain SBOM)** — 🟡 Partial, further along than the previous note
  suggested: `cosign attest --type cyclonedx` runs on every pushed image in `auto-deploy.yml`
  (KMS key, same trust root as image signing), and image-signature verification is already
  **Enforce** in Kyverno (`verify-images-policy.yaml`, ADR-0030 D4). What remains open, per
  that policy file's own roadmap comment: a **second** `verifyImages` rule requiring the SBOM
  attestation specifically (not just the signature) at admission — still marked "(planned)"
  there. That is the one concrete remaining gap closing this ADR fully.

<details>
<summary>Original delivery note (2026-06-30), superseded above</summary>

- **Axis 1 (operational SBOM)** — ✅ Specification complete and rule codified: host-side-only `cyclonedxBom` generation in `build-push-service.sh`, `COPY`-only in Dockerfiles, `/q/openbank/sbom` serving contract (`openbank.sbom.v1`) and admin-ui Tech Inventory consumer are designed; in-Docker `cyclonedxBom` path on `build/sbom-bake-pilot` Dockerfile rejected and the fix rule added to `CLAUDE.md` and `rules.yaml`; branches (`feat/libs-sbom-resource`, `build/sbom-bake-pilot`, `feat/admin-ui-sbom-live`) are in-flight and not yet merged to `main`.
- **Axis 2 (attested supply-chain SBOM)** — ⬜ Pending: `syft`-on-image full SBOM generation, `cosign attest` OCI referrer attachment reusing the existing `awskms` signing key (ADR-0093), and Kyverno `verify-images-policy` extension to enforce SBOM attestation at admission (closing ADR-0029 D2) are not yet implemented.

</details>

## Context

OpenBank services are converging on a **self-describing** model: each service reports its own
build/version (`ServiceInfoResource` `/api/v1/info`), its own governance metadata (per-service
`governance.yaml`, ADR-0071), and its own API docs (`DocsResource` `/q/openbank/docs`). The next axis —
each service self-reporting its own SBOM at `/q/openbank/sbom` — is **in flight on unmerged branches**,
not yet on `main`:

- `feat/libs-sbom-resource` adds `openbank-libs` `web/SbomResource`, serving a CycloneDX document baked
  into the image so what a running service reports is exactly what was built.
- `build/sbom-bake-pilot` wires the generation + image bake for the `account-service` pilot.
- `feat/admin-ui-sbom-live` adds the admin-ui Tech Inventory consumer.

This ADR records the direction **before those branches merge**, and resolves two issues visible in the
in-flight work so the merged result is consistent:

1. **The two in-flight generation paths diverge.** On `build/sbom-bake-pilot`, the canonical generic
   builder `openbank-infra/scripts/build-push-service.sh` generates the SBOM **host-side** (runs
   `cyclonedxBom` next to the host-side `quarkusBuild`, stages `bom.json`, then `COPY`s it) — correct,
   and consistent with the standing "host-side build, never in-Docker Gradle" rule (in-image Gradle hits
   NAT-shared 429 download throttling and can't resolve sub-project dirs). But the same branch's
   `account-service` **Dockerfile** instead adds `cyclonedxBom` to the *in-Docker* `gradlew` invocation,
   forking the generation path and violating that rule.
2. **Operational view ≠ supply-chain integrity.** The served SBOM is an **operational** artifact: "what
   JVM/Gradle dependencies are running", network-gated, regenerated per build. It is *not* a signed,
   attested supply-chain artifact (cosign / SLSA, ADR-0029 D2 — still open), and it does **not** cover
   the container OS layer (base image, sidecars). Conflating the two would give a false sense of
   supply-chain assurance.

## Decision

**We will treat operational SBOM and attested supply-chain SBOM as two distinct axes, codify a single
generation path for each, and require the in-flight SBOM branches to conform before merge.**

### Axis 1 — Operational SBOM (self-reported, live)

- Generated **host-side only**, in `build-push-service.sh`, from `cyclonedxBom` (CycloneDX 1.5,
  `runtimeClasspath`). Per-service Dockerfiles **MUST** only `COPY` the staged artifact — they **MUST
  NOT** invoke Gradle/`cyclonedxBom` in-Docker. Concretely, the `account-service` pilot Dockerfile on
  `build/sbom-bake-pilot` drops its in-Docker `cyclonedxBom` and relies on the staged `COPY`, exactly
  like the generic builder. This resolves issue (1). The rule is added to `CLAUDE.md` (GitOps › image
  builds) and `rules.yaml`.
- Served at `/q/openbank/sbom` (`openbank.sbom.v1` contract), network-gated, consumed live by admin-ui.
  Unchanged from the in-flight implementation.

### Axis 2 — Attested supply-chain SBOM (release artifact)

- At deploy-build, after the image is pushed, run **`syft` against the final image** to produce a full
  SBOM that includes **both** JVM dependencies **and** the container OS layer, then **`cosign attest`**
  it and attach it as an OCI referrer / SLSA provenance. This reuses the **cosign v2 KMS signing already
  wired into `build-push-service.sh`** (the same `awskms` key that signs OpenBank images, ADR-0093) — no
  new key-management surface.
- Extend the existing **kyverno `verify-images-policy`** to also verify the SBOM attestation at
  admission, so an image without a valid attested SBOM cannot be admitted. This closes ADR-0029 D2 and
  gives admission-time supply-chain enforcement.
- The attested SBOM is a **registry/compliance** artifact, not the admin-ui live view; the two axes serve
  different consumers and are never conflated.

## Alternatives considered

- **In-Docker `cyclonedxBom` (the pilot Dockerfile path)** — keeps SBOM generation co-located with the
  image build. Rejected: violates the host-side-build rule (NAT 429 download timeouts, slower, duplicated
  Gradle invocation) and forks generation across two code paths.
- **Operational SBOM only; skip attestation** — simplest. Rejected: leaves ADR-0029 D2 open, gives no
  admission-time supply-chain verification, and never sees the OS layer — insufficient for the
  public-launch hardening (ADR-0124 oss-readiness) and DORA third-party/ICT supply-chain risk.
- **Attested SBOM only; no live endpoint** — Rejected: loses the self-report introspection plane and the
  admin-ui live "what's running" view, which is the operational value the in-flight work delivers.

## Consequences

**Positive**
- One generation path per axis; the host-side/in-Docker inconsistency is resolved by rule before the
  SBOM branches merge, not retrofitted after.
- Closes ADR-0029 D2; admission-time SBOM verification via the kyverno gate already in place.
- OS-layer coverage via `syft`-on-image; the served CycloneDX stays the fast JVM-deps live view.
- Reuses existing KMS cosign infrastructure — no new key-management surface.

**Negative**
- `syft` + `cosign attest` add deploy-build time and a kyverno policy extension.
- Two artifacts to reason about; docs must keep the operational-vs-attested distinction sharp.

**Neutral**
- The `/q/openbank/sbom` serving contract (`openbank.sbom.v1`) is unchanged.

## Compliance impact

- PCI DSS: Req. 6.3.2 (inventory of bespoke/3rd-party components) — supported by both axes.
- DORA:    Art. 28 (ICT third-party risk) / Art. 8–9 (ICT asset inventory) — attested SBOM supports.
- GDPR:    not applicable.
- PSD2:    not applicable.
- CNB:     not applicable beyond the DORA mapping.
- EU CRA:  SBOM availability + supply-chain integrity expectations — both axes contribute.

## References

- ADR-0029 D2 — signed/attested release artifacts (cosign / SLSA) — closed by Axis 2.
- ADR-0030 — no runtime SBOM drift (image-baked operational SBOM upholds this).
- ADR-0071 — derived, self-reported per-service governance (the self-describing-service theme).
- ADR-0093 — public developer portal (the cosign v2 KMS image-signing reused by Axis 2).
- ADR-0124 — OSS readiness and public-launch hardening.
- In-flight branches: `feat/libs-sbom-resource`, `build/sbom-bake-pilot`, `feat/admin-ui-sbom-live`.
- `openbank-infra/gitops/components/kyverno/verify-images-policy.yaml` — admission gate to extend.
