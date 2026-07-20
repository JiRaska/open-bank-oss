---
date: 2026-05-26
decision-status: accepted
delivery-status: partial
authors: [jiri.raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [secrets, infrastructure, crypto-keys]
summary: "HashiCorp Vault (delivered as OpenBao) is the single source of truth for secrets, providing dynamic short-lived database credentials, PKI and eventually HSM-backed keys instead of static config values."
---

# 7. HashiCorp Vault for secrets management

**Delivery note (updated 2026-07-16):**

> **Naming:** the deployed engine is **OpenBao** (the Linux Foundation fork), not HashiCorp Vault —
> see [ADR-0027](0027-cloud-agnostic-in-cluster-substrate.md). This ADR's title and body still say
> "Vault"; the decision it records is unchanged and the API is compatible, so the words are left as
> written rather than retconned. Read "Vault" as "OpenBao" throughout.

- **Secrets infrastructure** — ✅ Shipped: OpenBao deployed via `openbank-infra/` with ArgoCD;
  auto-unseal via AWS KMS (`alias/openbank-vault-unseal`, `openbank-infra/gitops/apps/openbao.yaml`);
  External Secrets Operator projects secrets into K8s Secrets for steady-state service operation.
- **Dynamic database credentials** (per-pod Postgres, TTL ≤ 24h) — ✅ **Shipped** (corrected
  2026-07-16; this line previously read "Pending: services consume static credentials … database
  secrets engine not yet wired", which had been false since [ADR-0099](0099-automated-secret-rotation.md)
  Tier 1 landed). Seven components consume short-lived credentials from the OpenBao database secrets
  engine via the dedicated `vault-db` ClusterSecretStore — accounts, audit, balances, fx-service,
  ledger, notifications, payments (`openbank-infra/gitops/components/*/db-dynamic-externalsecret.yaml`).
  Lease TTL 24h, max 72h; ESO refreshes hourly to stay ahead of it. The remaining services still use
  static credentials, so the *rollout* is partial — but the engine is wired, and ADR-0099 owns the
  rollout from here.
- **PKI** — 🟡 Partial, and the two halves are worth separating:
  - The **OpenBao PKI engine is live and issuing**: a `pki-document-signing` root (RSA-2048, 10y)
    mints per-ceremony `client-signing` leaf certs with a 300s TTL and `no_store=true`
    (`openbank-infra/gitops/components/openbao/openbao-document-signing-pki.yaml`), and `pki-agent`
    issues AI-agent charter identity (ADR-0031 D3b).
  - **cert-manager ↔ OpenBao integration remains deferred.** cert-manager is deployed and does issue
    TLS, but from Let's Encrypt and a self-signed `openbank-ca`, not from OpenBao
    (`openbank-infra/gitops/components/platform/clusterissuer.yaml` — no Vault issuer). Services that
    need an OpenBao-issued cert call the engine directly.
- **HSM (FIPS 140-3)** for eIDAS QSeal and regulated signing keys — ⬜ Pending: deferred until the
  eIDAS signing use case (ADR-0094) activates in production. Note the deferral is circular — ADR-0094
  in turn defers to "KMS/HSM-backed" — and that signing today therefore claims only *advanced*
  (not qualified) eIDAS assurance. [ADR-0172](0172-cryptographic-key-management-and-lifecycle.md) owns
  the key-lifecycle picture, including this gap.

## Context

A banking platform handles a lot of secrets: database credentials, Kafka SASL passwords, eIDAS QSeal private keys, JWT signing keys, third-party API tokens. Mishandling any of these is a regulatory and security catastrophe.

Common bad approaches we explicitly reject:
- Secrets in env vars committed to git (gitleaks would block, but the practice itself is wrong).
- Secrets in Kubernetes Secrets stored unencrypted in etcd.
- Secrets in cloud-provider secret managers without portability.
- Per-service ad-hoc secret handling.

We need:
- A single source of truth for secrets.
- Dynamic credentials (rotated automatically, short-lived).
- PKI services (issue + rotate TLS certs).
- Audit trail of every secret access.
- Cloud-agnostic.

## Decision

**HashiCorp Vault** is the OpenBank reference secrets manager:

- Vault stores all production secrets.
- Services obtain secrets at startup via the **External Secrets Operator** in Kubernetes (which reads from Vault and creates K8s Secrets locally).
- Dynamic database credentials: Vault generates per-pod Postgres credentials with TTL ≤ 24h.
- PKI: Vault issues TLS certs via cert-manager.
- Auto-unseal: Vault uses cloud KMS (AWS KMS / GCP KMS / Azure Key Vault) as auto-unseal backend; operators choose per deployment.
- HSM (FIPS 140-3 Level 3) integration for eIDAS QSeal and other regulated signing keys in production.

Vault is in scope of `openbank-infra/`; helm charts and ArgoCD apps will be added by M2.

Operators MAY substitute Vault with their preferred secret manager (AWS Secrets Manager, GCP Secret Manager, Azure Key Vault) at deployment time; the External Secrets Operator supports all major backends. The reference architecture assumes Vault.

## Alternatives considered

- **Secrets in environment variables committed to git** — static secret values checked into the repository. Rejected as wrong practice in itself; gitleaks would block it, but the ADR rejects the approach regardless.
- **Secrets in Kubernetes Secrets stored unencrypted in etcd** — rely on K8s Secrets as the system of record. Rejected because the values sit unencrypted in etcd.
- **Cloud-provider secret managers used as the reference backend** — AWS Secrets Manager, GCP Secret Manager or Azure Key Vault as the platform default. Rejected for the reference architecture because it is not portable/cloud-agnostic; operators MAY still substitute one at deployment time via the External Secrets Operator.
- **Per-service ad-hoc secret handling** — each service manages its own secrets its own way. Rejected because the platform needs a single source of truth, dynamic short-lived credentials, PKI and an audit trail of every secret access.

## Consequences

**Positive**
- Secrets never live in git, env files, or unencrypted K8s Secrets.
- Dynamic credentials reduce blast radius of credential leaks.
- Audit log of every secret access.
- Cloud-agnostic; operators choose their backend.

**Negative**
- Vault is a critical dependency; its outage blocks new pod starts.
- Operational overhead: unseal procedures, replication, upgrades.

**Mitigation**
- Vault HA with auto-unseal eliminates manual unseal on restart.
- External Secrets Operator caches secrets in K8s Secrets so steady-state operation does not depend on Vault availability.
- Vault snapshot backups daily, tested restore quarterly.

## Compliance impact

- PCI DSS: not applicable — no cardholder data named in this ADR's scope.
- DORA:    DORA Art. 9 (protection of information assets) — secrets management explicitly required, as cited in this ADR's References.
- GDPR:    not applicable — secrets are credentials and keys, not personal data.
- PSD2:    not applicable — no payment initiation or account-access interface here.
- CNB:     not applicable — no CNB requirement is referenced in this ADR.

## References

- HashiCorp Vault documentation
- External Secrets Operator
- DORA Art. 9 (protection of information assets) — secrets management explicitly required.
