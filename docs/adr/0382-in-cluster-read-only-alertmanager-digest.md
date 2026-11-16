---
date: 2026-11-16
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [observability, security, networking, kubernetes]
summary: "The daily standing-critical digest runs in-cluster behind a GET-only relay, since Alertmanager accepts unauthenticated POST /silences; envelopes reach the weekly CI review via S3 with Pod Identity and OIDC roles, no stored credential."
followup: "#6432 — owner applies alert-digest.tf, sets ALERT_DIGEST_READER_ROLE_ARN, observes the first in-cluster envelope and one weekly fold, then retires the GitHub digest workflow"
---

# ADR-0382 — In-cluster read-only producer for the standing-critical alert digest

## Context

ADR-0241 D3 made the daily standing-critical digest the feed of the weekly RCA review. It never
worked: the digest ran in GitHub CI, and Alertmanager is reachable from outside the cluster only
through the admin-ui cookie gate (ADR-0234), which a runner cannot pass (#6432). The weekly review
then reported "no observations" for whole weeks (#16022). Admitting the in-cluster ARC runner pool
to Alertmanager was considered and rejected: Alertmanager has **no authentication of its own** and
its API accepts `POST /api/v2/silences`, so any pod that can open :9093 can mute the estate. The
only thing protecting that write API is `alertmanager-ingress-allow-list`, an L4 NetworkPolicy that
cannot tell a GET from a POST — admitting a reader there admits a writer.

The questions that had to be answered before deciding:

1. *Is this already decided?* ADR-0241 decides **that** a digest exists and what it computes; it
   is silent on where it runs and how it reaches Alertmanager. ADR-0234 decides the edge gate for
   humans. Nothing in DIGEST.md decides how a machine reads an unauthenticated in-cluster API that
   also accepts writes. ADR-0316 and the argocd-sync-verifier CronJob established the in-cluster
   producer pattern this follows, not the read-only boundary.
2. *What breaks if we do nothing?* The weekly RCA stays a review of nothing, and the
   zero-standing-criticals objective of ADR-0241 has no measurement.
3. *Which layer actually prevents the write?* Answered below, and tested by the case it must
   reject (`openbank-infra/scripts/alert_digest_readonly_test.py`).
4. *What new standing credential does it create?* None — that is a constraint on the decision.
5. *How is it proven live?* By an envelope object in S3 and a weekly run whose observation count
   is non-zero; not by a green CronJob.

## Decision

We will produce the digest **in-cluster**, in its own namespace `alert-digest`, reading Alertmanager
only through a dedicated **GET-only relay** (`observability/alertmanager-read-proxy`), and hand the
envelope to CI through **S3**.

- **The relay** is a separate pod in `observability` (so Alertmanager's existing same-namespace rule
  admits it — no change to the Alertmanager allow-list). It never forwards the caller's method,
  path, query, headers or body: the only upstream request it can construct is a GET of one constant
  URL (`/api/v2/alerts?filter=severity="critical"`). Every other verb is answered 405 locally
  without opening an upstream connection; every other path is 404. Its egress is DNS and
  Alertmanager :9093 only. Its ingress (fleet NetworkPolicy generator) admits `alert-digest`,
  `admin-ui` and same-namespace pods on :8080 — harmless, because whoever is admitted can only
  ever cause that one GET.
- **The digest CronJob** runs the committed `.github/scripts/standing-critical-digest.py`, shipped
  as a ConfigMap mounted read-only (a test fails on any byte of drift from the canonical script;
  nothing is fetched at runtime), against the relay (`ALERTMANAGER_AUTH=none`, an explicit opt-out; a merely
  missing token still fails closed). Its egress is DNS, the relay :8080, and TCP 443 to non-private
  addresses (S3, Slack).
- **Which layer prevents the write.** For the digest pod, the L4 drop: its namespace is not in
  `alertmanager-ingress-allow-list` and its own egress reaches no :9093, so a direct POST never
  arrives. The relay is the only path, and it is L7 GET-only, so a relayed POST is impossible by
  construction. The relay is a separate pod precisely because a sidecar would share the digest
  pod's network identity and could be bypassed.
- **Hand-off.** The CronJob writes `observations/<date>/alert-observation.json` with an EKS Pod
  Identity role granting only `s3:PutObject` on that prefix; `alert-rca-weekly.yml` reads it with a
  GitHub OIDC role (main, that workflow only) granting only Get on that prefix (no List). The bucket is versioned, so an
  overwrite by the writer stays recoverable. No PAT, GitHub
  App key or static AWS key exists on either side.
- The GitHub `standing-critical-digest.yml` stays dispatch-only until the in-cluster path is proven
  live; the weekly workflow's S3 step is inert until the reader role variable is set.

## Alternatives considered

- **Admit the ARC runner namespace to Alertmanager** — rejected (#6432): every CI job, including
  PR jobs running contributed code, could POST silences.
- **GET-only sidecar in the digest pod** — rejected: NetworkPolicy selects pods, so the main
  container would share the sidecar's identity and could bypass it once admitted.
- **Envoy Gateway route with a method match** — workable, but it puts a machine read path on the
  public edge's Gateway and its extAuth SecurityPolicy, which exist for humans (ADR-0234/0324), and
  needs a credential for the machine. A pod-local relay keeps the path entirely in-cluster.
- **Push the envelope via the GitHub API with a GitHub App installation token** — works and keeps
  GitHub as the only store, but the App's private key is a long-lived secret held in the cluster,
  and an installation token with `contents:write` on this repo can write far more than an envelope.
  The S3 roles are each one verb on one prefix and have no secret to leak.

## Consequences

**Positive**
- The weekly RCA gets a real observation per day without CI ever touching the cluster.
- No new standing credential; no change to Alertmanager's allow-list.
- The relay's code is extracted from its ConfigMap by the test, so the tested code is the run code.

**Negative**
- One more always-on pod (a few MiB) and one more namespace and Application to own.
- The digest runs `main`'s script unpinned (same trade as argocd-sync-verifier): a bad merge to the
  script breaks the next run, loudly.
- The 443 egress rule cannot name FQDNs, so it is "any public address", podSelector-scoped.

**Neutral**
- The Slack copy is optional at the pod (the OpenBao path may be unseeded); its absence is printed
  on every run. The envelope, not Slack, is the control.

## Compliance impact

- PCI DSS: not applicable — no cardholder data in alert labels or the envelope.
- DORA:    ICT incident-management evidence: restores the daily standing-critical observation the
  weekly RCA review depends on.
- GDPR:    not applicable — alert labels carry no personal data.
- PSD2:    not applicable — no payment-service interface changes.
- CNB:     not applicable — no supervisory reporting changes.

## References

- ADR-0241 (zero standing critical alerts), ADR-0234 (identity-aware tools gate)
- #6432, #16022
- `openbank-infra/gitops/components/observability/alertmanager-read-proxy.yaml`
- `openbank-infra/gitops/components/alert-digest/`
- `openbank-infra/aws/envs/sandbox-platform/alert-digest.tf`
- `openbank-infra/scripts/alert_digest_readonly_test.py`
