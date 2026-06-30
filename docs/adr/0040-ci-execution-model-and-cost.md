# CI execution model and cost

Date: 2026-05-30
Status: Accepted
Delivery-Status: Shipped
Author(s): Jiri Raska

## Context

CI on the (private) `JiRaska/open-bank-oss` repository has repeatedly stalled because
GitHub-hosted runners are billing-blocked: jobs abort at startup with "recent
account payments have failed or your spending limit needs to be increased" (see
the recurring incidents — a plan upgrade did not durably fix it). The requirement
is blunt: **CI must work, and we will not pay GitHub more for hosted runner
minutes.**

Forces at play:

- **Cost.** GitHub bills *hosted* runner minutes (private repos). GitHub does **not**
  bill Actions minutes for **self-hosted** runners — there you only pay for the
  compute you already own or rent.
- **Speed.** The fleet is ~28 Quarkus/Kotlin services. Build time is dominated by
  Gradle + Docker + JVM warm-up, so **cache warmth matters more than raw runner
  count**.
- **Scale of the team.** Small. Heavy Kubernetes-based autoscaling infrastructure
  is not obviously justified by the current CI volume.
- **Resilience (DORA).** CI is part of the ICT change pipeline; a single-runner
  outage stalls all merges. Availability and a fallback path matter.
- **Supply-chain integrity (ADR-0030).** Self-hosted runners execute arbitrary
  build code on hardware we own and place on our networks — host isolation and
  runner hygiene are security controls, not conveniences.

We already have: an AWS sandbox self-hosted runner in an isolated VPC (ADR-0027,
egress-only SG), and a newly added local **Mac mini** (macOS/arm64, Docker 28.5.2)
that is faster than the EC2 instance.

## Decision

**We will run CI on self-hosted runners as the durable execution model — persistent
(not ephemeral), cache-warm, right-sized to the team — and we will not adopt
Kubernetes-based runner autoscaling at this time.**

Concretely:

1. **All workflow jobs target a shared self-hosted label pool** (`[self-hosted,
   openbank-sandbox]`). Runners join the pool by carrying that label; no per-runner
   `runs-on` priority is attempted (GitHub Actions has no runner-priority concept —
   it assigns to the first idle runner matching the labels).
