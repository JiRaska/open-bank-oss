#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail

repo=$(cd "$(dirname "$0")/../.." && pwd)
service="$repo/openbank-legal-process-service/build/quarkus-app/quarkus-run.jar"
[[ -f "$service" ]] || { echo 'Build :openbank-legal-process-service:quarkusBuild first.' >&2; exit 1; }
cd "$repo/openbank-admin-ui"
[[ -x node_modules/.bin/playwright ]] || { echo 'Run npm ci first.' >&2; exit 1; }

probe=$(mktemp -d)
chmod 700 "$probe"
backend_pid=
database=
cleanup() {
  if [[ -n "$backend_pid" ]]; then kill "$backend_pid" 2>/dev/null || true; fi
  if [[ -n "$database" ]]; then docker rm -f "$database" >/dev/null 2>&1 || true; fi
  rm -rf "$probe"
}
trap cleanup EXIT

openssl req -x509 -newkey rsa:2048 -nodes -batch -subj '/CN=Synthetic Legal Inbox' \
  -days 1 -keyout "$probe/key.pem" -out "$probe/cert.pem" >/dev/null 2>&1
cat > "$probe/message.xml" <<'XML'
<MessageDownloadResponse xmlns="http://isds.czebox.cz/v20/message">
  <dmReturnedMessage><dmDm><dmID>910231</dmID><dmAnnotation>Synthetic inbox subject</dmAnnotation>
  <dmSenderRefNumber>SYNTHETIC-1</dmSenderRefNumber><dmFiles><dmFile/></dmFiles>
  </dmDm></dmReturnedMessage>
</MessageDownloadResponse>
XML
openssl cms -sign -binary -nodetach -in "$probe/message.xml" -signer "$probe/cert.pem" \
  -inkey "$probe/key.pem" -outform DER -out "$probe/message.zfo"
public_key=$(openssl pkey -in "$probe/key.pem" -pubout -outform DER | openssl base64 -A)
bearer=$(node --input-type=module - "$probe/key.pem" <<'JS'
import { readFileSync } from 'node:fs'
import { sign } from 'node:crypto'
const encode = object => Buffer.from(JSON.stringify(object)).toString('base64url')
const now = Math.floor(Date.now() / 1000)
const roles = ['ROLE_LEGAL_INBOX_VIEW', 'ROLE_LEGAL_INBOX_EVIDENCE', 'ROLE_LEGAL_INBOX_ASSIGN', 'ROLE_LEGAL_MAILBOX_browser-fixture']
const header = encode({ alg: 'RS256', typ: 'JWT' })
const claims = encode({ sub: 'synthetic-browser-operator', aud: 'synthetic-legal-inbox', iss: 'urn:synthetic-legal-inbox', iat: now, exp: now + 600, realm_access: { roles } })
const input = header + '.' + claims
process.stdout.write(input + '.' + sign('RSA-SHA256', Buffer.from(input), readFileSync(process.argv[2])).toString('base64url'))
JS
)

db_password=$(openssl rand -hex 16)
database=$(docker run -d --rm -p 127.0.0.1::5432 -e POSTGRES_PASSWORD="$db_password" postgres:18.6-alpine)
for _ in {1..30}; do
  if docker exec "$database" pg_isready -h 127.0.0.1 -U postgres >/dev/null 2>&1; then break; fi
  sleep 1
done
db_port=$(docker port "$database" 5432/tcp | sed -n '1s/.*://p')
backend_port=${LEGAL_INBOX_E2E_BACKEND_PORT:-39191}
QUARKUS_HTTP_HOST=127.0.0.1 QUARKUS_HTTP_PORT="$backend_port" \
  QUARKUS_DATASOURCE_JDBC_URL="jdbc:postgresql://127.0.0.1:$db_port/postgres" \
  QUARKUS_DATASOURCE_USERNAME=postgres QUARKUS_DATASOURCE_PASSWORD="$db_password" \
  QUARKUS_OIDC_PUBLIC_KEY="$public_key" LEGAL_INBOX_AUDIENCE=synthetic-legal-inbox \
  QUARKUS_OIDC_TOKEN_ISSUER=urn:synthetic-legal-inbox \
  java -jar "$service" > "$probe/backend.log" 2>&1 &
backend_pid=$!
ready=false
for _ in {1..60}; do
  if curl --fail --silent --max-time 2 --header "Authorization: Bearer $bearer" \
      "http://127.0.0.1:$backend_port/api/v1/legal-inbox" >/dev/null; then ready=true; break; fi
  if ! kill -0 "$backend_pid" 2>/dev/null; then break; fi
  sleep 1
done
if [[ "$ready" != true ]]; then cat "$probe/backend.log" >&2; exit 1; fi

message=$(xxd -p < "$probe/message.zfo" | tr -d '\n')
docker exec -i "$database" psql -q -U postgres -v ON_ERROR_STOP=1 -v original="$message" <<'SQL'
BEGIN;
INSERT INTO legal_isds_evidence (evidence_id, signed_message, signed_delivery_info)
VALUES ('10000000-0000-4000-8000-000000000001', decode(:'original', 'hex'), decode('0709', 'hex'));
INSERT INTO legal_delivery_inbox (receipt_id, source_namespace, delivery_id, route_country, evidence_id, content_sha256)
VALUES ('10000000-0000-4000-8000-000000000002', 'isds:browser-fixture', '910231', 'CZ',
        '10000000-0000-4000-8000-000000000001', repeat('a', 64));
INSERT INTO legal_isds_poll_run
    (id, mailbox, window_from, window_through, discovered_at, finished_at, status, discovered_count, imported_count, pending_count)
VALUES
    ('10000000-0000-4000-8000-000000000003', 'browser-fixture', now() - interval '5 minutes', now(),
       now(), now(), 'INCOMPLETE', 2, 1, 1),
    ('10000000-0000-4000-8000-000000000004', 'another-fixture', now() - interval '5 minutes', now(),
       now(), now(), 'COMPLETE', 0, 0, 0);
COMMIT;
SQL

LEGAL_PROCESS_SERVICE_URL="http://127.0.0.1:$backend_port" \
  LEGAL_INBOX_E2E_BEARER="$bearer" LEGAL_INBOX_E2E_DELIVERY_ID=910231 \
  LEGAL_INBOX_E2E_ORIGINAL="$probe/message.zfo" OPENBANK_E2E_SERVER=development \
  OPENBANK_E2E_PORT="${LEGAL_INBOX_E2E_UI_PORT:-39192}" \
  node_modules/.bin/playwright test --config=playwright.legal-inbox.config.ts

history=$(docker exec "$database" psql -At -U postgres -c "
  SELECT string_agg(action, ',' ORDER BY id) FROM legal_inbox_audit
  WHERE actor_subject = 'synthetic-browser-operator'")
[[ "$history" == 'DOWNLOAD_MESSAGE,EXTRACT,READ_DRAFT,ASSIGN,READ_DRAFT,READ_DRAFT' ]]
echo 'PostgreSQL audit matches the browser sequence.'
