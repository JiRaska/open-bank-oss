# 7. HashiCorp Vault for secrets management

Date: 2026-05-26
Status: Accepted

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

## References

- HashiCorp Vault documentation
- External Secrets Operator
- DORA Art. 9 (protection of information assets) — secrets management explicitly required.
