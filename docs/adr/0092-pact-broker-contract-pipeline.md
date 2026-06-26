# ADR-0092 — Pact Broker: broker-backed contract pipeline (enacts ADR-0063 follow-up)

**Status:** Accepted
**Date:** 2026-06-15
**Author:** @JiRaska

> **Amendment 2026-06-19 — Pact Broker live.** Broker deployed at `pact.open-bank.tech` with
> basic-auth ingress (OpenBao KV-backed credentials, ESO sync). CNPG `pact-broker-db` running.
> 4 contracts active and verified (balance→ledger REST, transaction→ledger REST,
> account←party `PARTY_CREATED`, balance←account `AccountCreated`). CI publishes pacts
> post-build; provider verification results are published back. `can-i-deploy` gate wired for
> non-money-path services. ADR-0063 git-pact storage retired for covered pairs.

**Relates to:** ADR-0063 (Consumer-driven contract testing with Pact / git-pact),
ADR-0029 (Governance-as-code: version/release/catalog axes), ADR-0048 (Two version axes),
ADR-0027 (Cloud-agnostic substrate, everything stateful in-cluster OSS),
ADR-0037 (Domain = namespace), ADR-0053 (Ephemeral scale-to-zero ARC runners),
ADR-0056 (admin-UI BFF is the sole public browser→cluster path)

## Context

ADR-0063 introduced consumer-driven contract testing with Pact and **deliberately chose
git-pact storage** (committed `pacts/*.json`, providers verify via `@PactFolder("../pacts")`)
over a broker. Its rationale, verbatim:

> A Pact Broker adds operational burden and a cross-service deploy gate before the team has
> proven the workflow. In a monorepo where consumer and provider co-evolve in a single PR, git
> storage is sufficient. **Migration to a broker is the natural follow-up when services need
> independent deploy cadences and a `can-i-deploy` gate.**

The workflow is now proven: 4 contracts are live and green (balance→ledger REST,
transaction→ledger REST, account←party `PARTY_CREATED`, balance←account `AccountCreated`).
The capability git-pact structurally cannot provide is a **`can-i-deploy` gate**: a check, at
deploy time, that the exact version being shipped has a verified contract against the versions
of its dependencies already in the target environment. With git-pact, "the contract is in the
repo" is the only signal — there is no record of *which provider version verified which
consumer version in which environment*, so an unverified contract change can ship.

This ADR records the decision to enact the ADR-0063 follow-up: stand up a Pact Broker and move
the contract pipeline (publish → verify → `can-i-deploy`) onto it.

## Decision

We will run a **self-hosted Pact Broker** (`pactfoundation/pact-broker`, OSS) as a stateful
service deployed via GitOps, and wire CI to publish pacts, publish provider verification
results, and gate deploys on `can-i-deploy`.

### Deployment (ADR-0027 / ADR-0037)

- Broker runs in its own namespace `pact-broker` (infra plane), backed by a single-owner CNPG
  Postgres `pact-broker-db` (`gitops/components/pact-broker/`). Everything stateful stays
  in-cluster OSS — no PactFlow SaaS, no managed DB.
- **No backups** on `pact-broker-db` (unlike money-path DBs): pact and verification data are
  fully reproducible — every CI build republishes them. This keeps the broker out of the
  backup-coverage obligation and at ~1Gi gp3 (< $1/mo total).
- Basic-auth credentials (write + read-only) live in OpenBao KV `openbank/pact-broker`, synced
  to the namespace by ESO (`ClusterSecretStore vault-kv`), seeded out-of-band via
  `scripts/seed-vault-gaps.sh` (GATE 2: values never in git).

### Exposure (ADR-0056 — documented exception)

- The `openbank-build` runner pool is **mixed**: in-cluster ARC pods (ADR-0053) *and* external
  runners (Hetzner VMs, a Mac mini). The external runners cannot reach the broker over cluster
  DNS, so an in-cluster-only broker cannot serve the CI pipeline. The broker is therefore
  exposed via an nginx **Ingress** at `https://pact.open-bank.tech` (cert-manager Let's Encrypt,
  external-dns), reachable by every runner for publish / verify / can-i-deploy.
- This is a **deliberate, documented exception** to ADR-0056's "no public pre-auth surface"
  default. It is acceptable because: (1) the broker is gated by HTTP **Basic auth** on every
  path except the DB-less `/diagnostic/status/heartbeat` (creds in OpenBao, synced by ESO);
  (2) the payload is API **contracts**, not customer data; (3) it is a machine/CI endpoint, not
  a browser admin console. Humans can still use `kubectl port-forward`
  (`scripts/pact-broker-local.sh`); the read-only Basic-auth creds gate the UI either way.
  Tighter options (nginx IP-allowlist to runner egress, or pinning the contract jobs to
  ARC-only runners) were considered and deferred — revisit if the external runners are retired.

### CI pipeline (ADR-0029 Layer B)

- **Consumers publish** their generated pacts to the broker in `_service-ci.yml` after the
  build, keyed by `--consumer-app-version=<git-sha> --branch=<ref>`.
- **Providers verify against the broker** (`@PactBroker` replaces `@PactFolder`) and publish
  verification results back (`pact.verifier.publishResults=true` in CI). Pending + WIP pacts are
  enabled so a brand-new consumer contract does not hard-fail an unrelated provider build — the
  strict enforcement happens at the gate, not in per-service CI.
- **`can-i-deploy` gate** is a new job in `auto-deploy.yml`, between `build-push` and
  `gitops-pr`: for each changed service it runs `pact-broker can-i-deploy --pacticipant <svc>
  --version <git-sha> --to-environment sandbox`. A failure means the gitops image-tag PR is
  never opened — the same fail-closed shape as the existing Trivy image-scan gate. Because the
  publish, verify and gate run as independent workflows on the same merge SHA, the gate uses
  `--retry-while-unknown` to wait for verification results rather than racing them.

### git-pact retirement (phased)

`pacts/*.json` and `pact.rootDir` are **retained as a fallback** through the rollout. Once the
broker pipeline is demonstrably green across the fleet, a follow-up issue removes the committed
pacts, the `pact.rootDir` system property, and the `@IgnoreNoPactsToVerify` local-dev escape.
This honours ADR-0063's "keep git-pact as fallback" until the broker is load-bearing.

## Consequences

- **Positive:** a real `can-i-deploy` gate; per-version/-branch/-environment contract history
  and a browsable matrix; the broker removes git-pact's one-pact-per-consumer/provider-pair
  filename-collision limit (V3 file storage); marginal cost < $1/mo.
- **Negative / operational burden (the cost ADR-0063 flagged):** one more stateful component and
  CNPG cluster to operate; CI gains publish/verify/gate steps and a new fail-closed surface;
  provider verification now needs the broker reachable (mitigated for local dev by
  `@IgnoreNoPactsToVerify` + the retained `pacts/` for offline reading).
- **Money-path:** the provider migration touches `src/test` + `build.gradle.kts` only in
  ledger/party/account (no `src/main`), so it triggers no version bump (ADR-0048) and no threat
  model (ADR-0030); it still requires 2 approvals as money-path PRs.
- `rules.yaml: api_change` stays `advisory`; the broker does not change the gate's severity, it
  makes the "consumer-driven contract test updated (Pact)" requirement *enforceable at deploy*.
