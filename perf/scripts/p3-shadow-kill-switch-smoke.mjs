// SPDX-License-Identifier: Apache-2.0
// P3 shadow kill-switch cluster smoke harness (ADR-0244 D7).
//
// Runs end-to-end against a live sandbox cluster from a developer laptop:
//   1. Mints an M2M token from Keycloak (client_credentials).
//   2. Clears any lingering rca-investigator kill-switch so the pilot can open cases.
//   3. Opens a shadow incident-response case via case-coordinator-agent REST.
//   4. Publishes agent.killswitch.set to agent-kill-switch-events-in from inside
//      the cluster, using the agent-service Kafka mTLS identity.
//   5. Polls the case read-model until it reports CLOSED with haltedAtEpochMs.
//   6. Publishes agent.killswitch.cleared to restore the pilot.
//
// Run:
//   export KEYCLOAK_CLIENT_SECRET=$(kubectl -n platform get secret case-coordinator-oidc \\
//     -o jsonpath='{.data.OIDC_CLIENT_SECRET}' | base64 -d)
//   node perf/scripts/p3-shadow-kill-switch-smoke.mjs
//
// Requires kubectl context pointing at the target cluster and port-forwards for:
//   - Keycloak (default localhost:8090)
//   - case-coordinator-agent (default localhost:8146)
// The Kafka publish itself runs in-cluster, so no local Kafka access is needed.

import { randomUUID } from 'node:crypto'
import { spawn } from 'node:child_process'
import { setTimeout as sleep } from 'node:timers/promises'

const KEYCLOAK_URL = process.env.KEYCLOAK_URL || 'http://localhost:8090'
const KEYCLOAK_REALM = process.env.KEYCLOAK_REALM || 'openbank'
const KEYCLOAK_CLIENT_ID = process.env.KEYCLOAK_CLIENT_ID || 'openbank-services'
const KEYCLOAK_CLIENT_SECRET = process.env.KEYCLOAK_CLIENT_SECRET || ''
const CASE_COORDINATOR_URL = process.env.CASE_COORDINATOR_URL || 'http://localhost:8146'
const KAFKA_NAMESPACE = process.env.KAFKA_NAMESPACE || 'platform'
const KAFKA_BOOTSTRAP = process.env.KAFKA_BOOTSTRAP || 'openbank-cluster-kafka-bootstrap.messaging.svc:9093'
const KAFKA_TOPIC = process.env.KAFKA_TOPIC || 'agent-kill-switch-events-in'
const KILL_SWITCH_SCOPE = process.env.KILL_SWITCH_SCOPE || 'rca-investigator'
const MAX_WAIT_MS = Number(process.env.MAX_WAIT_MS || '60000')
const POLL_INTERVAL_MS = Number(process.env.POLL_INTERVAL_MS || '2000')

const HEADERS_JSON = { 'Content-Type': 'application/json' }

function log(...args) {
  // eslint-disable-next-line no-console
  console.log(new Date().toISOString(), ...args)
}

function fail(message) {
  throw new Error(message)
}

async function httpFetch(url, options = {}) {
  const res = await fetch(url, options)
  const text = await res.text()
  let body
  try {
    body = text ? JSON.parse(text) : null
  } catch {
    body = text
  }
  if (!res.ok) {
    fail(`HTTP ${res.status} from ${options.method || 'GET'} ${url}: ${text}`)
  }
  return body
}

async function mintToken() {
  if (!KEYCLOAK_CLIENT_SECRET) {
    fail('KEYCLOAK_CLIENT_SECRET is required')
  }
  const params = new URLSearchParams({
    grant_type: 'client_credentials',
    client_id: KEYCLOAK_CLIENT_ID,
    client_secret: KEYCLOAK_CLIENT_SECRET,
  })
  const url = `${KEYCLOAK_URL}/realms/${KEYCLOAK_REALM}/protocol/openid-connect/token`
  log('minting M2M token from', url)
  const body = await httpFetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: params.toString(),
  })
  if (!body.access_token) {
    fail('token response contained no access_token')
  }
  log('M2M token minted; sub:', body.access_token.split('.')[1] ? JSON.parse(Buffer.from(body.access_token.split('.')[1], 'base64url').toString()).sub : '?')
  return body.access_token
}

