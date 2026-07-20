---
date: 2026-06-19
decision-status: accepted
delivery-status: partial
authors: [@JiRaska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [secrets, security-ops, database]
summary: "Static per-service credentials give way to OpenBao dynamic database credentials plus a tiered CronJob rotator for OIDC, JWT and external tokens, so rotation stops depending on manual engineer action."
---

# ADR-0099 — Automated secret rotation: OpenBao dynamic credentials + CronJob rotator

**Implementation note (Tier 1 + Tier 2 scripts — shipped 2026-06-30, PR feat/secret-rotation):**
All script and configuration bugs fixed; CronJobs un-suspended. One out-of-band
operator step remains before Tier 1 fires successfully: §3 in runbook 0006 (create
`vault_admin` Postgres role per-service and populate the `openbao-db-admin-passwords`
Secret). Tier 2 rotates OIDC/JWT gracefully (WARN+skip) until §5 (KV tree bootstrap)
is complete. ESO extension to `database/creds/*` (runbook 0006 §4) is also required.

Changes shipped in this PR:
- `dynamic-db-credentials.yaml` — fixed `connection_url` host (`<svc>-service-rw` →
  `<svc>-db-rw`, verified from CNPG manifests); added `password` parameter via
  per-service env var from `openbao-db-admin-passwords` Secret; corrected DB names.
- `db-rotation-job.yaml` — removed `suspend: true`; added `envFrom` Secret refs for
  `vault_admin` passwords (one key per service).
- `secret-rotator-cronjob.yaml` — removed `suspend: true`; fixed all 4 `secret/`
  KV path references to `openbank/` (KV v2 mount, verified from clustersecretstore.yaml).
- Per-service `db-dynamic-externalsecret.yaml` created for all 7 Phase 1 services
  (notifications, audit, balances, fx-service, accounts, payments/transaction, ledger)
  with `refreshInterval: 1h` and `secret.reloader.stakater.com/match: "true"`.
- `docs/runbooks/0006-openbao-dynamic-db.md` — extended with §3 (vault_admin bootstrap),
  §4 (ESO policy extension), §5 (KC/JWT KV tree), and verify commands.

Tier 3 / Phase 3 (audit outbox + admin-UI Credential Hygiene panel) tracked as
follow-up. Tier 2 Kafka SASL rotation is deferred (Kafka uses mTLS per ADR-0137).

**Relates to:** ADR-0034 (OPA unified authz, covers the OpenBao trust model), ADR-0037 (audit
outbox), ADR-0027 (cloud-agnostic substrate), ADR-0029 (governance as code), ADR-0059 (gated CD)

## Context

OpenBank runs ~30 `openbank-*-service` microservices. Each service carries its own credential
surface: a dedicated PostgreSQL user, a Kafka SASL credential, an OIDC client secret in Keycloak,
and service-to-service JWT signing keys. Additionally, the platform depends on a small set of
external API tokens (Slack webhooks, Groq API key, ECR push credentials). All of these are
currently **static** — credentials are generated once, written to OpenBao (our self-hosted OpenBao
fork of Vault, ADR-0034/infra), and rotated only when an engineer does it manually, if at all.

**OpenBao history.** The OpenBao instance was completely rebuilt in June 2026 following a
recovery-key loss incident (gen-root returned HTTP 405 — deprecated in v2.5.3). Recovery required
break-glass access via AWS Secrets Manager, followed by a full KV + auth + role reconstruction.
The rebuild confirmed that the fleet's entire credential store is sourced from a single OpenBao KV
backend with no expiry or rotation enforcement. Static KV secrets are operationally convenient
but carry compounding risk: a leaked credential stays valid indefinitely, rotation is undocumented,
and compliance evidence is informal or absent.

**Current delivery path.** Kubernetes secrets are projected from OpenBao by External Secrets
Operator (ESO). `ClusterSecretStore` objects point at the OpenBao KV mount; `ExternalSecret`
resources specify the path and sync interval. This layer works correctly and is kept in scope as
the *delivery* mechanism — ESO is not being replaced; we are adding a *rotation engine* upstream
of it.

**PostgreSQL specifics.** CloudNativePG (CNPG) manages all PostgreSQL clusters. CNPG exposes a
`PasswordEnforcement` reconciliation loop that can accept externally-managed password credentials
and roll them to the Postgres user without a cluster restart. OpenBao's `database` secrets engine
supports PostgreSQL natively, issuing short-lived dynamic credentials (username + password) on
demand for each lease rather than storing a long-lived static password.

**What is already handled and not in scope.** TLS certificates are auto-rotated by cert-manager
(wildcard via ACME/Let's Encrypt and in-cluster CA) — no rotation gap exists there. Keycloak
realm signing keys are managed internally by Keycloak — not in scope. External API tokens (Slack
webhooks, Groq, ECR) are third-party-managed and cannot be rotated programmatically from within
the cluster — these are documented in a manual runbook and excluded from this ADR.

**Compliance gap.** Banking regulation and card-industry standards require periodic credential
rotation for application accounts:

- PCI DSS v4.0 Req. 8.2.4: passwords/passphrases for application accounts must be changed at
  least every 90 days.
- CNB Vyhl. č. 163/2014 §25: access management must include periodic credential review and, where
  technically feasible, automated rotation.
- DORA EU 2022/2554 Art. 9: ICT security requires controls over authentication credentials as a
  foundational ICT risk measure.

The current manual rotation posture does not satisfy these requirements: there is no evidence of
rotation frequency, no audit trail of rotation events, and no enforcement mechanism.

## Decision

We will implement automated credential rotation in **three phases** across two tiers.

### Tier 1 — Dynamic secrets for CNPG PostgreSQL via OpenBao database engine

OpenBao's `database` secrets engine will be enabled and configured with a PostgreSQL plugin
connection for every CNPG cluster in the fleet. Instead of a static username/password stored in
KV, OpenBao will generate short-lived, per-lease credentials on demand.

Parameters per service database role:
- **TTL:** 24 hours — the lease is renewed automatically by ESO before expiry.
- **Max TTL:** 72 hours — forces a full credential cycle even if renewals have been uninterrupted,
  bounding the blast radius of a leaked credential to at most three days.
- OpenBao role name convention: `<service>-db-vault-role`.

ESO's `ExternalSecret` for each service is reconfigured to reference the dynamic path
(`database/creds/<service>-db-vault-role`) rather than the KV path. ESO re-fetches on TTL
approach and updates the Kubernetes secret. CNPG's `PasswordEnforcement` loop then reconciles the
new password to the Postgres user without disrupting active connections.

### Tier 2 — Rotated static secrets (OIDC client secrets, JWT signing keys) via CronJob

A `secret-rotator` CronJob is deployed to the `openbank-platform` namespace. It runs on a
**weekly schedule (Sunday 02:00 UTC)** — well within the 90-day PCI DSS requirement and outside
the primary banking-hours window. The CronJob is an in-cluster workload using the existing
OpenBao Kubernetes auth role; it holds no standing credentials of its own.

**OIDC client secret rotation.** For each Keycloak OIDC client registered to an OpenBank service,
the rotator calls the Keycloak Admin API (`POST /realms/<realm>/clients/<id>/client-secret`) to
generate a new secret, writes the new value to the corresponding OpenBao KV path, then triggers an
ESO refresh (via annotation `force-sync` on the `ExternalSecret`). After the sync completes it
verifies a liveness probe on the affected service before marking the rotation as successful.

**JWT signing key rotation.** JWT signing keys (used for service-to-service trust) are rotated
with a **7-day overlap window**: the new key is written and the old key is kept valid for seven
additional days to allow in-flight tokens issued under the old key to expire naturally. After the
overlap window the old key is removed. This avoids hard cutover failures across the fleet.

### Phase 1 — OpenBao database engine for CNPG credentials

Scope: all production and sandbox CNPG clusters (one per service, ~30 clusters). Deliverables:

1. Enable and configure the `database` secrets engine on OpenBao with a PostgreSQL plugin
   connection per cluster. Connection credentials use a superuser-equivalent Postgres role created
   exclusively for OpenBao to manage sub-roles; this role has `CREATEROLE` privilege only — it
   cannot read application data.
2. Create a `<service>-db-vault-role` for each service with the 24h/72h TTL parameters above.
3. Reconfigure the ESO `ExternalSecret` resources to reference dynamic credential paths and set an
   appropriate `refreshInterval` (1 hour — short enough to stay well ahead of the 24h TTL).
4. Validate with one non-money-path service (notifications or push-notifications) before fleet-wide
   rollout. Rollout proceeds service by service, ordered lowest-risk-first.
5. Remove the corresponding static KV paths once the dynamic path is verified for each service.

### Phase 2 — CronJob rotator for OIDC client secrets and JWT signing keys

Scope: all Keycloak OIDC client secrets registered in the `openbank` realm; all JWT signing key
pairs stored in OpenBao KV.

1. Build and publish the `secret-rotator` container image (distroless, non-root, no shell). Image
   is built via the standard service pipeline and signed with cosign (ADR-0027 image signing
   policy) before the CronJob references it.
2. Deploy CronJob with a dedicated Kubernetes ServiceAccount bound to a narrowly scoped OpenBao
   role: `kv/data/<service>/oidc-secret write`, `kv/data/<service>/jwt-keys write`, and
   Keycloak Admin API credentials via a separate KV path (read-only for the rotator, rotated
   manually on a separate schedule).
3. Implement liveness-probe verification as the final step of each rotation batch: the rotator
   polls the service's `/q/health/live` endpoint until a 200 is returned or a timeout (5 minutes)
   triggers an alert and a rollback to the previous secret version.
4. Implement the 7-day JWT key overlap window using OpenBao KV versioning: the prior version of
   the key remains readable via the versioned path; services load `versions=[current, current-1]`
   for verification.

### Phase 3 — Audit integration and compliance report in admin-UI

Every rotation event, regardless of tier, is emitted to the **audit-service outbox** (ADR-0037)
with the following envelope:

```
event_type     = SECRET_ROTATED
secret_path    = <openbao-path>
service        = <service-name>
triggered_by   = cron | manual | eso-renewal
tier           = dynamic | rotated-static
status         = success | failure
failure_reason = <string> | null
rotated_at     = <ISO-8601 UTC>
```

The `admin-UI` Compliance tab is extended with a **Credential Hygiene** panel that surfaces:
- Last rotation timestamp per credential class and service.
- Current credential age and time-to-next-rotation.
- Any rotation failures from the audit outbox (status=failure events).
- A per-service compliance status (PCI DSS Req. 8.2.4 green/amber/red based on age vs. 90-day
  threshold) derived from audit events, not from human-reported data.

The panel is read-only for all roles; rotation can be manually triggered by an `admin` role via a
button that calls a new `POST /api/v1/secret-rotator/trigger` endpoint on the rotator service
(authenticated via Keycloak, logged to audit outbox with `triggered_by=manual`).

## Alternatives considered

- **AWS Secrets Manager with managed rotation Lambdas** — AWS Secrets Manager provides native,
  managed rotation for RDS credentials via Lambda functions. Rejected: ADR-0027 mandates a
  cloud-agnostic substrate and prohibits coupling runtime behaviour to AWS-native services. Tying
  credential rotation to an AWS Lambda would make rotation unavailable outside AWS, contradict the
  substrate neutrality invariant, and introduce per-invocation Lambda cost at fleet scale (~30
  services × weekly = 1 560 Lambda invocations per year at minimum, excluding DB credential
  renewals). We already operate OpenBao; extending it is zero additional infrastructure cost.

- **ESO `ClusterSecretStore` push-secret with rotation interval** — ESO can be configured to
  refresh secrets on a polling interval and could theoretically call an external generator.
  Rejected as a standalone rotation engine: ESO is a *synchronisation* layer that pulls or pushes
  values already computed elsewhere. It has no built-in credential *generation* capability for
  Postgres or OIDC. ESO remains the correct delivery mechanism (this ADR uses it as such) but it
  is not the rotation trigger.

- **Manual rotation with improved runbook and calendar reminder (status quo improved)** — the
  operational burden of manual rotation could be reduced with a detailed runbook and a calendar
  event per service per quarter. Rejected: this remains human-dependent, produces no machine-
  readable audit trail, has no enforcement mechanism (the reminder can be dismissed), and fails
  the CNB §25 "where technically feasible, automated" standard. The June 2026 rebuild already
  demonstrated the fragility of manual operational processes around OpenBao.

- **HashiCorp Vault upstream** — OpenBao is a fork of Vault 1.14; upstream Vault has the same
  database secrets engine and CronJob patterns described here. Rejected: we already run OpenBao.
  Migrating back to Vault is unnecessary churn, adds licence exposure risk (BSL), and provides no
  capability we do not already have.

- **Per-service rotation sidecars** — deploying a lightweight rotation sidecar per pod that
  handles its own credential refresh. Rejected: 30 sidecars × N containers = significant resource
  overhead, complex lifecycle coordination with the main container, and no centralised audit trail.
  The CronJob + ESO model centralises rotation logic while keeping delivery per-service.

## Consequences

**Positive**
- PostgreSQL credentials (Tier 1) cycle every 24 hours. Even a completely leaked database password
  is invalidated within a day without any manual intervention.
- OIDC and JWT credentials (Tier 2) cycle weekly — reducing the window from "indefinitely" to at
  most 7 days (14 with overlap for JWT keys).
- Closes the PCI DSS Req. 8.2.4, CNB §25, and DORA Art. 9 compliance gaps with machine-readable
  evidence rather than informal process assertions.
- The audit outbox integration (Phase 3) makes compliance posture continuously visible in admin-UI
  without manual reporting.
- Eliminates the category of incidents where a credential stays valid long after a breach because
  no one noticed or scheduled a rotation.

**Negative**
- Phase 1 introduces dynamic Postgres credentials, which changes the effective username each time
  a new lease is generated. Applications must not hard-code the DB username in non-secret
  configuration (e.g. Flyway baseline users, CNPG connection pooler allow-lists). Each service
  rollout needs a pre-flight check of such references.
- The 7-day JWT overlap window in Phase 2 means two key versions must be trusted simultaneously
  during rollover. Token validation logic must load both the current and previous key version from
  the versioned OpenBao KV path. This is a small but non-trivial change to the libs JWT verifier.
- The `secret-rotator` CronJob is a new workload to operate, build, and maintain. A bug in the
  rotator can cause a rotation failure that cascades to a liveness-probe failure — recoverable
  (the previous secret version is still valid until TTL expiry for Tier 1, or until the next
  rotation window for Tier 2), but it requires on-call response.
- Phase 3 adds a new admin-UI panel and a new `POST` endpoint on the rotator — both require
  testing and carry a standard web-endpoint threat surface.

**Neutral**
- No change to ESO configuration for TLS-backed secrets (cert-manager paths) or Keycloak realm
  signing keys — those remain out of scope.
- External API tokens (Slack, Groq, ECR) remain in the manual runbook. The audit outbox panel
  (Phase 3) will surface these as `triggered_by=manual` only, making the gap visible.
- CNPG cluster configuration is unchanged; the `PasswordEnforcement` loop is already present in
  deployed CNPG versions in the fleet.

## Compliance impact

- **PCI DSS v4.0:** Req. 8.2.4 — passwords/passphrases for application service accounts must
  change at least every 90 days. Tier 1 (24h dynamic TTL) and Tier 2 (weekly CronJob) both
  satisfy this requirement. The audit outbox provides per-credential rotation timestamps as
  compliance evidence.
- **DORA (EU 2022/2554):** Art. 9 — ICT security policy must address credential lifecycle and
  access management controls; automated rotation is a direct implementation of that requirement.
  Art. 10 — ICT-related incident detection; the audit outbox rotation events feed the detection
  chain and allow anomaly alerting on rotation failures or unexpected rotation gaps.
- **GDPR (EU 2016/679):** Art. 32 — appropriate technical and organisational measures to ensure
  a level of security appropriate to the risk; automated credential rotation is a standard
  technical control for protecting data at rest and in transit accessed via database credentials.
- **PSD2:** not directly applicable to credential rotation.
- **CNB Vyhl. č. 163/2014 §25:** access management requirements include periodic review and,
  where technically feasible, automated rotation of application credentials. This ADR closes the
  "where technically feasible" gap: automated rotation is now feasible and implemented.

## References

- OpenBao database secrets engine: https://openbao.org/docs/secrets/databases/
- OpenBao PostgreSQL plugin: https://openbao.org/docs/secrets/databases/postgresql/
- External Secrets Operator documentation: https://external-secrets.io/
- CloudNativePG password enforcement: https://cloudnative-pg.io/documentation/current/
- ADR-0027 (cloud-agnostic substrate — rejects AWS-native rotation)
- ADR-0034 (OPA unified authz — OpenBao trust model and Kubernetes auth)
- ADR-0037 (audit outbox — rotation event schema)
- ADR-0029 (governance as code — compliance evidence as derived data)
- ADR-0059 (gated CD — Phase 3 rotator trigger endpoint is a money-path-adjacent write surface)
- PCI DSS v4.0 Requirement 8.2.4
- DORA EU Regulation 2022/2554, Articles 9 and 10
- CNB Vyhláška č. 163/2014 Sb., §25 (access management)
- June 2026 OpenBao rebuild post-mortem (gen-root 405 / recovery-key loss incident)
