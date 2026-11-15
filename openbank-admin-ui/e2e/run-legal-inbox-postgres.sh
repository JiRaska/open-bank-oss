#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail
set +x

case "${1:-}" in
  '')
    mailbox=browser-fixture
    delivery_id=910231
    expected_subject='Synthetic inbox subject'
    live=false
    ;;
  --live-isds)
    : "${LEGAL_ISDS_POLL_BASIC:?Missing Basic credentials for the public ISDS test mailbox}"
    : "${LEGAL_ISDS_POLL_TEST_MAILBOX:?Missing local test mailbox namespace}"
    : "${LEGAL_ISDS_POLL_FIRST_FROM:?Missing start of the scan window in ISO 8601}"
    : "${LEGAL_INBOX_E2E_DELIVERY_ID:?Missing ID of the synthetic ISDS test message}"
    : "${LEGAL_INBOX_E2E_SUBJECT:?Missing subject of the synthetic ISDS test message}"
    mailbox="$LEGAL_ISDS_POLL_TEST_MAILBOX"
    delivery_id="$LEGAL_INBOX_E2E_DELIVERY_ID"
    expected_subject="$LEGAL_INBOX_E2E_SUBJECT"
    [[ "$mailbox" =~ ^test-[a-z0-9._:-]{1,118}$ && "$delivery_id" =~ ^[1-9][0-9]{0,18}$ ]] || {
      echo 'Invalid public-test mailbox namespace or ISDS message ID.' >&2
      exit 2
    }
    live=true
    ;;
  *)
    echo 'Usage: run-legal-inbox-postgres.sh [--live-isds]' >&2
    exit 2
    ;;
esac

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
if [[ "$live" == false ]]; then
  cat > "$probe/message.xml" <<'XML'
<MessageDownloadResponse xmlns="http://isds.czebox.cz/v20/message">
  <dmReturnedMessage><dmDm><dmID>910231</dmID><dmAnnotation>Synthetic inbox subject</dmAnnotation>
  <dmSenderRefNumber>SYNTHETIC-1</dmSenderRefNumber><dmFiles><dmFile/></dmFiles>
  </dmDm><dmAcceptanceTime>2026-01-01T12:01:00Z</dmAcceptanceTime></dmReturnedMessage>
</MessageDownloadResponse>
XML
  openssl cms -sign -binary -nodetach -in "$probe/message.xml" -signer "$probe/cert.pem" \
    -inkey "$probe/key.pem" -outform DER -out "$probe/message.zfo"
  printf '\x07\x09' > "$probe/delivery.zfo"
