#!/bin/sh
set -e

export VAULT_ADDR="http://127.0.0.1:8200"
export VAULT_TOKEN="${VAULT_DEV_ROOT_TOKEN:?VAULT_DEV_ROOT_TOKEN must be set}"

vault_exec() {
  vault "$@" 2>/dev/null || true
}

echo "Initializing Vault..."

vault_exec secrets enable -path=openbank kv-v2 && echo "KV v2 enabled at openbank/" || echo "KV v2 already enabled"

# Transit engine for analytics crypto-shredding (ADR-0023 F6). The analytics-sink
# VaultCryptoErasure adapter "erases" a GDPR subject by destroying their per-subject
# Transit key (allow-deletion -> destroy), so ciphertext in the immutable bronze log
# becomes unreadable without mutating that append-only log.
vault_exec secrets enable -path=transit transit && echo "Transit enabled at transit/" || echo "Transit already enabled"

vault_exec auth enable approle && echo "AppRole enabled" || echo "AppRole already enabled"

vault policy write openbank-services - <<EOF
path "openbank/*" {
  capabilities = ["read", "list"]
}
EOF
echo "Policy openbank-services written"

# Crypto-shred policy for openbank-analytics-sink: manage + destroy Transit keys
# under the analytics-* prefix. config (allow-deletion) + delete are the erase path;
# create/update/encrypt/decrypt let the encrypting side mint and use per-subject keys.
vault policy write openbank-analytics-erasure - <<EOF
path "transit/keys/analytics-*" {
  capabilities = ["create", "read", "update", "delete"]
}
path "transit/keys/analytics-*/config" {
  capabilities = ["update"]
}
path "transit/encrypt/analytics-*" {
  capabilities = ["update"]
}
path "transit/decrypt/analytics-*" {
  capabilities = ["update"]
}
EOF
echo "Policy openbank-analytics-erasure written"

for svc in account-service ledger-service transaction-service balance-service product-catalog-service; do
  vault kv put openbank/$svc \
    db_password="${POSTGRES_PASSWORD:?POSTGRES_PASSWORD must be set}" \
    jwt_secret="${JWT_SECRET:?JWT_SECRET must be set}" \
    kafka_bootstrap="localhost:29092" \
    valkey_password="${VALKEY_PASSWORD:?VALKEY_PASSWORD must be set}"
  echo "Secret written: openbank/$svc"
done

vault write auth/approle/role/openbank-services \
  token_policies="openbank-services" \
  token_ttl=1h \
  token_max_ttl=4h
echo "AppRole role openbank-services created"

echo "Vault initialization complete."
