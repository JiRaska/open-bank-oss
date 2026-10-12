// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

export const TEMPORAL_MIGRATION_SOURCE = 'docs/adr/0101-temporal-durable-execution.md'

// ADR-0101 predates the canonical Gradle module names. Keep only proven
// historical spellings here; any new unresolved name fails catalog generation.
const LEGACY_MODULE_NAMES = {
  'sepa-payment-service': 'sepa-payment',
  'domestic-payment-service': 'domestic-payment',
  'statements-service': 'statement-service',
}

export function deriveTemporalMigrationPhases(markdown, moduleNames) {
  if (typeof markdown !== 'string') throw new Error(`${TEMPORAL_MIGRATION_SOURCE} is required`)
  const rows = markdown.split('\n').map(line => /^\|\s*(P[0-5])\s*\|\s*([^|]+)\|/.exec(line)).filter(Boolean)
  const expected = ['P0', 'P1', 'P2', 'P3', 'P4', 'P5']
  if (rows.length !== expected.length || rows.some((row, index) => row[1] !== expected[index])) {
    throw new Error('ADR-0101 migration phase table must contain P0-P5 exactly once and in order')
  }

  const phases = rows.map(([, id, rawScope]) => {
    const scope = rawScope.trim()
    const tokens = [...scope.matchAll(/`([^`]+)`/g)].map(match => match[1])
      .filter(token => token.endsWith('-service') || token === 'openbank-libs/temporal')
    // P5 names the libraries in prose while the two code spans name tables/classes.
    if (id === 'P5' && /\bin libs\b/.test(scope)) tokens.push('libs')
    if (tokens.length === 0) throw new Error(`ADR-0101 ${id} has no resolvable module scope`)

    const services = tokens.map(token => {
      const raw = token === 'openbank-libs/temporal' ? 'libs' : token
      const name = LEGACY_MODULE_NAMES[raw] ?? raw
      const moduleName = name.startsWith('openbank-') ? name : `openbank-${name}`
      if (!moduleNames.has(moduleName)) throw new Error(`ADR-0101 ${id} names unknown module ${token} (${moduleName})`)
      return moduleName
    })
    if (new Set(services).size !== services.length) throw new Error(`ADR-0101 ${id} repeats a module`)
    return { id, services }
  })
  return { source: TEMPORAL_MIGRATION_SOURCE, phases }
}