2. **The active pool is four runners — two macOS and two Linux EC2 — all online and
   busy** (**updated 2026-05-31**; this reverses the original "two Macs, EC2 cold
   standby" decision):
   - `Jiris-Mac-mini` — macOS **arm64** (`/Users/.../actions-runner`)
   - `Mac` (MacBook) — macOS **x64** (`/Users/.../actions-runner`)
   - `openbank-sandbox-runner` — Linux **arm64** (`/opt/actions-runner`)
   - `openbank-sandbox-x64-runner` — Linux **x64** (`/opt/actions-runner`)

   The EC2 Linux runners were **brought back online intentionally for build capacity**:
   the ~28-service Gradle/Docker matrix serializes badly on two hosts (see the Negative
   consequence below), so four concurrent jobs materially shortens fleet-wide changes.
   The pool is therefore **both OS-mixed (macOS + Linux) and arch-mixed (arm64 + x64)**.
   No `runs-on` priority is attempted (point 1) — GitHub assigns a job to the first idle
   runner carrying the labels, so **any job can land on any of the four hosts**. That
   portability assumption is exactly what point 3a must now guarantee across *all four*,
   not just the Macs. (Verified 2026-05-31 via
   `gh api repos/<owner>/<repo>/actions/runners`; the original cold-standby wording is
   superseded.) The **cost** of keeping that Linux capacity online — which would
   otherwise reintroduce exactly the GitHub-billing-class spend this ADR exists to
   avoid — is contained by running the Linux pair on **Spot**, not on-demand (point 8).
3. **Runners are persistent and keep warm caches** (Gradle build cache, Docker
   layers, npm). This is the main lever for fast CI and is the explicit reason we
   reject ephemeral-by-default runners here.
   - **3a. Workflows invoke security/lint tools as host CLIs, not Docker-container
     actions.** `ibiqlik/action-yamllint`, `ludeeus/action-shellcheck`,
     `aquasecurity/trivy-action` and `gitleaks/gitleaks-action` are Docker-container
     actions (or download to fixed `/tmp` paths) that do **not** execute on a macOS
     runner and break on a reused persistent host. We call the brew/host-installed
     binaries (`yamllint`, `shellcheck`, `trivy`, `gitleaks`) directly instead. This
     keeps every job OS-portable across the mixed pool — **but only if the binary is
     present on whichever host takes the job.** With the pool now four OS/arch-mixed
     hosts (point 2), a host-CLI job can land on any of them, so **every such tool must
     be installed on all four runners** (or the job pinned to a narrower label such as
     `[self-hosted, openbank-sandbox, macOS]`). This is not hypothetical: on 2026-05-31
     the `Trivy` job in `security.yml` failed `trivy: command not found` (exit 127)
     because it was scheduled onto a Linux runner where the brew-installed `trivy` was
     absent; it was fixed by installing `trivy` on both Linux runners to restore
     pool-wide tool parity. Treat host-CLI parity across **all** pool members as a
     standing runner-hygiene invariant. (Incident also recorded in ADR-0042, which
     depends on these checks being reliably green to make them branch-protection
     required.)
4. **Concurrency is sized to host RAM, not wished for.** A 28-module Gradle build
   with `-Xmx3g` per job plus the Docker compose test stack (postgres/kafka/valkey)
   consumes several GB per job; the number of runner agents per host is capped so
   concurrent jobs do not exceed available memory.
5. **Hosts are isolated and hardened.** The EC2 runner stays in its egress-only,
   isolated VPC. The Mac mini must be network-isolated (no lateral access to
   sensitive systems), hold no standing production credentials, and run the runner
   under a dedicated low-privilege account.
6. **`step-security/harden-runner` is omitted on self-hosted jobs** — it is an eBPF
   shim for ephemeral hosted runners and is redundant/unsupported here; egress is
   controlled at the host/infra layer instead.
7. **Path-scoped CI is retained** (build only changed Gradle modules) to keep the
   load on the modest runner fleet low.
8. **The Linux EC2 runners run on Spot, not on-demand — cost is the deciding
   constraint** (**added 2026-05-31**). The founding mandate of this ADR is "CI must
   work and we will not pay more for it"; bringing two on-demand EC2 hosts online 24/7
   for *bursty* CI would quietly betray that. So the Linux pair is provisioned as
   **Spot, Graviton/arm64-first** (`t4g`/`c7g` for the arm64 runner, a spot x64 type
   for the other) behind an Auto Scaling Group with **`capacity-optimized`** allocation
   to minimise interruptions. This turns "standing on-demand spend" into **near-zero
   variable spot spend** (small instances at ~70–90 % off list), keeping us in the
   spirit of the $0-extra mandate while still gaining the parallelism of point 2.
   - **8a. Spot runners are *overflow*, not warm-cache hosts.** Point 3's warm-cache
     speed thesis is carried by the **two owned Macs** (the persistent fast path). A
     spot interruption *terminates* the instance, so its Gradle/Docker cache is cold on
     replacement — acceptable precisely because the Macs hold cache warmth and any
     interrupted job re-queues onto them. (Optionally the Gradle cache lives on a
     persistent EBS volume that re-attaches to the replacement, recovering some warmth.)
   - **8b. Interruption is handled, not feared.** Spot gives a ~2-minute termination
     notice; the runner agent re-registers via its systemd service on the replacement's
     boot, and CI jobs are **retryable**, so an interrupted build fails and re-queues
     (onto a Mac or the new spot host) rather than corrupting state. This is the same
     resilience the four-host pool already buys us (point 2 / DORA fallback).

The public-vs-private repository question is **explicitly out of scope** for this
ADR (see Alternatives). ARC/EKS autoscaling is **deferred**, not rejected, with a
concrete revisit trigger.

## Alternatives considered

- **Keep GitHub-hosted runners (`ubuntu-latest`).** Simplest, zero ops, clean
  isolation per job. **Rejected:** directly violates the cost requirement and is the
  source of the recurring billing block. (Free-tier minutes do not cover this fleet
  on a private repo.)

- **Actions Runner Controller (ARC) on EKS — ephemeral, autoscaling, scale-to-zero.**
  The "cloud-native" answer; matches ADR-0027's EKS direction. **Deferred, not
  chosen now**, because: (a) the EKS control plane costs ~$73/mo and does **not**
  scale to zero, so a cluster spun up *for CI alone* is more expensive than one
  Mac mini or a small EC2; (b) **ephemeral runners lose the Gradle/Docker/npm cache
  every run**, making builds slower — the opposite of the speed goal — unless we
  also stand up a remote build-cache/registry-cache, which is more moving parts;
  (c) controller + runner-image + scaling ops is real engineering time not justified
  by current CI volume. **Revisit trigger:** the platform EKS cluster already exists
  for other reasons *and* CI demand outgrows the four persistent hosts (sustained
  queueing). At that point CI runners are just additional pods and the economics flip.

- **Make the repository public to get free, unlimited GitHub-hosted runners.**
  Public repos get free standard hosted runners and free CodeQL/GHAS — this would
  eliminate the cost problem and all self-hosted ops. **Out of scope / rejected as a
  CI-cost lever:** open-sourcing a banking codebase is a strategic, effectively
  irreversible decision (history is exposed permanently) that must be driven by
  product/legal/security readiness — *not* by a desire for cheaper CI. It also
  carries a security trap that interacts with this ADR: **self-hosted runners on a
  public repo are unsafe** (fork PRs can execute arbitrary code on our hardware), so
  "go public" and "self-hosted" are mutually exclusive without further isolation.
  If the project later goes public on its own merits, this ADR should be revisited —
  the right answer there is free hosted runners, not self-hosted.

## Consequences

**Positive**
- **$0 GitHub Actions minutes.** The recurring billing block stops gating merges.
- **Faster builds** from warm Gradle/Docker/npm caches on persistent runners.
- **~~$0 standing compute~~ → near-zero variable spot spend (updated 2026-05-31).**
  The two Macs are owned (no spend); the two EC2 Linux runners are now running rather
  than stopped, but on **Spot, Graviton-first** (point 8) — small instances at ~70–90 %
  off on-demand, so the bill is a few dollars/month of *variable* compute, not the
  $30–60/mo a 24/7 on-demand pair would cost. This keeps us materially inside the
  founding "no extra spend" mandate while still gaining the parallelism; it is far
  cheaper than the ARC/EKS alternative below (whose EKS control plane alone is ~$73/mo
  and does not scale to zero).
- **A fallback path with real parallelism** — four hosts (two Mac + two Linux), so a
  single-host outage does not stall merges and the matrix runs **up to four jobs
  concurrently** rather than two.

**Negative**
- **Concurrency raised to four, still bounded (updated 2026-05-31).** Bringing the two
  EC2 Linux runners online lifts the ceiling from two to four concurrent jobs, easing
  the previous serialization of the ~28-service matrix; but four modest hosts still
  queue a true fleet-wide change, and the heterogeneity adds the host-CLI-parity
  obligation (point 3a) and the small variable spot cost (point 8) as the price of
  that capacity.
- **Spot interruptions cost the Linux runners their cache and can re-queue jobs.**
  Choosing Spot (point 8) for cheapness means the two Linux hosts can be reclaimed on
  ~2-minute notice: an in-flight job re-queues and the replacement starts cache-cold.
  We accept this because the Macs are the warm-cache fast path and jobs are retryable
  (point 8a/8b); the trade is "occasional re-run / colder Linux builds" for "~70–90 %
  off the EC2 bill", which the cost mandate makes the right call.
- **Operational ownership.** We patch, monitor, and keep the runner agents healthy;
  this is now our responsibility, not GitHub's.
- **Persistent-runner hygiene risk.** State accumulates between jobs; we mitigate
  with workspace cleanup and least-privilege, isolated hosts, but it is weaker
  isolation than per-job ephemeral runners.
- **Home/office-network exposure** for the Macs unless deliberately isolated.

**Neutral**
- `runs-on` carries no priority; whichever of the **four** runners is idle first takes
  the job — none is guaranteed "preferred".
- The pool is **OS-mixed (macOS + Linux) and arch-mixed (arm64 + x64)**. Jobs stay
  portable because lint/security tools are host CLIs (installed on all four — point 3a)
  and container images are multi-arch; CodeQL is the known exception (no linux/arm64
  support, and gated off while private anyway).

## Compliance impact

- PCI DSS: not applicable (no cardholder data in CI execution).
- DORA: **applicable** — CI is part of the ICT change pipeline. The four-host pool
  provides fallback and parallelism; runner outage handling, patching, and host-CLI
  parity (point 3a) become documented operational duties.
- GDPR: not applicable (no personal data on runners).
- PSD2: not applicable.
- CNB: not applicable.

## References

- ADR-0027 — cloud-agnostic substrate / EKS direction (self-hosted sandbox runner).
- ADR-0030 — supply-chain security and SSDLC hardening (runner integrity).
- ADR-0010 — Kubernetes + ArgoCD GitOps (where a future ARC fleet would live).
- ADR-0042 — enforce branch protection on `main` (depends on these CI checks being
  pool-wide reliable to make them required; records the same 2026-05-31 Trivy incident).
- GitHub Docs — "About self-hosted runners" (no Actions-minute billing); "Usage
  limits and billing" (hosted-minute charges on private repos).
- Pool snapshot 2026-05-31 — `gh api repos/<owner>/<repo>/actions/runners`: four online
  runners (`Jiris-Mac-mini` macOS/arm64, `Mac` macOS/x64, `openbank-sandbox-runner`
  Linux/arm64, `openbank-sandbox-x64-runner` Linux/x64).
