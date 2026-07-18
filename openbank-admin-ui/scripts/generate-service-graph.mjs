// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Generate the code-derived inter-service dependency graph (ADR-0029 D1,
// "the critical replacement for the lineage data buried in the hand-curated
// manifest.ts"). Walks each service's application.yaml and derives edges from
// REAL configuration — never hand-curated — so humans and agents can answer
// "if I change producer X, who breaks?" (blast-radius, CI impact selection).
//
// Two edge kinds, both parsed from the Quarkus config:
//   rest  — quarkus.rest-client.<target-service>.url  (synchronous API call)
//           the rest-client KEY is literally the target service short-name.
//   kafka — mp.messaging.outgoing.*.topic (produces) ↔ incoming.*.topic
//           (consumes); a producer→consumer edge is drawn per shared topic.
//
// Honest by construction: a config that can't be parsed contributes no edges
// (never a fabricated dependency). Targets that don't resolve to a known module
// stay OUT of the inter-service `edges`/`nodes` (only module→module is kept there).
//
// Data-flow map extension (additive, same honesty rule): the animated topology in
// the admin-ui also wants the *substrate* each service sits on and the *third
// parties* it reaches, so this generator ALSO emits two extra tiers, parsed from
// the same application.yaml (base profile only — `%dev`/`%test` overrides ignored):
//   infraNodes/infraEdges     — Postgres, Kafka, Keycloak, OPA (presence-detected)
//   externalNodes/externalEdges — Apple APNs, Firebase FCM, Slack, ČNB, GitHub,
//                                 LLM providers, S3 (host- or enabled-flag-detected)
// These are strictly additive: the original `nodes`/`edges` are unchanged, so every
// existing consumer keeps working. A tier node is emitted only if ≥1 edge references
// it — no orphan/fabricated nodes.
//
// Usage: node scripts/generate-service-graph.mjs [--repo <path>] [--out <file>]

import { readdirSync, statSync, readFileSync, writeFileSync } from 'fs'
import path from 'path'
import { fileURLToPath } from 'url'
import { parse as parseYaml } from 'yaml'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const args = process.argv.slice(2)
const getArg = (flag, dflt) => {
  const i = args.indexOf(flag)
  return i >= 0 && args[i + 1] ? args[i + 1] : dflt
}
const REPO = path.resolve(getArg('--repo', path.resolve(__dirname, '..', '..')))
const OUT = path.resolve(getArg('--out', path.resolve(__dirname, '..', 'service-graph.json')))

const readText = p => { try { return readFileSync(p, 'utf-8') } catch { return null } }

// Known module names (the node set). An edge target is kept only if it resolves
// to one of these — so external deps (OPA, Keycloak, Postgres) never appear.
const modules = readdirSync(REPO)
  .filter(n => n.startsWith('openbank-'))
  .filter(n => { try { return statSync(path.join(REPO, n)).isDirectory() } catch { return false } })
  .sort()
const moduleSet = new Set(modules)

// Resolve a rest-client key (e.g. "sanctions-service", "sepa-payment") to a
// module name. The key is the target short-name; try openbank-<key> directly,
// then with a -service suffix.
function resolveTarget(key) {
  const k = String(key).toLowerCase().replace(/_/g, '-')
  if (moduleSet.has(`openbank-${k}`)) return `openbank-${k}`
  if (moduleSet.has(`openbank-${k}-service`)) return `openbank-${k}-service`
  return null
}

// Quarkus splits config across `quarkus.*`, `mp.*`, and profile keys (`%dev`).
// Read the base profile only (ignore %-prefixed overrides) and tolerate both
// nested and flat-dotted forms by merging what we find.
function loadConfig(dir) {
  const raw = readText(path.join(dir, 'src', 'main', 'resources', 'application.yaml'))
  if (raw == null) return null
  try { return parseYaml(raw) } catch { return null }
}

// --- Infra + external tier helpers ------------------------------------------
// Recursively visit every scalar in the parsed YAML, yielding [dottedKeyPath,
// value]. Profile overrides (`%dev`, `%test`, `%slack-it`) are skipped so the
// tiers describe the BASE (production-shaped) config, matching the rest/kafka pass.
function* walkScalars(obj, prefix = '') {
  if (obj == null || typeof obj !== 'object') return
  for (const [k, v] of Object.entries(obj)) {
    if (k.startsWith('%')) continue
    const keyPath = prefix ? `${prefix}.${k}` : k
    if (v != null && typeof v === 'object') yield* walkScalars(v, keyPath)
    else yield [keyPath, v]
  }
}