fi
public_key=$(openssl pkey -in "$probe/key.pem" -pubout -outform DER | openssl base64 -A)
bearer=$(node --input-type=module - "$probe/key.pem" "$mailbox" <<'JS'
import { readFileSync } from 'node:fs'
import { sign } from 'node:crypto'
const encode = object => Buffer.from(JSON.stringify(object)).toString('base64url')
const now = Math.floor(Date.now() / 1000)
const roles = ['ROLE_LEGAL_INBOX_VIEW', 'ROLE_LEGAL_INBOX_EVIDENCE', 'ROLE_LEGAL_INBOX_ASSIGN', 'ROLE_LEGAL_MAILBOX_' + process.argv[3]]
const header = encode({ alg: 'RS256', typ: 'JWT' })
const claims = encode({ sub: 'synthetic-browser-operator', aud: 'synthetic-legal-inbox', iss: 'urn:synthetic-legal-inbox', iat: now, exp: now + 1200, realm_access: { roles } })
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
  QUARKUS_OIDC_TOKEN_ISSUER=urn:synthetic-legal-inbox LEGAL_ISDS_POLL_ENABLED="$live" \
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

copy_evidence() {
  docker exec -i "$database" psql -At -U postgres -v ON_ERROR_STOP=1 \
    -v scope="isds:$mailbox" -v delivery_id="$delivery_id" -v column="$1" <<'SQL'
SELECT encode(e.:"column", 'hex')
FROM legal_delivery_inbox i JOIN legal_isds_evidence e USING (evidence_id)
WHERE i.source_namespace = :'scope' AND i.delivery_id = :'delivery_id';
SQL
}

if [[ "$live" == false ]]; then
  message=$(xxd -p < "$probe/message.zfo" | tr -d '\n')
  docker exec -i "$database" psql -q -U postgres -v ON_ERROR_STOP=1 -v original="$message" <<'SQL'
BEGIN;
INSERT INTO legal_isds_evidence (evidence_id, signed_message, signed_delivery_info)
VALUES ('10000000-0000-4000-8000-000000000001', decode(:'original', 'hex'), decode('0709', 'hex'));
INSERT INTO legal_delivery_inbox (receipt_id, source_namespace, delivery_id, route_country, evidence_id, content_sha256)
VALUES ('10000000-0000-4000-8000-000000000002', 'isds:browser-fixture', '910231', 'CZ',
        '10000000-0000-4000-8000-000000000001',
        encode(sha256(int4send(octet_length(decode(:'original', 'hex'))) || decode(:'original', 'hex') || decode('0709', 'hex')), 'hex'));
INSERT INTO legal_isds_poll_run
    (id, mailbox, window_from, window_through, discovered_at, finished_at, status, discovered_count, imported_count, pending_count)
VALUES
    ('10000000-0000-4000-8000-000000000003', 'browser-fixture', now() - interval '5 minutes', now(),
       now(), now(), 'INCOMPLETE', 2, 1, 1),
    ('10000000-0000-4000-8000-000000000004', 'another-fixture', now() - interval '5 minutes', now(),
       now(), now(), 'COMPLETE', 0, 0, 0);
COMMIT;
SQL

else
  echo 'Waiting for discovery and signed originals from the public ISDS test.'
  for _ in {1..190}; do
    copy_evidence signed_message | xxd -r -p > "$probe/message.zfo"
    if [[ -s "$probe/message.zfo" ]]; then break; fi
    if ! kill -0 "$backend_pid" 2>/dev/null; then break; fi
    sleep 2
  done
  if [[ ! -s "$probe/message.zfo" ]]; then
    docker exec "$database" psql -At -U postgres -c \
      'SELECT status, discovered_count, imported_count, pending_count FROM legal_isds_poll_run ORDER BY started_at DESC LIMIT 5'
    echo 'The requested message was not imported within the test interval.' >&2
    exit 1
  fi
fi

copy_evidence signed_message | xxd -r -p > "$probe/message.zfo"
copy_evidence signed_delivery_info | xxd -r -p > "$probe/delivery.zfo"
[[ -s "$probe/delivery.zfo" ]]
openssl cms -verify -noverify -nosigs -binary -inform DER -in "$probe/message.zfo" \
  -out "$probe/signed-message.xml" 2>/dev/null
python3 - "$probe/signed-message.xml" <<'PYTHON'
from datetime import datetime
from sys import argv
from xml.etree import ElementTree

root = ElementTree.parse(argv[1]).getroot()
namespaces = ("http://isds.czebox.cz/v20/message", "http://isds.czechpoint.cz/v20")
for namespace in namespaces:
    delivery = root.findtext(f".//{{{namespace}}}dmAcceptanceTime")
    if delivery and datetime.fromisoformat(delivery.replace("Z", "+00:00")).tzinfo is not None:
        break
else:
    raise SystemExit("The signed message contains no acceptance timestamp with an offset.")
PYTHON
echo 'Stored envelope contains an acceptance timestamp.'

LEGAL_PROCESS_SERVICE_URL="http://127.0.0.1:$backend_port" \
  LEGAL_INBOX_E2E_BEARER="$bearer" LEGAL_INBOX_E2E_DELIVERY_ID="$delivery_id" \
  LEGAL_INBOX_E2E_SUBJECT="$expected_subject" LEGAL_INBOX_E2E_MAILBOX="$mailbox" \
  LEGAL_INBOX_E2E_LIVE_ISDS="$live" LEGAL_INBOX_E2E_RECEIPT="$probe/delivery.zfo" \
  LEGAL_INBOX_E2E_ORIGINAL="$probe/message.zfo" OPENBANK_E2E_SERVER=development \
  OPENBANK_E2E_PORT="${LEGAL_INBOX_E2E_UI_PORT:-39192}" \
  node_modules/.bin/playwright test --config=playwright.legal-inbox.config.ts

audit=$(docker exec -i "$database" psql -At -U postgres -v ON_ERROR_STOP=1 \
  -v source_namespace="isds:$mailbox" -v delivery_id="$delivery_id" <<'SQL'
SELECT string_agg(a.action, ',' ORDER BY a.id),
       count(*) FILTER (WHERE a.action = 'ASSIGN' AND a.previous_team IS NULL
                        AND a.next_team = 'LEGAL_REVIEW_CZ' AND a.review_revision = 1),
       bool_and(r.team = 'LEGAL_REVIEW_CZ' AND r.revision = 1 AND i.content_sha256 =
           encode(sha256(int4send(octet_length(e.signed_message)) || e.signed_message || e.signed_delivery_info), 'hex'))
FROM legal_inbox_audit a JOIN legal_delivery_inbox i USING (receipt_id)
JOIN legal_inbox_review r USING (receipt_id)
JOIN legal_isds_evidence e ON i.evidence_id = e.evidence_id
WHERE a.actor_subject = 'synthetic-browser-operator'
  AND i.source_namespace = :'source_namespace' AND i.delivery_id = :'delivery_id';
SQL
)
[[ "$audit" == 'DOWNLOAD_MESSAGE,DOWNLOAD_DELIVERY,EXTRACT,READ_DRAFT,ASSIGN,READ_DRAFT,READ_DRAFT|1|t' ]]
echo 'PostgreSQL evidence digest and assignment audit match the browser sequence.'
