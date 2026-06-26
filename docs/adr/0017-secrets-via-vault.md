# Move runtime secrets from application.yaml to HashiCorp Vault

Date: 2026-05-29
Status: Accepted
Author(s): jiri.raska

## Context

K1 from the 2026-05-28 audit:

> Hardcoded credentials v sourcu — `openbank-account-service/src/main/resources/application.yaml:33-34`
> (`password: openbank_secret`, redis `password: openbank_cache_secret`).
> The pattern repeats across all 28 services and is the highest-severity
> finding in the regulatory audit (NIS2 čl. 21, ISO 27001, internal SDLC).

ADR 0007 already mandates Vault as the single source of truth for secrets,
but the migration was never done — every service still carries plaintext
defaults in its application.yaml. The Op-ex 1 step of the SBOM follow-up
finishes the job.

The constraints we have to honour:

1. **Dev mode must still work** out of the box. New contributors clone, run
   `make up`, and have a working stack. No Vault unseal ceremony at the
   developer laptop.
2. **Prod / staging must NOT carry plaintext** in any of the YAMLs that
   ship in the JAR. Local dev defaults must be inert in those environments
   (rejected by Quarkus profile guard).
3. **Single mechanism per service**. We do not want some services on
   Vault, some on env vars, some on file mounts — that combinatorial
   maintenance defeats the purpose.

## Decision

We will adopt the **Quarkus Vault extension** (`quarkus-vault`, already
declared in `libs.versions.toml` but not wired) as the secrets source for
every service. Per-service `application.yaml` changes follow the same shape:

```yaml
# dev profile: Vault in dev mode, single-node, root token
"%dev":
  quarkus:
    vault:
      url: http://localhost:8200
      authentication.client-token: ${VAULT_DEV_ROOT_TOKEN_ID:openbank-dev-root}
      secret-config-kv-path: openbank/account-service

# prod profile: Vault production cluster, AppRole auth
"%prod":
  quarkus:
    vault:
      url: ${VAULT_URL}
      authentication:
        app-role:
          role-id: ${VAULT_ROLE_ID}
          secret-id: ${VAULT_SECRET_ID}
      secret-config-kv-path: openbank/account-service

# Reference secrets from Vault rather than inlining them. The keys below are
# stored under openbank/account-service in the Vault KV v2 backend.
quarkus:
  datasource:
    password: ${vault.db_password}
  redis:
    hosts: redis://:${vault.redis_password}@redis:6379

# %dev fallback (rejected outside dev) — Quarkus profile-guard.
"%dev":
  vault:
    db_password: openbank_local_dev_only
    redis_password: openbank_local_dev_only
```

`openbank-libs` adds a `BootstrapVerifier` that fails-fast at startup if it
detects any property literally equal to `CHANGE_ME_LOCAL_DEV_ONLY` or
matching `*_local_dev_only` outside the `%dev` profile. This prevents the
old defaults from accidentally shipping.

## Alternatives considered

- **K8s secrets (`Secret` + `envFrom: secretRef`)**. Works for prod but
  not for the developer-laptop dev mode (no cluster). Would need a parallel
  mechanism for dev → exactly the two-mechanism split we are rejecting.
  Stays for very low-value, env-shaped values (LOG_LEVEL, OIDC_CLIENT_ID)
  which are not secrets in the audit sense.
- **Sealed Secrets / SOPS**. Solves the "in-git" half (encrypted secrets
  travel with the manifests) but does not give us rotation, audit, or fine
  per-service access control. Vault wins.
- **External Secrets Operator → AWS Secrets Manager / GCP Secret Manager**.
  Adds cloud lock-in to a project that markets itself as cloud-agnostic.
  Reject for the reference setup; production deployers who already operate
  one of those can swap Vault out via the same Quarkus extension config.
- **Leave hardcoded defaults**. The audit finding remains open. Reject.

## Consequences

**Positive**
- K1 closed: no plaintext password lands in any committed YAML.
- ADR 0007 promise actually fulfilled.
- One mechanism (Quarkus Vault) per service → uniform troubleshooting.
- BootstrapVerifier catches the regression of someone copy-pasting a dev
  default into a prod profile.

**Negative**
- Per-service yaml change × 28 services. Sed-friendly but each needs a
  smoke test (service starts, reads expected key from Vault).
- Vault dev mode in `openbank-infra/docker-compose.yml` adds one more
  container that has to be healthy before any service starts. Already
  modelled as a depends_on in the existing compose file (Vault is part of
  the platform shared services since ADR 0007).
- CI must seed test secrets into the Vault container before running
  integration tests. New step in `.github/workflows/ci.yml`.

**Neutral**
- No runtime perf cost: Quarkus Vault caches secret reads at startup;
  refresh interval configurable per backend.

## Migration plan

1. **libs**: BootstrapVerifier bean in `com.openbank.libs.security`. Fails
   `@ApplicationScoped` startup if any sensitive property still looks like
   a dev default. Auto-discovered via Jandex.
2. **infra**: `docker-compose.yml` Vault container goes from optional to
   `restart: unless-stopped` + healthcheck. `make up` seeds the KV paths
   via a startup hook (`vault-seed.sh`).
3. **Per-service migration** (~28 yaml files, opportunistic order):
   account, transaction, ledger, sepa-payment first; the rest as touched.
   Each migration touches: application.yaml (4–8 line diff), build.gradle.kts
   (1 line for `implementation(libs.quarkus.vault)`).
4. **CI**: vault-seed step in `ci.yml` before any integration test job.
5. **Prod docs**: short runbook in `docs/strategy/04-security-baseline.md`
   on Vault AppRole provisioning per environment.

## Compliance impact

- **K1** (audit 2026-05-28): closed.
- **NIS2 čl. 21** (cryptographic controls + secret management): satisfied.
- **ISO 27001 A.9.4 / A.10**: secrets no longer in source repo.
- **DORA Art. 9** (ICT security controls): central secret store with
  rotation + audit log (Vault built-in).

## References

- ADR 0007 — Vault for secrets management (the policy this commit finally
  implements).
- 2026-05-28 audit — K1 hardcoded credentials.
- [Quarkus Vault extension](https://docs.quarkiverse.io/quarkus-vault/dev/index.html)