// Resolve a Quarkus `${ENV:default}` wrapper (or a plain scalar) to its effective
// value/boolean. `${APNS_ENABLED:false}` → 'false'; a bare value passes through.
function envValue(v) {
  const s = String(v ?? '').trim()
  const m = s.match(/^\$\{[^:}]*:([^}]*)\}$/)
  return (m ? m[1] : s).trim()
}
function envBool(v) { return envValue(v).toLowerCase() === 'true' }

// Infra node catalog — id → {tech, label}. Presence-detected from config keys.
const INFRA = {
  postgres: { tech: 'postgres', label: 'PostgreSQL' },
  kafka:    { tech: 'kafka',    label: 'Apache Kafka' },
  keycloak: { tech: 'keycloak', label: 'Keycloak (OIDC)' },
  opa:      { tech: 'opa',      label: 'OPA (authz)' },
}
const INFRA_EDGE_TYPE = { postgres: 'db', kafka: 'broker', keycloak: 'auth', opa: 'authz' }

// External / 3rd-party catalog — id → {vendor, label}. Host- or flag-detected.
const EXTERNAL = {
  'apple-apns':   { vendor: 'Apple',         label: 'APNs (push)' },
  'firebase-fcm': { vendor: 'Google',        label: 'Firebase FCM (push)' },
  slack:          { vendor: 'Slack',         label: 'Slack webhook' },
  cnb:            { vendor: 'ČNB',           label: 'Czech National Bank' },
  github:         { vendor: 'GitHub',        label: 'GitHub API' },
  'llm-gateway':  { vendor: 'LLM providers', label: 'LLM gateway' },
  s3:             { vendor: 'AWS',           label: 'S3 object store' },
}
// Classify a URL host as a known EXTERNAL 3rd party. In-cluster hosts
// (litellm.ai-platform, ollama, localhost, openbank-*) return null on purpose —
// they are infra or inter-service edges elsewhere, so counting them here would
// double up. Unknown public hosts also return null: never fabricate a vendor.
function classifyHost(host) {
  const h = host.toLowerCase()
  if (h.includes('cnb.cz')) return { id: 'cnb', type: 'registry' }
  if (h === 'api.github.com' || h.endsWith('.github.com')) return { id: 'github', type: 'api' }
  if (h.includes('deepinfra.com') || h.includes('groq.com') || h.includes('nvidia.com')) return { id: 'llm-gateway', type: 'llm' }
  if (h.includes('amazonaws.com')) return { id: 's3', type: 'api' }
  return null
}

const restEdges = []                 // {from, to, via}
const producers = new Map()          // topic -> Set(service)
const consumers = new Map()          // topic -> Set(service)
const infraEdges = []                // {from, to, type}
const externalEdges = []             // {from, to, type, enabled}

for (const name of modules) {
  const cfg = loadConfig(path.join(REPO, name))
  if (!cfg) continue

  // --- REST client edges: quarkus.rest-client.<target>.url ------------------
  const restClient = cfg?.quarkus?.['rest-client'] ?? cfg?.['quarkus.rest-client']
  if (restClient && typeof restClient === 'object') {
    for (const [clientKey, val] of Object.entries(restClient)) {
      // skip global rest-client settings (only per-client objects have a url)
      if (!val || typeof val !== 'object' || !('url' in val)) continue
      const to = resolveTarget(clientKey)
      if (to && to !== name) restEdges.push({ from: name, to, via: clientKey })
    }
  }

  // --- Kafka edges: mp.messaging.outgoing/incoming.*.topic ------------------
  const messaging = cfg?.mp?.messaging ?? cfg?.['mp.messaging']
  for (const dir of ['outgoing', 'incoming']) {
    const block = messaging?.[dir]
    if (!block || typeof block !== 'object') continue
    for (const ch of Object.values(block)) {
      const topic = ch && typeof ch === 'object' ? ch.topic : null
      if (!topic) continue
      const map = dir === 'outgoing' ? producers : consumers
      if (!map.has(topic)) map.set(topic, new Set())
      map.get(topic).add(name)
    }
  }

  // --- Infra + external tiers (single walk over the base config) -----------
  const svcInfra = new Set()                 // infra ids this service touches
  const svcExternal = new Map()              // `${id}|${type}` -> enabled(bool)
  const addExternal = (id, type, enabled) => {
    const k = `${id}|${type}`
    svcExternal.set(k, (svcExternal.get(k) ?? false) || enabled)
  }
  // Any Kafka channel means the service sits on the broker.
  if (messaging?.outgoing || messaging?.incoming) svcInfra.add('kafka')

  for (const [keyPath, value] of walkScalars(cfg)) {
    const kp = keyPath.toLowerCase()
    const raw = envValue(value)
    // Infra — presence-detected.
    if (kp.endsWith('datasource.reactive.url') || kp.endsWith('datasource.jdbc.url') || raw.includes('postgresql://')) svcInfra.add('postgres')
    if (kp.endsWith('oidc.auth-server-url') || raw.includes('/realms/')) svcInfra.add('keycloak')
    if (kp === 'opa.url' || kp.endsWith('.opa.url') || /:8181(\/|$)/.test(raw)) svcInfra.add('opa')
    if (kp.includes('bootstrap-servers')) svcInfra.add('kafka')
    // External — push/webhook detected by the integration's own enabled flag
    // (the endpoint/creds are injected at runtime, so the URL is usually empty).
    if (kp.endsWith('push.apns.enabled')) addExternal('apple-apns', 'push', envBool(value))
    else if (kp.endsWith('push.fcm.enabled')) addExternal('firebase-fcm', 'push', envBool(value))
    else if (kp.endsWith('webhook.slack.enabled')) addExternal('slack', 'webhook', envBool(value))
    // External — any http(s) URL whose host is a known 3rd party.
    const m = raw.match(/^https?:\/\/([^/:\s]+)/)
    if (m) { const c = classifyHost(m[1]); if (c) addExternal(c.id, c.type, true) }
  }

  for (const id of svcInfra) infraEdges.push({ from: name, to: `infra:${id}`, type: INFRA_EDGE_TYPE[id] })
  for (const [k, enabled] of svcExternal) {
    const [id, type] = k.split('|')
    externalEdges.push({ from: name, to: `ext:${id}`, type, enabled })
  }
}

