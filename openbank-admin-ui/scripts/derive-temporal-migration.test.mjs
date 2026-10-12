// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import assert from 'node:assert/strict'
import { readFileSync, readdirSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { test } from 'node:test'
import { deriveTemporalMigrationPhases, TEMPORAL_MIGRATION_SOURCE } from './derive-temporal-migration.mjs'

const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const markdown = readFileSync(path.join(repo, TEMPORAL_MIGRATION_SOURCE), 'utf8')
const modules = new Set(readdirSync(repo).filter(name => name.startsWith('openbank-')))

test('all ADR-0101 phase scopes resolve to real canonical modules', () => {
  const result = deriveTemporalMigrationPhases(markdown, modules)
  assert.equal(result.source, TEMPORAL_MIGRATION_SOURCE)
  assert.deepEqual(result.phases, [
    { id: 'P0', services: ['openbank-libs'] },
    { id: 'P1', services: ['openbank-sepa-payment'] },
    { id: 'P2', services: ['openbank-domestic-payment'] },
    { id: 'P3', services: ['openbank-settlement-service'] },
    { id: 'P4', services: ['openbank-fx-service', 'openbank-statement-service'] },
    { id: 'P5', services: ['openbank-libs'] },
  ])
})

test('unknown legacy names and missing phases fail catalog generation', () => {
  assert.throws(
    () => deriveTemporalMigrationPhases(
      markdown.replace('`fx-service`, `statements-service` EoD close', '`fx-service`, `unknown-service` EoD close'), modules,
    ),
    /unknown module unknown-service/,
  )
  assert.throws(
    () => deriveTemporalMigrationPhases(markdown.replace('| P3 |', '| PX |'), modules),
    /must contain P0-P5/,
  )
})
