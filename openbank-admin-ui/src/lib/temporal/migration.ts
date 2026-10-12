// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

const SOURCE = 'docs/adr/0101-temporal-durable-execution.md'
const PHASE_IDS = ['P0', 'P1', 'P2', 'P3', 'P4', 'P5'] as const

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null && !Array.isArray(value)

/** Fail closed if the baked ADR scope or its catalog membership is incomplete. */
export function parseTemporalMigrationCatalog(raw: unknown): Map<string, string[]> | null {
  if (!isRecord(raw) || raw.available !== true || !Array.isArray(raw.services)) return null
  const known = new Set(raw.services.filter(isRecord).map(service => service.name).filter((name): name is string => typeof name === 'string'))
  const migration = raw.temporalMigration
  if (!isRecord(migration) || migration.source !== SOURCE || !Array.isArray(migration.phases)) return null
  if (migration.phases.length !== PHASE_IDS.length) return null
  const phases = new Map<string, string[]>()
  for (const [index, phase] of migration.phases.entries()) {
    if (!isRecord(phase) || phase.id !== PHASE_IDS[index] || !Array.isArray(phase.services) || phase.services.length === 0) return null
    if (!phase.services.every((name): name is string => typeof name === 'string' && known.has(name))) return null
    if (new Set(phase.services).size !== phase.services.length) return null
    phases.set(PHASE_IDS[index], phase.services)
  }
  return phases
}
