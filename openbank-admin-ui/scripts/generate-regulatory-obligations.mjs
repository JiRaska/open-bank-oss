// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Bakes openbank-libs/governance/regulatory-obligations.yaml into regulatory-obligations.json for
// the Regulatory → Obligations screen (ADR-0367).
//
// Baked rather than read at runtime for the same reason as card-capabilities.json: the image does
// not carry the monorepo, so a runtime read of a path outside the app works in development and
// fails in the cluster. Edit the register, never the JSON.
//
// It refuses to write an empty file: a screen listing no obligations reads as "the bank owes the
// regulators nothing", which is a confident lie the reader cannot tell from the truth.
//
// Usage: node scripts/generate-regulatory-obligations.mjs [--repo <path>] [--out <file>]

import { readFileSync, writeFileSync, existsSync } from 'fs'
import path from 'path'
import { parse as parseYaml } from 'yaml'

const REGISTER = path.join('openbank-libs', 'governance', 'regulatory-obligations.yaml')

function arg(name, fallback) {
  const i = process.argv.indexOf(name)
  return i > -1 && process.argv[i + 1] ? process.argv[i + 1] : fallback
}

const repo = path.resolve(arg('--repo', path.join(process.cwd(), '..')))
const out = path.resolve(arg('--out', path.join(process.cwd(), 'regulatory-obligations.json')))
const registerPath = path.join(repo, REGISTER)

if (!existsSync(registerPath)) {
  console.error(`generate-regulatory-obligations: ${REGISTER} not found under ${repo}. Refusing to write an empty register.`)
  process.exit(1)
}

const reg = parseYaml(readFileSync(registerPath, 'utf-8'))
const obligations = (reg.obligations ?? []).map(o => ({
  id: o.id,
  area: o.area,
  name: o.name,
  authority: o.authority,
  legalBasis: o.legal_basis,
  sources: { law: o.sources?.law ?? null, spec: o.sources?.spec ?? null },
  cadence: o.cadence,
  cadenceConfidence: o.cadence_confidence,
  kind: o.kind,
  transport: o.transport,
  personalData: String(o.personal_data),
  applicability: String(o.applicability),
  coverage: {
    status: o.coverage?.status,
    reports: o.coverage?.reports ?? [],
    services: o.coverage?.services ?? [],
    tracking: o.coverage?.tracking ?? null,
  },
  note: o.note ?? null,
}))

if (obligations.length === 0) {
  console.error('generate-regulatory-obligations: the register declares no obligations')
  process.exit(1)
}

const authorities = Object.entries(reg.authorities ?? {}).map(([id, a]) => ({ id, cs: a.cs, en: a.en, url: a.url ?? null }))
const areas = (reg.areas ?? []).map(a => ({ id: a.id, cs: a.cs, en: a.en }))

writeFileSync(out, JSON.stringify({ schemaVersion: reg.schema_version, areas, authorities, obligations }, null, 2) + '\n')
console.log(`generate-regulatory-obligations: ${obligations.length} obligations -> ${path.relative(process.cwd(), out)}`)
