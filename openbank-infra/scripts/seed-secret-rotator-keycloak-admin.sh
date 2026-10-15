#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# Naseeduje OpenBao KV openbank/keycloak/admin (klíče username, password) pro
# secret-rotator CronJob (openbank-infra/gitops/components/openbao/secret-rotator-cronjob.yaml,
# ADR-0099 Tier 2). Bez tohoto seedu rotator padá na `bao kv get openbank/keycloak/admin`
# při každém běhu (KubeJobFailed) — viz kontext v docs/adr/0177-workload-identity-m2m-auth.md.
#
# Hodnoty NEJSY generovány náhodně: rotator se jimi přihlašuje proti živému Keycloak
# (master realm, admin-cli), takže musí odpovídat skutečnému bootstrap admin účtu.
# Zdroj pravdy je out-of-band secret `keycloak-bootstrap` (vytváří se ručně při instalaci,
# nikdy v git/state — viz hlavička components/keycloak/keycloak.yaml); skript ho přečte
# z clusteru a zkopíruje do KV. Heslo se nikdy nevypisuje.
#
# Idempotentní: pokud KV path už username+password má, skript nic nezapíše a skončí 0.
#
# Spuštění:
#   ./openbank-infra/scripts/seed-secret-rotator-keycloak-admin.sh
#
# Potřebuješ:
#   - kubectl nakonfigurovaný na sandbox cluster
#   - OpenBao operator token s write na openbank/* pod $VAULT_TOKEN / $BAO_TOKEN / $RT
#     (jinak se na něj skript zeptá; vstup se nezobrazuje)
set -euo pipefail

NS_VAULT=vault
NS_IAM=iam
KV_PATH=openbank/keycloak/admin
BOOTSTRAP_SECRET=keycloak-bootstrap

prompt_secret() { # var_name prompt_text
  local __var="$1" __prompt="$2" __val
  if [[ -n "${!__var:-}" ]]; then return; fi
  read -r -s -p "$__prompt: " __val
  echo >&2
  printf -v "$__var" '%s' "$__val"
}

if [[ -z "${VAULT_TOKEN:-}" ]]; then
  if [[ -n "${BAO_TOKEN:-}" ]]; then
    VAULT_TOKEN="$BAO_TOKEN"
  elif [[ -n "${RT:-}" ]]; then
    VAULT_TOKEN="$RT"
  fi
fi
prompt_secret VAULT_TOKEN "OpenBao operator token — nenalezen v \$VAULT_TOKEN/\$BAO_TOKEN/\$RT, vlož ho"

echo "==> kontroluji, jestli $KV_PATH už naseedovaný je (idempotence)"
if kubectl -n "$NS_VAULT" exec openbao-0 -- env "BAO_TOKEN=$VAULT_TOKEN" \
     bao kv get -field=username "$KV_PATH" >/dev/null 2>&1 \
   && kubectl -n "$NS_VAULT" exec openbao-0 -- env "BAO_TOKEN=$VAULT_TOKEN" \
     bao kv get -field=password "$KV_PATH" >/dev/null 2>&1; then
  echo "OK: $KV_PATH už username+password má — nic nezapisuji (přepsání by zneplatnilo rotaci)."
  exit 0
fi

echo "==> čtu bootstrap admin credentials ze secretu $BOOTSTRAP_SECRET (ns $NS_IAM)"
KC_ADMIN_USER="$(kubectl -n "$NS_IAM" get secret "$BOOTSTRAP_SECRET" \
  -o jsonpath='{.data.admin-username}' 2>/dev/null | base64 -d || true)"
KC_ADMIN_PASS_B64="$(kubectl -n "$NS_IAM" get secret "$BOOTSTRAP_SECRET" \
  -o jsonpath='{.data.admin-password}' 2>/dev/null || true)"

if [[ -z "$KC_ADMIN_USER" || -z "$KC_ADMIN_PASS_B64" ]]; then
  echo "!! secret $NS_IAM/$BOOTSTRAP_SECRET nenalezen nebo neobsahuje admin-username/admin-password."
  echo "!! Vytvoř ho ručně podle components/keycloak/keycloak.yaml a spusť znovu."
  exit 1
fi

echo "==> zapisuji do OpenBao ($KV_PATH) — hodnoty se nevypisují"
# Heslo posíláme base64 z secretu přes stdin environmální proměnné, aby neprošlo
# argument listem (kv put -field hodnotu z argv by `ps` ukázal).
kubectl -n "$NS_VAULT" exec -i openbao-0 -- env "BAO_TOKEN=$VAULT_TOKEN" \
  "KC_ADMIN_PASS_B64=$KC_ADMIN_PASS_B64" sh -c \
  'bao kv put "'"$KV_PATH"'" username="'"$KC_ADMIN_USER"'" password="$(echo "$KC_ADMIN_PASS_B64" | base64 -d)"' \
  >/dev/null
unset KC_ADMIN_PASS_B64

echo "==> ověřuji zápis (jen existence polí, ne hodnoty)"
kubectl -n "$NS_VAULT" exec openbao-0 -- env "BAO_TOKEN=$VAULT_TOKEN" \
  bao kv get -field=username "$KV_PATH" | grep -q . \
  && kubectl -n "$NS_VAULT" exec openbao-0 -- env "BAO_TOKEN=$VAULT_TOKEN" \
  bao kv get -field=password "$KV_PATH" | grep -q . \
  && echo "OK: $KV_PATH má username+password. Příští běh secret-rotator už nespadne na kroku 2." \
  || { echo "!! ověření zápisu selhalo" >&2; exit 1; }
