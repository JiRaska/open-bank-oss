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
// (OPA, Keycloak, datasources) are dropped — only inter-service edges are kept.
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

const restEdges = []                 // {from, to, via}
const producers = new Map()          // topic -> Set(service)
const consumers = new Map()          // topic -> Set(service)

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
  source: 'code-derived (application.yaml: quarkus.rest-client + mp.messaging) — ADR-0029 D1',
  collectedAt: null,
  totals: {
    nodes: modules.length,
    restEdges: restEdges.length,
    kafkaEdges: kafkaEdges.length,
    danglingTopics: danglingTopics.length,
  },
  nodes: modules.map(n => ({ name: n, short: n.replace(/^openbank-/, ''), ...degree[n] })),
  edges,
  danglingTopics,
}

writeFileSync(OUT, JSON.stringify(graph, null, 2) + '\n')
console.log(`[generate-service-graph] ${modules.length} nodes, ${restEdges.length} REST + ${kafkaEdges.length} Kafka edges, ${danglingTopics.length} dangling topics → ${OUT}`)
