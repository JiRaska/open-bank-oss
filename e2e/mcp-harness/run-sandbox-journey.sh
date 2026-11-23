#!/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
set -eu
umask 077

if [ "${MCP_HARNESS_APP_SIGN_IN:-}" != true ] || [ -z "${MCP_HARNESS_APP_COUNTER_FILE:-}" ]; then
  echo "scheduled journey requires app sign-in and its persistent counter file" >&2
  exit 2
fi
case "$MCP_HARNESS_APP_COUNTER_FILE" in
  /*) counter="$MCP_HARNESS_APP_COUNTER_FILE";;
  *) counter="$PWD/$MCP_HARNESS_APP_COUNTER_FILE";;
esac
if [ -n "${MCP_HARNESS_PRIVATE_CA_FILE:-}" ]; then
  if [ "$(id -u)" != 1001 ] || [ "$HOME" != /home/pwuser ]; then
    echo "private CA import requires the dedicated sandbox container user" >&2
    exit 2
  fi
  ca=$MCP_HARNESS_PRIVATE_CA_FILE
  case "$ca" in
    /*) ;;
    *) echo "private CA mount requires an absolute path" >&2; exit 2 ;;
  esac
  node --input-type=module - "$ca" <<'JS'
import { X509Certificate } from 'node:crypto'
import { readFileSync, statSync } from 'node:fs'
try {
  const file = statSync(process.argv[2])
  if (!file.isFile() || file.size > 16384) throw new Error("expected a bounded public certificate file")
  const pem = readFileSync(process.argv[2], 'utf8').trim()
  if (!/^-----BEGIN CERTIFICATE-----\r?\n[A-Za-z0-9+/=\r\n]+-----END CERTIFICATE-----$/.test(pem)) {
    throw new Error('expected one public x509 certificate')
  }
  const cert = new X509Certificate(pem)
  if (!cert.ca || cert.subject !== cert.issuer || !cert.verify(cert.publicKey)) {
    throw new Error('expected the sandbox root CA')
  }
} catch {
  console.error('private CA mount must contain one public, self-signed CA certificate')
  process.exitCode = 2
}
JS
  # Chromium supports the legacy location when present. Give this container job its own NSS store.
  db=$HOME/.pki/nssdb
  mkdir -p "$HOME/.pki"
  mkdir "$db"
  certutil -N --empty-password -d "sql:$db"
  certutil -A -d "sql:$db" -t 'C,,' -n 'OpenBank MCP synthetic sandbox CA' -i "$ca"
  export NODE_EXTRA_CA_CERTS="$ca"
fi

script_dir=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)

# Keep the lock through both assertions. Locking only the reservation would allow a later
# process to present its higher reserved counter before the current browser has authenticated.
exec flock --exclusive --nonblock --no-fork -- "$counter.lock" node "$script_dir/src/cli.ts" --journey "$@"
