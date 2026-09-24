// SPDX-License-Identifier: Apache-2.0
// Run one inert approval fixture around an approved browser command in a sandbox namespace.
// Cluster context, namespace and Redis service are supplied at runtime; no credentials or
// environment-specific network locations belong in this repository.
import { spawn, spawnSync } from 'node:child_process'
import { randomUUID } from 'node:crypto'

const image = 'docker.io/valkey/valkey@sha256:081c2f5cb575efc901aa80ff9cdbd1ec6a301682fd35e1ebb4b0990a4a4a8507'
const ttlSeconds = 600
const required = ['OPENBANK_FIXTURE_CONTEXT', 'OPENBANK_FIXTURE_NAMESPACE', 'OPENBANK_FIXTURE_REDIS_HOST']
const separator = process.argv.indexOf('--')
const command = separator < 0 ? [] : process.argv.slice(separator + 1)
if (process.env.OPENBANK_FIXTURE_ALLOW !== '1' || command.length === 0 || required.some(name => !process.env[name])) {
  throw new Error(`fixture requires OPENBANK_FIXTURE_ALLOW=1, ${required.join(', ')} and a command after --`)
}
const context = process.env.OPENBANK_FIXTURE_CONTEXT
const namespace = process.env.OPENBANK_FIXTURE_NAMESPACE
const redisHost = process.env.OPENBANK_FIXTURE_REDIS_HOST
if (!/sandbox/i.test(context) || !/^[a-z0-9]([-a-z0-9]*[a-z0-9])?$/.test(namespace) ||
    !/^[a-z0-9]([-a-z0-9.]*[a-z0-9])?$/.test(redisHost)) {
  throw new Error('fixture target must be an explicitly named sandbox context and valid namespace/service')
}

function kube(args, input) {
  const result = spawnSync('kubectl', args, { input, encoding: 'utf8', timeout: 120_000, maxBuffer: 1024 * 1024 })
  if (result.error || result.status !== 0) throw new Error(`kubectl ${args.at(-1)} failed`)
  return result.stdout.trim()
}
if (kube(['config', 'current-context']) !== context) throw new Error('current Kubernetes context differs from approved sandbox context')
kube(['get', 'namespace', namespace, '-o', 'name'])

const id = randomUUID()
const key = `approval:${id}`
const action = 'billing.synthetic.fixture'
const resourceId = `synthetic:${id}`
const makerId = `synthetic-approval-e2e:${id}`
const createdAt = new Date().toISOString()
const value = `${action}|${resourceId}|${makerId}|PENDING|${createdAt}||`
const jobPrefix = `approval-fixture-${id.replaceAll('-', '').slice(0, 12)}`
const jobName = phase => `${jobPrefix}-${phase}`
let interrupted = false
let browserChild = null
for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => {
    interrupted = true
    browserChild?.kill('SIGTERM')
  })
}
const seed = `test "$(valkey-cli -h "$REDIS_HOST" --raw SET "$FIXTURE_KEY" "$FIXTURE_VALUE" NX EX ${ttlSeconds})" = OK && test "$(valkey-cli -h "$REDIS_HOST" --raw GET "$FIXTURE_KEY")" = "$FIXTURE_VALUE"`
const cleanup = `test "$(valkey-cli -h "$REDIS_HOST" --raw EVAL 'if redis.call("GET", KEYS[1]) == ARGV[1] then return redis.call("DEL", KEYS[1]) else return 0 end' 1 "$FIXTURE_KEY" "$FIXTURE_VALUE")" = 1 && test "$(valkey-cli -h "$REDIS_HOST" --raw EXISTS "$FIXTURE_KEY")" = 0`

function manifest(phase) {
  return {
    apiVersion: 'batch/v1', kind: 'Job',
    metadata: { name: jobName(phase), namespace, labels: { 'app.kubernetes.io/part-of': 'approval-e2e' } },
    spec: {
      backoffLimit: 0, activeDeadlineSeconds: 120, ttlSecondsAfterFinished: 600,
      template: { spec: {
        restartPolicy: 'Never', automountServiceAccountToken: false,
        securityContext: { runAsNonRoot: true, runAsUser: 999, runAsGroup: 1000, seccompProfile: { type: 'RuntimeDefault' } },
        containers: [{
          name: 'fixture', image, imagePullPolicy: 'IfNotPresent', command: ['/bin/sh', '-ec'],
          args: [phase === 'seed' ? seed : cleanup],
          env: [{ name: 'REDIS_HOST', value: redisHost }, { name: 'FIXTURE_KEY', value: key }, { name: 'FIXTURE_VALUE', value }],
          securityContext: { allowPrivilegeEscalation: false, readOnlyRootFilesystem: true, capabilities: { drop: ['ALL'] } },
          resources: { requests: { cpu: '10m', memory: '32Mi' }, limits: { cpu: '100m', memory: '64Mi' } },
        }],
      } },
    },
  }
}

async function job(phase) {
  const name = jobName(phase)
  kube(['create', '-f', '-'], `${JSON.stringify(manifest(phase))}\n`)
  const deadline = Date.now() + 120_000
  while (Date.now() < deadline) {
    if (interrupted && phase !== 'cleanup') throw new Error(`${phase} job interrupted`)
    const status = JSON.parse(kube(['-n', namespace, 'get', 'job', name, '-o', 'json'])).status
    if (status?.succeeded === 1) {
      kube(['-n', namespace, 'delete', 'job', name, '--wait=false'])
      return
    }
    if (status?.failed || status?.conditions?.some(c => c.type === 'Failed' && c.status === 'True')) {
      throw new Error(`${phase} job failed`)
    }
    await new Promise(resolve => setTimeout(resolve, 2_000))
  }
  throw new Error(`${phase} job timed out`)
}

function browser() {
  return new Promise((resolve, reject) => {
    if (interrupted) return reject(new Error('browser command interrupted'))
    const child = spawn(command[0], command.slice(1), {
      env: { ...process.env, OPENBANK_APPROVAL_FIXTURE_ID: id, OPENBANK_APPROVAL_FIXTURE_ACTION: action,
        OPENBANK_APPROVAL_FIXTURE_RESOURCE_ID: resourceId, OPENBANK_APPROVAL_FIXTURE_MAKER_ID: makerId,
        OPENBANK_APPROVAL_FIXTURE_CREATED_AT: createdAt },
      stdio: 'ignore',
    })
    browserChild = child
    const timeout = setTimeout(() => child.kill('SIGTERM'), 300_000)
    child.on('error', reject)
    child.on('exit', (code, signal) => {
      browserChild = null
      clearTimeout(timeout)
      if (code === 0) resolve()
      else reject(new Error(`browser command failed (${signal ?? code})`))
    })
  })
}

let seedAttempted = false
let failure = null
try {
  seedAttempted = true
  await job('seed')
  await browser()
} catch (error) {
  failure = error
} finally {
  if (seedAttempted) {
    try {
      await job('cleanup')
      console.log(`synthetic approval ${id}: explicit cleanup verified`)
    } catch {
      console.error(`synthetic approval ${id}: cleanup not proven`)
      process.exitCode = 1
    }
  }
}
if (failure) {
  console.error(failure.message)
  process.exitCode = 1
}
if (interrupted) process.exitCode = 1
