# Runbook 0008 — OpenBao document-signing PKI + bank seal keystore (ADR-0162 D4 continued)

Status: Draft (issuer config gitops-committed; out-of-band bootstrap + KV seeding pending)
Owner: Platform + Security
Related: ADR-0162 (document management, templating & e-signature), ADR-0017 (Vault/OpenBao
secrets), ADR-0034 (zero-standing-token), runbook 0005 (Vault→OpenBao), runbook 0007
(agent-identity PKI — the pattern this mirrors).

## Why

`openbank-document-service` applies two cryptographically distinct signatures per ceremony
(ADR-0162 D4 continued):

- **The signer's own electronic signature** — a fresh, single-use certificate per signing act,
  issued from a dedicated OpenBao PKI engine (`pki-document-signing`). The private key is used
  once and discarded; trust rests on the issuing CA staying in OpenBao.
- **The bank's institutional electronic seal** — a stable, long-lived organizational certificate,
  stored as an OpenBao KV secret and projected into a mounted PKCS12 keystore (same pattern as the
  service's own Kafka mTLS identity).

Until this runbook's steps are completed, both fall back to a DEV-ONLY ephemeral, non-CA-issued
identity (loudly logged, worthless as evidence) — the service is always runnable, never crash-loops
on a missing secret, but produces no legally meaningful signature either.

## What's gitops-committed (issuer config — done, this PR)

- `gitops/components/openbao/openbao-document-signing-pki.yaml`: a dedicated `pki-document-signing`
  engine, an internal root CA (generated once, guarded), a `client-signing` issuing role
  (`allow_any_name=true` — CN is the signer's partyRef, not a fixed charter — `ttl=max_ttl=300s`,
  `no_store=true`), and a narrow `document-signing-issue` policy bound to document-service's own
  `document-service` ServiceAccount via Kubernetes auth.
- `gitops/components/document-service/es-document-service-signing-keystore.yaml`: an `ExternalSecret`
  projecting the bank's seal keystore from OpenBao KV (`openbank/document-service-signing-seal`)
  into a mounted PKCS12 Secret — `optional: true` on the volume, so a not-yet-seeded secret degrades
  to the ephemeral fallback rather than blocking the pod.
- `document-service-service.yaml`: a dedicated `document-service` ServiceAccount (least privilege —
  was previously `default`), the signing-keystore volume mount, and `PRODUCT_CATALOG_SERVICE_URL` /
  `OPENBANK_SIGNATURE_KEYSTORE_PATH` / `_PASSWORD` env vars.
- A weekly `openbao-document-signing-sync` CronJob (SA `openbao-document-signing-admin`) that
  re-asserts the PKI config idempotently — same shape as the Tier-1 db-rotation and agent-identity
  syncs.

## Out-of-band bootstrap (one-time, operator) — pending

Mirrors runbook 0007's `agent-identity-admin` bootstrap. The sync CronJob logs in as the
`document-signing-admin` k8s-auth role, which must exist first:

```sh
# Narrow admin policy for the sync job: manage ONLY the pki-document-signing engine + its policy.
bao policy write document-signing-admin - <<'EOF'
path "sys/mounts/pki-document-signing"      { capabilities = ["create","read","update"] }
path "sys/mounts/pki-document-signing/tune" { capabilities = ["update"] }
path "pki-document-signing/*"               { capabilities = ["create","read","update","list"] }
path "sys/policies/acl/document-signing-issue" { capabilities = ["create","update","read"] }
path "auth/kubernetes/role/document-service-signing" { capabilities = ["create","update","read"] }
EOF

bao write auth/kubernetes/role/document-signing-admin \
  bound_service_account_names=openbao-document-signing-admin \
  bound_service_account_namespaces=vault \
  token_policies=document-signing-admin \
  ttl=10m
```

## Seeding the bank's seal keystore (one-time, operator) — pending

Generate (or obtain from a real organizational CA, once legal sign-off for a production identity
lands) a PKCS12 keystore for the bank's own seal certificate, then write it to OpenBao KV as
base64:

```sh
# Example: a self-signed org cert for the sandbox (production should use a real issued cert).
keytool -genkeypair -alias openbank-seal -keyalg RSA -keysize 2048 -validity 3650 \
  -dname "CN=OpenBank, O=OpenBank" -keystore seal-keystore.p12 -storetype PKCS12 \
  -storepass "<CHOOSE-A-REAL-PASSWORD>"

bao kv put openbank/document-service-signing-seal \
  KEYSTORE_P12_BASE64="$(base64 -i seal-keystore.p12 | tr -d '\n')" \
  KEYSTORE_PASSWORD="<the same password>"
```

The `document-service-signing-keystore` ExternalSecret picks this up on its next `refreshInterval`
(1h) or immediately via `kubectl annotate externalsecret document-service-signing-keystore -n
documents force-sync=$(date +%s) --overwrite`.

## Verify

```sh
bao read pki-document-signing/cert/ca                 # CA present
bao read pki-document-signing/roles/client-signing     # max_ttl 300s, allow_any_name true
bao write pki-document-signing/issue/client-signing common_name=test-party ttl=300s  # smoke issue
bao kv get openbank/document-service-signing-seal      # keystore properties present

kubectl get secret document-service-signing-keystore -n documents  # ESO-created Secret exists
kubectl logs -n documents deploy/document-service | grep -i "EPHEMERAL"  # should NOT appear
```

If the ephemeral-fallback warning still appears in logs after both steps above, check: the pod
actually restarted after the Secret synced (the keystore is read once at CDI bean construction,
not hot-reloaded); the `document-service` ServiceAccount is correctly bound in
`auth/kubernetes/role/document-service-signing`; OpenBao is reachable from the `documents`
namespace (NetworkPolicy).

## Rollback

`bao secrets disable pki-document-signing` removes the engine and every (ephemeral, `no_store`)
cert in flight. Remove the `document-service-signing` k8s-auth role binding first so no consumer
depends on it. The bank seal keystore (KV) is independent — deleting it simply reverts
`PdfBoxPadesSealAdapter` to its ephemeral fallback, it does not affect already-sealed documents.
