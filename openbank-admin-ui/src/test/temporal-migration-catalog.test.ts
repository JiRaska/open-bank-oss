// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { describe, expect, it } from 'vitest'
import { parseTemporalMigrationCatalog } from '@/lib/temporal/migration'

const names = [
  'openbank-libs', 'openbank-sepa-payment', 'openbank-domestic-payment',
  'openbank-settlement-service', 'openbank-fx-service', 'openbank-statement-service',
]
const phases = [
  { id: 'P0', services: [names[0]] },
  { id: 'P1', services: [names[1]] },
  { id: 'P2', services: [names[2]] },
  { id: 'P3', services: [names[3]] },
  { id: 'P4', services: [names[4], names[5]] },
  { id: 'P5', services: [names[0]] },
]
const catalog = {
  available: true,
  services: names.map(name => ({ name })),
  temporalMigration: { source: 'docs/adr/0101-temporal-durable-execution.md', phases },
}

describe('Temporal migration catalog', () => {
  it('accepts the complete canonical P0–P5 scope', () => {
    const result = parseTemporalMigrationCatalog(catalog)
    expect(result).not.toBeNull()
    expect(result?.get('P4')).toEqual(['openbank-fx-service', 'openbank-statement-service'])
    expect(result?.get('P5')).toEqual(['openbank-libs'])
  })

  it('fails closed when a phase, source, or catalog service is unresolved', () => {
    expect(parseTemporalMigrationCatalog({ ...catalog, temporalMigration: { ...catalog.temporalMigration, phases: phases.slice(0, 5) } })).toBeNull()
    expect(parseTemporalMigrationCatalog({ ...catalog, temporalMigration: { ...catalog.temporalMigration, source: 'unknown' } })).toBeNull()
    expect(parseTemporalMigrationCatalog({ ...catalog, services: catalog.services.slice(0, -1) })).toBeNull()
    expect(parseTemporalMigrationCatalog({ ...catalog, available: false })).toBeNull()
  })
})
