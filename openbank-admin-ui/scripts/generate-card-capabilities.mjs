// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Bakes openbank-libs/governance/card-capabilities.yaml into card-capabilities.json for the
// Card Center (ADR-0283 phase 3, issue #8811).
//
// WHY BAKE IT RATHER THAN READ THE YAML AT RUNTIME
//   The admin UI ships as a container that does not carry the monorepo, so a runtime read of a
//   path outside the app would work in development and fail in the cluster — the class of defect
//   that only shows up after a deploy. Every other derived surface here is baked the same way
//   (governance.json, catalog.json), and the same rule applies: edit the registry, never the JSON.
//
// WHAT IT REFUSES TO DO
//   It does not invent a fallback. If the registry is missing or unparseable the script FAILS,
//   rather than writing an empty file — an empty capability matrix renders as "this platform
//   supports nothing", which is a confident lie, and the screen cannot tell it apart from a
//   registry that genuinely lists nothing.
//
// Usage: node scripts/generate-card-capabilities.mjs [--repo <path>] [--out <file>]

import { readFileSync, writeFileSync, existsSync } from 'fs'
import path from 'path'
import { parse as parseYaml } from 'yaml'

const REGISTRY = path.join('openbank-libs', 'governance', 'card-capabilities.yaml')

function arg(name, fallback) {
  const i = process.argv.indexOf(name)
  return i > -1 && process.argv[i + 1] ? process.argv[i + 1] : fallback
}

const repo = path.resolve(arg('--repo', path.join(process.cwd(), '..')))
const out = path.resolve(arg('--out', path.join(process.cwd(), 'card-capabilities.json')))
const registryPath = path.join(repo, REGISTRY)

if (!existsSync(registryPath)) {
  console.error(
    `generate-card-capabilities: ${REGISTRY} not found under ${repo}. Refusing to write an empty ` +
    'matrix — a screen listing no capabilities reads as "this platform supports nothing".',
  )
  process.exit(1)
}

const registry = parseYaml(readFileSync(registryPath, 'utf-8'))
const networks = Object.entries(registry.networks ?? {}).map(([id, n]) => ({
  id,
  label: n.label,
  developerPortal: n.developer_portal,
  sandboxAuth: n.sandbox_auth,
}))

if (networks.length === 0 || (registry.capabilities ?? []).length === 0) {
  console.error('generate-card-capabilities: the registry declares no networks or no capabilities')
  process.exit(1)
}

const capabilities = registry.capabilities.map(c => ({
  id: c.id,
  label: c.label,
  // The Kotlin interface, short name only — the screen shows what a reader can search for.
  port: c.port ? String(c.port).split('.').pop() : null,
  portFqn: c.port ?? null,
  why: (c.why ?? '').split('\n').map(s => s.trim()).filter(Boolean).join(' '),
  // The honest column: what THIS repository implements, not what the network offers.
  bindings: c.bindings ?? [],
  networks: Object.fromEntries(
    Object.entries(c.networks ?? {}).map(([id, n]) => [id, { product: n.product, availability: n.availability }]),
  ),
}))

const payload = {
  schema: 'openbank.card-capabilities/v1',
  generatedFrom: REGISTRY,
  networks,
  capabilities,
}

writeFileSync(out, `${JSON.stringify(payload, null, 2)}\n`, 'utf-8')
console.log(
  `generate-card-capabilities: wrote ${path.relative(process.cwd(), out)} ` +
  `(${capabilities.length} capabilities, ${networks.length} networks)`,
)
