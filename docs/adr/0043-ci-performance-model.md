---
date: 2026-05-31
decision-status: accepted
delivery-status: shipped
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [ci, testing]
summary: "CI warm-reuses the persistent runner: the Gradle daemon and compose test stack stay up across jobs and one build cache is shared pool-wide, with isolation from per-job database recreation and Valkey flush instead of teardown."
---

# CI performance model — warm-reuse the persistent runner

> **Amendment 2026-06-19 — implemented in `.github/workflows/_service-ci.yml` + gitops.**
>
> - **`--no-daemon` removed** (`_service-ci.yml:198`): Gradle daemon stays warm on persistent
>   runners between jobs; comment "No --no-daemon (ADR-0043)" confirms the change.
> - **Per-service `GRADLE_USER_HOME` isolation** (`_service-ci.yml:75`): each service job writes
>   to `../../.gradle-svc/<service>` — daemon warmth preserved, sibling-job `gradle --stop`
>   collision eliminated (the `Gradle build daemon disappeared` race fixed in this sweep).
> - **Shared remote build cache**: `openbank-infra/gitops/components/gradle-build-cache/` deploys
>   an nginx-WebDAV cache node; CI wires it in via `GRADLE_REMOTE_CACHE_URL` for the ARC in-cluster
>   runners. Unchanged modules across the 30-service fan-out are served from cache, not recompiled.
> - **`configuration-cache`** stays `false` (per ADR decision — Quarkus-plugin compat risk);
>   **ephemeral ARC scale-out** remains a deferred lever (ADR-0040 revisit trigger).
> - `openbank-build` runner pool: mixed ARC (in-cluster) + Hetzner VMs + Mac mini (ADR-0092).
>   The remote cache bridges the cross-host warmth gap that pure local caching cannot cover.

## Context

ADR-0040 chose **persistent, cache-warm self-hosted runners** (two Macs, EC2 cold
standby) as the durable, $0-GitHub-minutes execution model, with cache warmth named
as "the main lever for fast CI". The execution *recipe* in
`.github/workflows/_service-ci.yml`, however, is still written as if it ran on
ephemeral GitHub-hosted runners, so the fleet pays cold-start costs the ADR-0040
hardware was bought to avoid. On a ~30-module Quarkus/Kotlin monorepo where a change
to `openbank-libs` fans out to every service, those per-job fixed costs dominate wall
time. Four concrete leaks, all on `main` today:

1. **`--no-daemon` on a persistent host.** The build step runs
   `./gradlew … --no-daemon`, so every job throws away the warm Gradle daemon: cold
   JVM start + re-configuration of all 30 modules, every build. `--no-daemon` is an
   ephemeral-runner setting; it directly contradicts the ADR-0040 thesis.
2. **`compose down -v` per job.** Each job boots a full KRaft Kafka + Postgres +
   Valkey stack and tears it down with `down -v` at the end. A fan-out across 28
   services pays ~28 cold KRaft/Postgres boots — almost certainly the single largest
   per-job fixed cost — and the volume wipe means nothing is reused.
3. **No shared build cache across the pool.** Each Mac keeps only its own local
   Gradle cache; a module built on the Mac mini does not warm the MacBook. There is
   no cross-host cache, so the fan-out recompiles the same unchanged modules twice.
4. **`org.gradle.configuration-cache=false`.** Configuration of 30 modules is re-run
   on every invocation.

The goal is blunt: **build individual services as fast as possible, independently,**
without abandoning the ADR-0040 cost ($0 hosted minutes) or the local↔CI test-infra
parity that catches Quarkus Dev Services divergence bugs.

## Decision

**We will make the CI recipe match the persistent-runner model ADR-0040 already
chose: keep the Gradle daemon and the test-infra stack warm across jobs, share one
build cache across the whole runner pool, and reset only per-job *state* — never the
warm processes.** This ADR extends, and does not supersede, ADR-0040.

Concretely, in `.github/workflows/_service-ci.yml`:

1. **Warm Gradle daemon.** Drop `--no-daemon`. The persistent runner keeps the daemon
   (and its configured 30-module model) warm between jobs.
2. **Warm test-infra stack.** Replace boot-and-`down -v`-per-job with **boot-if-not-
   healthy + leave-running**. The slow processes (KRaft broker, Postgres) stay warm;
   a foreign/unhealthy stack is force-rebooted at the next job start (port-freeing
   logic retained). The stack is intentionally *not* torn down at job end.
3. **Per-job state reset, not teardown.** Because the stack is reused, isolation can
   no longer come from `down -v`. Each job instead **drop+recreates this service's
   dedicated `*_it` databases** (PG-version-agnostic: terminate backends → `DROP
   DATABASE IF EXISTS` → `CREATE`) and **flushes Valkey**, giving a clean schema/data
   slate on top of warm processes.