async function openCase(token) {
  const subjectRef = `p3-shadow-smoke-${Date.now()}-${Math.floor(Math.random() * 1_000_000)}`
  const url = `${CASE_COORDINATOR_URL}/api/v1/case-coordinator/cases`
  const payload = {
    caseClass: 'incident-response',
    subjectRef,
    openedBy: 'case-coordinator',
    dispositionTarget: 'P3 shadow kill-switch smoke harness',
  }
  log('opening case:', payload.subjectRef)
  const body = await httpFetch(url, {
    method: 'POST',
    headers: { ...HEADERS_JSON, Authorization: `Bearer ${token}` },
    body: JSON.stringify(payload),
  })
  if (!body.caseId) {
    fail(`case open did not return caseId: ${JSON.stringify(body)}`)
  }
  log('case opened:', body.caseId)
  return body.caseId
}

function jobManifest(jobName, eventType, reason) {
  const eventId = randomUUID()
  const occurredAt = new Date().toISOString()
  const payload = JSON.stringify({
    eventId,
    eventType,
    aggregateId: KILL_SWITCH_SCOPE,
    reason,
    actorId: 'p3-shadow-kill-switch-smoke',
    occurredAt,
  })
  return `apiVersion: batch/v1
kind: Job
metadata:
  name: ${jobName}
  namespace: ${KAFKA_NAMESPACE}
  labels:
    app: p3-shadow-kill-switch-smoke
spec:
  ttlSecondsAfterFinished: 600
  backoffLimit: 0
  template:
    metadata:
      labels:
        app: p3-shadow-kill-switch-smoke
    spec:
      restartPolicy: Never
      initContainers:
      - name: props
        image: quay.io/strimzi/kafka:0.44.0-kafka-3.8.0
        command: ["/bin/bash", "-c"]
        args:
        - |
          set -euo pipefail
          cat > /shared/producer.properties <<PROPS
          security.protocol=SSL
          ssl.endpoint.identification.algorithm=
          ssl.keystore.location=/opt/kafka/keystore/user.p12
          ssl.keystore.password=${process.env.KAFKA_KEYSTORE_PASSWORD || '$(KAFKA_KEYSTORE_PASSWORD)'}
          ssl.keystore.type=PKCS12
          ssl.truststore.location=/opt/kafka/truststore/ca.p12
          ssl.truststore.password=${process.env.KAFKA_TRUSTSTORE_PASSWORD || '$(KAFKA_TRUSTSTORE_PASSWORD)'}
          ssl.truststore.type=PKCS12
          PROPS
        volumeMounts:
        - name: producer-props
          mountPath: /shared
        env:
        - name: KAFKA_KEYSTORE_PASSWORD
          valueFrom:
            secretKeyRef:
              name: agent-service-kafka-keystore
              key: user.password
        - name: KAFKA_TRUSTSTORE_PASSWORD
          valueFrom:
            secretKeyRef:
              name: kafka-cluster-ca-truststore
              key: ca.password
      containers:
      - name: kafka
        image: quay.io/strimzi/kafka:0.44.0-kafka-3.8.0
        command: ["/bin/bash", "-c"]
        args:
        - |
          set -euo pipefail
          cat > /tmp/msg.json <<'MSG'
          ${payload}
          MSG
          /opt/kafka/bin/kafka-console-producer.sh \\
            --bootstrap-server ${KAFKA_BOOTSTRAP} \\
            --topic ${KAFKA_TOPIC} \\
            --producer.config /shared/producer.properties \\
            --property parse.key=false \\
            < /tmp/msg.json
          echo "published ${eventId}"
        volumeMounts:
        - name: keystore
          mountPath: /opt/kafka/keystore
          readOnly: true
        - name: truststore
          mountPath: /opt/kafka/truststore
          readOnly: true
        - name: producer-props
          mountPath: /shared
          readOnly: true
        resources:
          requests:
            cpu: '100m'
            memory: '256Mi'
          limits:
            memory: '512Mi'
      volumes:
      - name: keystore
        secret:
          secretName: agent-service-kafka-keystore
          items:
          - key: user.p12
            path: user.p12
      - name: truststore
        secret:
          secretName: kafka-cluster-ca-truststore
          items:
          - key: ca.p12
            path: ca.p12
      - name: producer-props
        emptyDir: {}
`
}

