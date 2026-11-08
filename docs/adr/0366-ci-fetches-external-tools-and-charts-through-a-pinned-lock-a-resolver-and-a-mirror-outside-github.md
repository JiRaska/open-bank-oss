---
date: 2026-11-08
decision-status: proposed
delivery-status: planned
authors: [jiri.raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [ci, supply-chain, resilience]
summary: "CI resolves every external tool and Helm chart from one sha256-pinned lock through a single resolver: Actions cache, then a mirror on ECR Public outside GitHub, then upstream; an unreachable artifact is UNVERIFIED, never a finding."
---

# ADR-0366 — CI fetches external tools and charts through a pinned lock, a resolver and a mirror outside GitHub

## Context

CI downloads its tooling at run time, and each script does it its own way. On `origin/main`
(2026-11-08), 20 files under `.github/` fetch from about 15 upstream sources: GitHub Releases
(gitleaks, cosign, oasdiff, trivy, yq, infracost, prometheus/promtool, pact standalone), the
OPA download site, and a dozen Helm repositories on `*.github.io` whose chart archives are
themselves served from GitHub Releases. There is no inventory of what CI depends on, and no
second place to get it from.

On 2026-11-08 from about 08:50Z, `github.com` answered HTTP 504 for release downloads and for
ordinary pages. This was observed from a hosted runner and from a workstation; the 504 carried
an `x-github-request-id`, and the status page showed no incident. The Actions API, checkout and
runners kept working throughout. Two **required** contexts went red for reasons unrelated to
any change:

- `Gitleaks`: the `Install gitleaks` step died on `curl: (22)`.
- `gates (gitops-api)`: `helm-values-key-effect` could not pull a single chart (`could not
  reach … after 3 attempts`).

`main` went red, and the merge train holds while `main` is red, so nothing merged. An earlier
stall the same day had the same root cause, reached from the other side: a setup-gradle bump
changed a dependency inherited from the action's default (the dependency-graph plugin,
#15161 / #15211). The version CI ran was not declared anywhere we control.

ADR-0363 is the closest decision. It places a remote Gradle build cache outside AWS and makes
it **fail-open**: a missing cache only makes a build slower. It does not cover tools or charts.
Those are inputs a gate needs before it can say anything, so failing open is not an option for
them. ADR-0030 (supply chain) requires pinned, verifiable inputs. It does not say where CI
obtains them, or what happens when the source is down.

The estate already solved the same problem once, for container images.
`openbank-infra/aws/envs/sandbox-platform/ecr-public-mirror.tf` mirrors images that Docker
Hub's rate limit kept failing (kafka for CI, and alloy, grafana, falco and falcoctl for the
cluster) into ECR Public. Those images are pushed by `scripts/mirror-ci-base-images.sh` and
pulled anonymously. This ADR extends that working pattern from images to tools and charts. It
uses the same provider (`aws.us_east_1`) and the same registry alias, and it adds no new vendor.

## Decision

We will make external CI artifacts a declared, pinned, mirrored dependency with exactly one way
to fetch them.

1. **One lock: `ci/artifacts.lock`.** Every external binary, archive and Helm chart that CI
   fetches is listed with name, version, upstream URL, platform and **sha256**. A version bump
   changes this file, not individual scripts. The lock is the only trust anchor: whichever
   source serves the bytes, they are accepted only if their digest matches.
2. **One resolver: `.github/scripts/fetch-artifact`.** Callers ask for an artifact by name. The
   resolver tries three sources in fixed order: the GitHub Actions cache (keyed by name and
   digest), then the mirror, then the upstream URL. It verifies sha256 against the lock and
   refuses a mismatch from any source. A cache or mirror hit therefore cannot change what runs,
   and a changed upstream is rejected rather than silently adopted.
3. **The mirror is Amazon ECR Public, outside GitHub's failure domain.**
   - Artifacts are stored as OCI artifacts (`oras`; Helm charts natively as `oci://`).
   - Reads are anonymous, so PRs from forks need no credential.
   - Only a scheduled, on-lock-change workflow on `main` writes. It uses the existing GitHub
     OIDC trust (ADR-0060) and a role limited to that one registry namespace.
   - The mirror holds only public open-source artifacts, the same bytes anyone can download
     upstream.
4. **Mirror completeness is checked, not assumed.** The mirror workflow pulls every lock entry
   that is missing or stale, verifies it against the lock and pushes it. It goes red if any
   entry cannot be mirrored. A new lock entry that never reached the mirror is therefore visible
   on the day it lands.
5. **Unreachable is a third state.** If no source can deliver a verified artifact, the resolver
   exits with a distinct status and the calling gate reports `UNVERIFIED (artifact
   unavailable)`. The check still fails, because a required check that cannot run must not
   pass. The log and the gate summary label it as infrastructure, and the merge train re-runs
   such a failure once before it holds the queue.