4. **Keep real-compose parity.** We continue to boot the *same*
   `openbank-infra/docker-compose.yml` the developer runs locally — we do **not**
   switch to Quarkus Dev Services or GitHub `services:` containers. The speed comes
   from reusing that real stack, not from lowering fidelity.

In `settings.gradle.kts`:

5. **Shared remote build cache.** Add an opt-in HTTP remote build cache
   (`gradle/build-cache-node`) gated on `GRADLE_REMOTE_CACHE_URL`. When the repo
   variable is set, every runner in the pool reads+writes one cache, so a fleet-wide
   fan-out resolves unchanged modules from cache instead of recompiling them per host.
   Unset ⇒ pure local-cache fallback, zero config for local dev and un-provisioned
   runners.

Deferred / tracked, not adopted now:

6. **`configuration-cache`** stays `false`. The Quarkus Gradle plugin has a history of
   configuration-cache incompatibility; flipping it globally risks breaking all ~30
   service builds at once. We track it as an experiment behind a per-service probe
   before any fleet flip.
7. **Executor scale-out.** Two Macs remain the parallelism ceiling for a fan-out. With
   warm caches, unchanged services become near-free, so the first lever is **more
   runner agents per host** (RAM-bounded). The second is the **ARC-on-EKS revisit
   trigger from ADR-0040** — a shared remote build cache removes that ADR's primary
   objection to ephemeral runners (cache loss per run), so if/when the platform EKS
   cluster exists and CI demand sustains queueing, ephemeral autoscaling pods become
   viable. This ADR does not adopt ARC; it records that lever #5 unblocks it.

## Alternatives considered

- **Quarkus Dev Services / Testcontainers reuse instead of compose.** Would also keep
  containers warm. **Rejected** as the primary mechanism: ADR-0040 and the
  `_service-ci` parity note deliberately boot the real compose stack (KRaft listener,
  Valkey `requirepass`, init volume) to stay 1:1 with local and catch Dev Services
  divergence. Reusing the *real* stack gets the speed without trading away fidelity.
- **Ephemeral ARC-on-EKS autoscaling now.** The "fastest on fan-out" answer via raw
  parallelism. **Deferred** (not rejected) per ADR-0040's economics and its explicit
  revisit trigger; the remote cache here is a precondition that makes it cheaper later.
- **Managed fast runners (Depot / Namespace / Blacksmith).** Built-in caching, high
  parallelism, trivial ops. **Rejected:** they bill per minute, violating the ADR-0040
  $0-hosted-minutes requirement.
- **Flip `configuration-cache=true` now.** Real config-time savings. **Rejected for
  now:** Quarkus-plugin compatibility risk across 30 modules; tracked as a gated
  experiment instead.

## Consequences

**Positive**
- Removes the dominant per-job fixed costs (cold daemon + ~28 cold KRaft/Postgres
  boots) the persistent hardware was meant to avoid — large wall-time win on fan-outs.
- One shared cache across both Macs: unchanged modules in a fan-out become cache hits.
- Still $0 GitHub minutes; still 1:1 local↔CI infra parity; per-service green/red
  status checks unchanged.
- Unblocks the ADR-0040 ARC revisit trigger by solving ephemeral cache loss.

**Negative**
- **Weaker per-job isolation than `down -v`.** Postgres (drop+recreate) and Valkey
  (flush) are reset per job, but the **reused Kafka broker retains topics/offsets**
  across jobs. Cross-service collision is unlikely (service-specific topic names), but
  this is the residual risk to watch when a service flips red after this change; the
  per-service status check localises it. A targeted topic reset can be added if needed.
- **Persistent-runner state accumulation** (already an ADR-0040 risk) increases: a
  warm stack can drift; the health-gated force-reboot is the safety valve.
- The remote build-cache-node is **new infra to run and trust** (a poisoned cache
  entry is a supply-chain concern, ADR-0030) — it must live on an isolated runner host
  with authenticated push.

**Neutral**
- No change to which services build (path-scoping retained) or to the cost model.
- Remote cache is inert until `GRADLE_REMOTE_CACHE_URL` is provisioned; this ADR lands
  the wiring, not the node.

## Compliance impact

- PCI DSS: not applicable (no cardholder data in CI execution).
- DORA:    **applicable** — CI is part of the ICT change pipeline (as in ADR-0040).
  Warm-reuse adds a runner-hygiene duty (health-gated reboot) already owned per
  ADR-0040; the build cache becomes an integrity-relevant component (see ADR-0030).
- GDPR:    not applicable (no personal data on runners).
- PSD2:    not applicable.
- CNB:     not applicable.

## References

- ADR-0040 — CI execution model and cost (persistent self-hosted runners; this ADR
  extends it).
- ADR-0030 — supply-chain security and SSDLC hardening (build-cache integrity, runner
  isolation).
- ADR-0027 — cloud-agnostic substrate / EKS direction (where a future ARC fleet lives).
- Gradle docs — Build Cache, `gradle/build-cache-node`, configuration cache.