// Producer → consumer edges, one per (producer, consumer, topic) triple.
const kafkaEdges = []
for (const [topic, prods] of producers) {
  const cons = consumers.get(topic)
  if (!cons) continue
  for (const from of prods) for (const to of cons) {
    if (from !== to) kafkaEdges.push({ from, to, via: topic })
  }
}

// Topics produced but never consumed (or vice-versa) — useful dangling-edge
// signal (a consumer waiting on a topic nobody emits, or an unread event).
const danglingTopics = []
for (const t of producers.keys()) if (!consumers.has(t)) danglingTopics.push({ topic: t, producedBy: [...producers.get(t)], consumedBy: [] })
for (const t of consumers.keys()) if (!producers.has(t)) danglingTopics.push({ topic: t, producedBy: [], consumedBy: [...consumers.get(t)] })

const edges = [
  ...restEdges.map(e => ({ ...e, type: 'rest' })),
  ...kafkaEdges.map(e => ({ ...e, type: 'kafka' })),
]

// Emit an infra/external node only if ≥1 edge references it (no orphan nodes).
const usedInfra = new Set(infraEdges.map(e => e.to.slice('infra:'.length)))
const infraNodes = [...usedInfra].sort()
  .filter(id => INFRA[id])
  .map(id => ({ id: `infra:${id}`, kind: 'infra', ...INFRA[id] }))
const usedExternal = new Set(externalEdges.map(e => e.to.slice('ext:'.length)))
const externalNodes = [...usedExternal].sort()
  .filter(id => EXTERNAL[id])
  .map(id => ({ id: `ext:${id}`, kind: 'external', ...EXTERNAL[id] }))

// Per-node fan-in / fan-out, so the UI/agent can rank blast radius directly.
const degree = {}
for (const n of modules) degree[n] = { dependsOn: 0, dependedOnBy: 0 }
for (const e of edges) {
  if (degree[e.from]) degree[e.from].dependsOn++
  if (degree[e.to]) degree[e.to].dependedOnBy++
}

const graph = {
  schema: 'openbank.service-graph/v1',
  generator: 'generate-service-graph.mjs',
  source: 'code-derived (application.yaml: quarkus.rest-client + mp.messaging + infra/3rd-party tiers) — ADR-0029 D1',
  collectedAt: null,
  totals: {
    nodes: modules.length,
    restEdges: restEdges.length,
    kafkaEdges: kafkaEdges.length,
    danglingTopics: danglingTopics.length,
    infraNodes: infraNodes.length,
    externalNodes: externalNodes.length,
    infraEdges: infraEdges.length,
    externalEdges: externalEdges.length,
  },
  nodes: modules.map(n => ({ name: n, short: n.replace(/^openbank-/, ''), ...degree[n] })),
  edges,
  danglingTopics,
  // Additive data-flow tiers (see header). Existing consumers ignore these.
  infraNodes,
  externalNodes,
  infraEdges,
  externalEdges,
}

writeFileSync(OUT, JSON.stringify(graph, null, 2) + '\n')
console.log(
  `[generate-service-graph] ${modules.length} nodes, ${restEdges.length} REST + ${kafkaEdges.length} Kafka edges, ` +
  `${danglingTopics.length} dangling topics · ${infraNodes.length} infra + ${externalNodes.length} external nodes ` +
  `(${infraEdges.length} infra + ${externalEdges.length} external edges) → ${OUT}`,
)