function runCommand(cmd, args, input) {
  return new Promise((resolve, reject) => {
    const child = spawn(cmd, args, { stdio: ['pipe', 'pipe', 'pipe'] })
    let stdout = ''
    let stderr = ''
    child.stdout.on('data', (data) => { stdout += data.toString() })
    child.stderr.on('data', (data) => { stderr += data.toString() })
    if (input) {
      child.stdin.write(input)
      child.stdin.end()
    }
    child.on('close', (code) => {
      if (code !== 0) {
        reject(new Error(`"${cmd} ${args.join(' ')}" exited ${code}\nSTDOUT:\n${stdout}\nSTDERR:\n${stderr}`))
      } else {
        resolve(stdout)
      }
    })
  })
}

async function runKafkaJob(eventType, reason) {
  const jobName = `p3-kill-switch-${eventType.replace(/\./g, '-')}-${Date.now()}`
  const manifest = jobManifest(jobName, eventType, reason)
  log('applying Kafka job:', jobName)
  await runCommand('kubectl', ['apply', '-f', '-'], manifest)
  try {
    await runCommand('kubectl', ['wait', '--for=condition=complete', '-n', KAFKA_NAMESPACE, `job/${jobName}`, '--timeout=120s'])
    const logs = await runCommand('kubectl', ['logs', '-n', KAFKA_NAMESPACE, `job/${jobName}`, '--tail=20'])
    log('Kafka job completed:\n', logs)
  } finally {
    // Keep the pod logs available for a few minutes via ttlSecondsAfterFinished,
    // but delete the Job object so repeated runs do not collide.
    await runCommand('kubectl', ['delete', 'job', '-n', KAFKA_NAMESPACE, jobName, '--ignore-not-found=true']).catch(() => {})
  }
  return jobName
}

async function pollCase(token, caseId) {
  const url = `${CASE_COORDINATOR_URL}/api/v1/case-coordinator/cases/${caseId}`
  const deadline = Date.now() + MAX_WAIT_MS
  while (Date.now() < deadline) {
    log('polling case:', caseId)
    const body = await httpFetch(url, {
      headers: { Authorization: `Bearer ${token}` },
    })
    if (body.status === 'CLOSED' && body.haltedAtEpochMs && body.haltedAtEpochMs > 0) {
      log('case halted successfully:', JSON.stringify({ status: body.status, haltedAtEpochMs: body.haltedAtEpochMs, haltReason: body.haltReason }))
      return body
    }
    if (body.status === 'CLOSED' && !body.haltedAtEpochMs) {
      fail('case closed but haltedAtEpochMs is missing')
    }
    await sleep(POLL_INTERVAL_MS)
  }
  fail(`case did not halt within ${MAX_WAIT_MS}ms`)
}

async function main() {
  const token = await mintToken()

  // Pre-clean: lingering kill-switch from a previous interrupted run would block openCase().
  await runKafkaJob('agent.killswitch.cleared', 'resumed')

  let caseId
  let mainError
  try {
    caseId = await openCase(token)
    await runKafkaJob('agent.killswitch.set', 'P3 shadow kill-switch smoke test halt')
    await pollCase(token, caseId)
    log('SMOKE PASSED:', caseId)
  } catch (err) {
    mainError = err
  } finally {
    // Always restore the pilot, even if assertions above fail.
    log('clearing kill-switch to restore pilot')
    try {
      await runKafkaJob('agent.killswitch.cleared', 'resumed')
    } catch (cleanupErr) {
      log('cleanup failed, manual intervention may be needed:', cleanupErr.message)
      if (!mainError) mainError = cleanupErr
    }
  }

  if (mainError) throw mainError
}

main().catch((err) => {
  log('SMOKE FAILED:', err.message)
  process.exit(1)
})
