// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Generate the developer-docs Kafka topic table from `docs/asyncapi/openbank-events.yaml`
// (the fleet's published event document) cross-referenced with the actual
// `mp.messaging.*` config each service declares in its own `application.yaml`.
//
// WHY THIS EXISTS: `src/app/docs/api/page.tsx` used to hand-maintain a TypeScript
// array of { topic, publisher, consumers } literals. It drifted from the AsyncAPI
// document the same way that document itself drifted (#4761) — 15 of ~23 topic
// names went stale, and several publisher attributions were wrong (party events
// are published by party-service, not pid-service). Retyping the corrected 15
// names would only recreate the drift with a fresh timestamp; this script derives
// the table directly from the AsyncAPI document instead of a second, ungated copy
// going stale again.
//
// publisher/consumer attribution comes from the SAME source
// `check-event-contract-code-agreement.py` trusts (ADR-0006): each service's own
// `mp.messaging.outgoing.*.topic` (publisher) and
// `mp.messaging.incoming.*.topic` / `.topics` (consumer, comma-separated fan-in —
// audit-service subscribes to ~21 topics that way). This is stronger than eyeballing
// the spec, because it is derived from what services actually declare, not prose.
//
// Honest by construction: a channel address with no matching application.yaml
// declaration ships with empty publishers/consumers arrays (never a fabricated
// service name) and is flagged in `unmatched` so a maintainer notices without the
// page silently lying.
//
// Usage: node scripts/generate-events.mjs [--repo <path>] [--out <file>]
// Defaults: repo = parent of admin-ui, out = ./events.json

import { readFileSync, writeFileSync, readdirSync, statSync } from 'fs'
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
const OUT = path.resolve(getArg('--out', path.resolve(__dirname, '..', 'events.json')))
const DOC = path.join(REPO, 'docs', 'asyncapi', 'openbank-events.yaml')

function readText(p) {
  try { return readFileSync(p, 'utf-8') } catch { return null }
}

// Every topic each in-tree service publishes to / consumes from, derived from its
// own application.yaml, kept per-service instead of collapsed to one set.
function messagingTopics(root) {
  const publishers = new Map() // topic -> Set(service short name)
  const consumers = new Map()
  const serviceDirs = readdirSync(root)
    .filter(n => n.startsWith('openbank-'))
    .filter(n => { try { return statSync(path.join(root, n)).isDirectory() } catch { return false } })
    .sort()
  for (const dirName of serviceDirs) {
    const serviceName = dirName.replace(/^openbank-/, '')
    const configPath = path.join(dirName, 'src', 'main', 'resources', 'application.yaml')
    const raw = readText(path.join(root, configPath))
    if (!raw) continue
    let doc
    try { doc = parseYaml(raw) } catch { continue }
    const messaging = doc?.mp?.messaging
    if (!messaging || typeof messaging !== 'object') continue

    const addTopics = (channels, target) => {
      if (!channels || typeof channels !== 'object') return
      for (const cfg of Object.values(channels)) {
        if (!cfg || typeof cfg !== 'object') continue
        if (cfg.topic) {
          const t = String(cfg.topic)
          if (!target.has(t)) target.set(t, new Set())
          target.get(t).add(serviceName)
        }
        // Fan-in consumer subscribes with `topics:` (comma-separated), not `topic:`.
        if (cfg.topics) {
          for (const t of String(cfg.topics).split(',').map(s => s.trim()).filter(Boolean)) {
            if (!target.has(t)) target.set(t, new Set())
            target.get(t).add(serviceName)
          }
        }
      }
    }
    addTopics(messaging.outgoing, publishers)
    addTopics(messaging.incoming, consumers)
  }
  return { publishers, consumers }
}

const COLORS = {
  accounts: '#2563eb', ledger: '#2563eb', balances: '#2563eb', fx: '#2563eb',
  payments: '#7c3aed', transactions: '#7c3aed', clearing: '#7c3aed', 'standing-orders': '#7c3aed',
  kyc: '#dc2626', aml: '#dc2626', sanctions: '#dc2626',
  parties: '#059669', interest: '#059669',
  consents: '#d97706',
  cards: '#db2777', disputes: '#db2777',
  audit: '#6b7280', notifications: '#6b7280',
}
function colorFor(topic) {
  const domain = topic.split('.')[1]
  return COLORS[domain] ?? '#6b7280'
}

function main() {
  const raw = readText(DOC)
  if (!raw) {
    writeFileSync(OUT, JSON.stringify({
      schema: 'openbank.events/v1',
      generator: 'generate-events.mjs',
      source: DOC.replace(REPO + path.sep, ''),
      available: false,
      collectedAt: null,
      topics: [],
      unmatched: [],
    }, null, 2) + '\n')
    console.log(`[generate-events] ${DOC} not found — wrote empty, honest envelope`)
    return
  }

  let doc
  try { doc = parseYaml(raw) } catch (e) {
    console.error(`[generate-events] failed to parse ${DOC}: ${e.message}`)
    process.exitCode = 1
    return
  }

  const channels = doc?.channels ?? {}
  const { publishers, consumers } = messagingTopics(REPO)

  const topics = []
  const unmatched = []
  for (const [name, channel] of Object.entries(channels)) {
    if (!channel || typeof channel !== 'object') continue
    const address = channel.address
    if (!address) continue
    const pub = [...(publishers.get(address) ?? [])].sort()
    const cons = [...(consumers.get(address) ?? [])].sort()
    if (pub.length === 0 && cons.length === 0) unmatched.push(address)
    topics.push({
      channel: name,
      topic: address,
      description: channel.description ?? null,
      publishers: pub,
      consumers: cons,
      color: colorFor(address),
    })
  }
  topics.sort((a, b) => a.topic.localeCompare(b.topic))

  const events = {
    schema: 'openbank.events/v1',
    generator: 'generate-events.mjs',
    source: 'docs/asyncapi/openbank-events.yaml cross-referenced with each service\'s mp.messaging.* application.yaml',
    collectedAt: null,
    topics,
    unmatched,
  }

  writeFileSync(OUT, JSON.stringify(events, null, 2) + '\n')
  console.log(`[generate-events] ${topics.length} channels → ${OUT}`)
  if (unmatched.length > 0) {
    console.log(`[generate-events] ${unmatched.length} address(es) with no publisher/consumer found in-tree:`)
    for (const t of unmatched) console.log(`  - ${t}`)
  }
}

main()