6. **No direct downloads.** A gate fails any download under `.github/` that bypasses the
   resolver and targets a lock-eligible upstream: GitHub Releases, `*.github.io`, the OPA
   download site, and others as they are added. It covers `curl`, `wget`, `helm pull`,
   `helm repo add` and action downloads. The gate starts `advisory` and becomes `enforced`
   once the existing 20 call sites are migrated.

Rollout runs in four steps:
1. The lock, the resolver and the gate (advisory), with the three artifacts that blocked
   required contexts: gitleaks, the `helm-values-key-effect` charts, and promtool.
2. The ECR Public registry, the IAM role and the mirror workflow.
3. The remaining call sites, one batch at a time.
4. The gate in `enforced`.

## Alternatives considered

- **`actions/cache` only, no mirror.** Needs no new infrastructure. Rejected as the whole
  answer: the repo's Actions cache sits permanently over its 10 GB ceiling (10.7 GB, 100
  entries, measured 2026-11-08), so entries are evicted. A cache miss during an upstream outage
  still fails, which is exactly the case this ADR exists for. The cache stays as the first,
  fastest tier.
- **GHCR as the mirror.** Free and already authenticated. Rejected: it shares GitHub's failure
  domain, and independence from that domain is the whole point of the mirror.
- **A registry on the Hetzner host ADR-0363 runs.** It is outside both GitHub and AWS, and it
  already exists. Rejected: a single self-operated box, with its TLS, patching and uptime,
  would become part of the availability path of required checks. ADR-0363 accepts that box
  only because a cache there fails open, and a mirror does not have that property.
- **Vendor the artifacts into the repository.** Fully offline. Rejected: several hundred MB of
  binaries in the history of a public repository, a review burden on every bump, and it still
  needs a separate update mechanism.
- **Bake the tools into a custom runner image.** Rejected: GitHub-hosted runners cannot use a
  custom image. It would also only move the download to image build time, not remove it.

## Consequences

**Positive**
- An outage of GitHub Releases or `*.github.io` no longer turns required checks red, as long as
  GitHub Actions itself runs.
- One reviewed file lists every external input CI executes, with digests. That serves the
  supply-chain posture of ADR-0030, and Dependabot/Renovate can bump it.
- A failure caused by an unavailable artifact is distinguishable from a finding, so nobody
  debugs a policy gate during an upstream outage.

**Negative**
- More ECR Public repositories on the existing alias, an IAM write role, a workflow, and a
  sweep of 20 call sites.
- ECR Public runs only in `us-east-1`. That is acceptable because the artifacts are public
  open-source tools, not customer data, so ADR-0175 residency does not apply.
- An outage of GitHub Actions as a whole is not addressed. Only self-hosted runners plus a
  repository mirror would address it, and that is out of proportion here.

**Neutral**
- Cost: a few hundred MB of storage and transfer well under ECR Public's anonymous
  data-transfer allowance. Expected cost is about 0 EUR per month; to be confirmed against the
  current ECR Public price list before the registry is created (ADR-0054).
- Gates: `ci-artifact-fetch-via-resolver` (new; `advisory` until the sweep closes, then
  `enforced`) and `ci-artifact-lock-integrity` (lock schema, unique names, sha256 format;
  `enforced` from the start).

## Delivery check

- `git grep -nE 'releases/download|\.github\.io' -- .github ':!*.md'` prints nothing outside
  the resolver and the lock.
- `.github/gates/gates.yaml` has `id: ci-artifact-fetch-via-resolver` with `mode: enforced`.
- The latest mirror workflow run on `main` is `success`, and
  `oras manifest fetch public.ecr.aws/<namespace>/<name>:<version>` succeeds for every lock
  entry.

## Compliance impact

- PCI DSS: not applicable — CI tooling only; no cardholder data is involved.
- DORA: not applicable — no specific requirement is claimed. The change reduces CI's
  dependency on a single third-party provider for build tooling.
- GDPR: not applicable — the mirror holds public open-source artifacts, no personal data.
- PSD2: not applicable — no payment interface changes.
- CNB: not applicable — no regulatory reporting or banking system change.

## References

- ADR-0030 — supply-chain security and SSDLC hardening.
- ADR-0054 — FinOps guardrails and cost audit.
- ADR-0060 — CI applies the platform OpenTofu through OIDC.
- ADR-0175 — data residency (scope: customer data).
- ADR-0363 — remote Gradle build cache outside AWS, fail-open.
- #15161, #15211 — dependency-graph plugin version inherited from setup-gradle.
