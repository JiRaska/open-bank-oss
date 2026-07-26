// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { beforeAll, describe, expect, it } from 'vitest'
import { mkdtempSync, mkdirSync, rmSync, writeFileSync, readFileSync } from 'fs'
import { tmpdir } from 'os'
import path from 'path'
import { fileURLToPath } from 'url'
// @ts-expect-error - plain .mjs build script, no type declarations by design
import { buildManifest } from '../../scripts/generate-governance.mjs'
// @ts-expect-error - plain .mjs build script, no type declarations by design
import { jsonSchema } from '../../scripts/governance-schema.mjs'

// The ADR-0071 governance gate, tested on its FAILURE path — which is where every defect it
// has ever had lived (issue #2165: two of them, both invisible while the fleet was green).
//
// Every rule below has a fixture that MUST make it fire. A gate whose failure path is never
// exercised is indistinguishable from a gate that passes everything, and this one twice was:
//   - it demanded schemaName from modules that own no database, so they invented `n/a`;
//   - its CI reporter read a `.modules` field the manifest never had, so it threw instead of
//     naming the offender;
//   - and the field it did enforce (`schemaName`) named a Postgres schema that existed in no
//     migration and no config anywhere in the fleet — 51 modules declaring a fiction, checked
//     by nobody. ADR-0196 replaced it with `databaseName`, which is cross-checked against the
//     datasource URL in the tree, so the same class of fiction cannot come back silently.
//
// The generator is imported, never spawned: `buildManifest` is the pure part and the script's
// CLI half only runs under a `process.argv[1]` entrypoint guard. Spawning a node subprocess
// per case measurably slowed the shared vitest pool and tipped unrelated render-smoke tests
// over their 5 s timeout.

interface Manifest {
  totals: { modules: number; withGaps: number; unverifiedDatabaseNames: number }
  gaps: string[]
  services: Array<{
    serviceName: string
    databaseName: string | null
    databaseNameEvidence: 'derived' | 'declared-only' | null
    ownsNoDatabase?: true
  }>
}

interface Fixture {
  /** governance.yaml body; null = the file is absent entirely. */
  yaml: string | null
  /** Flyway migration filenames to create under src/main/resources/db/migration. */
  migrations?: string[]
  /** application.yaml body (where the datasource URL and `redis:` block live). */
  appYaml?: string
  /** package.json body — a `pg` dependency is a non-Quarkus module's Postgres evidence. */
  pkg?: string
}

const STATEFUL = `dataDomain: core
primaryDatastore: PostgreSQL
databaseName: openbank_widgets
dataLineageRole: both
dataClassification: confidential
retentionPolicy: 10 years
`

const OWNS_NO_DB = `dataDomain: payments
primaryDatastore: none
ownsNoDatabase: true
dataLineageRole: consumer
dataClassification: confidential
retentionPolicy: not applicable
`

const WIDGETS_DS = `quarkus:
  datasource:
    jdbc:
      url: jdbc:postgresql://localhost:5432/openbank_widgets
`

/** The canonical healthy stateful module: declaration and code agree. */
const OK_STATEFUL: Fixture = { yaml: STATEFUL, migrations: ['V1__init.sql'], appYaml: WIDGETS_DS }

function buildRepo(modules: Record<string, Fixture>): Manifest {
  const repo = mkdtempSync(path.join(tmpdir(), 'gov-gate-'))
  try {
    for (const [name, f] of Object.entries(modules)) {
      const dir = path.join(repo, name)
      mkdirSync(dir, { recursive: true })
      writeFileSync(path.join(dir, 'version.txt'), '1.0.0\n') // released-component marker
      if (f.yaml != null) writeFileSync(path.join(dir, 'governance.yaml'), f.yaml)
      if (f.migrations?.length) {
        const md = path.join(dir, 'src', 'main', 'resources', 'db', 'migration')
        mkdirSync(md, { recursive: true })
        for (const mig of f.migrations) writeFileSync(path.join(md, mig), '-- test\n')
      }
      if (f.appYaml != null) {
        const rd = path.join(dir, 'src', 'main', 'resources')
        mkdirSync(rd, { recursive: true })
        writeFileSync(path.join(rd, 'application.yaml'), f.appYaml)
      }
      if (f.pkg != null) writeFileSync(path.join(dir, 'package.json'), f.pkg)
    }
    return buildManifest(repo) as Manifest
  } finally {
    rmSync(repo, { recursive: true, force: true })
  }
}

const gapFor = (m: Manifest, name: string) => m.gaps.filter(g => g.startsWith(`${name}:`))
const onlyGap = (m: Manifest, name: string) => {
  const gaps = gapFor(m, name)
  expect(gaps, `expected exactly one gap for ${name}, got ${JSON.stringify(gaps)}`).toHaveLength(1)
  return gaps[0]
}
/** For fixtures that legitimately trip more than one rule: assert THIS rule is among them. */
const someGap = (m: Manifest, name: string, needle: string) => {
  const gaps = gapFor(m, name)
  expect(gaps.join('\n'), `no gap for ${name} mentioned "${needle}"`).toContain(needle)
  return gaps
}

/** A retention statement that never trips the GDPR rule, for fixtures testing something else. */
const withRetention = (yaml: string) => yaml.replace(/^retentionPolicy:.*$/m, 'retentionPolicy: 1 year')

describe('governance gate — shape rules (scripts/governance-schema.mjs)', () => {
  let m: Manifest

  beforeAll(() => {
    m = buildRepo({
      'openbank-ok-stateful': OK_STATEFUL,
      'openbank-ok-owns-nothing': { yaml: OWNS_NO_DB },
      'openbank-no-databasename': { yaml: STATEFUL.replace(/^databaseName:.*\n/m, ''), migrations: ['V1__init.sql'] },
      'openbank-owns-nothing-with-database': { yaml: OWNS_NO_DB + 'databaseName: openbank_contradiction\n' },
      'openbank-flag-false': { yaml: STATEFUL + 'ownsNoDatabase: false\n', migrations: ['V1__init.sql'] },
      'openbank-placeholder-database': { yaml: STATEFUL.replace('openbank_widgets', 'n/a'), migrations: ['V1__init.sql'] },
      'openbank-unknown-key': { yaml: STATEFUL + 'schemaName: widgets_schema\n', migrations: ['V1__init.sql'] },
      'openbank-bad-datastore': { yaml: STATEFUL.replace('PostgreSQL', 'Cassandra'), migrations: ['V1__init.sql'] },
      'openbank-no-yaml': { yaml: null },
    })
  })

  it('leaves a compliant stateful module and a compliant owns-nothing module ungapped', () => {
    expect(gapFor(m, 'openbank-ok-stateful')).toEqual([])
    expect(gapFor(m, 'openbank-ok-owns-nothing')).toEqual([])
  })

  it('flags a module that omits databaseName WITHOUT asserting that it owns none', () => {
    const gap = onlyGap(m, 'openbank-no-databasename')
    expect(gap).toContain('missing databaseName')
    expect(gap).toContain('ownsNoDatabase: true') // the remedy is spelled out in the message
  })

  it('flags the contradiction of `ownsNoDatabase: true` alongside a databaseName', () => {
    const gap = onlyGap(m, 'openbank-owns-nothing-with-database')
    expect(gap).toContain('ownsNoDatabase')
    expect(gap).toContain('openbank_contradiction')
  })

  it('rejects `ownsNoDatabase: false` — the flag is an assertion, not a tri-state', () => {
    expect(onlyGap(m, 'openbank-flag-false')).toContain("ownsNoDatabase must be 'true' or omitted")
  })

  it('rejects a placeholder databaseName — the exact dodge the old gate forced on modules that own none', () => {
    // 'n/a' trips both the placeholder rule and the identifier-format rule; the placeholder
    // message is the one that tells a contributor what to do instead.
    expect(someGap(m, 'openbank-placeholder-database', 'placeholder').join('\n')).toContain('ownsNoDatabase')
  })

  it('rejects an unknown key, so the removed `schemaName` cannot quietly reappear', () => {
    expect(onlyGap(m, 'openbank-unknown-key')).toContain('schemaName')
  })

  it('rejects a datastore outside the closed enum', () => {
    expect(onlyGap(m, 'openbank-bad-datastore')).toContain('primaryDatastore')
  })

  it('still flags a module with no governance.yaml at all', () => {
    expect(gapFor(m, 'openbank-no-yaml')).toEqual(['openbank-no-yaml: missing governance.yaml'])
  })
})

describe('governance gate — truth rules (declaration vs the code, ADR-0196)', () => {
  it('flags `ownsNoDatabase: true` on a module that owns Flyway migrations', () => {
    // The exact shape finops-agent and psd2-service shipped: "stateless" over real migrations.
    const m = buildRepo({
      'openbank-lying-flag': {
        yaml: withRetention(OWNS_NO_DB.replace('primaryDatastore: none', 'primaryDatastore: PostgreSQL')),
        migrations: ['V1__init.sql', 'V2__more.sql'],
      },
    })
    const gap = onlyGap(m, 'openbank-lying-flag')
    expect(gap).toContain('2 Flyway migration')
    expect(gap).toContain('databaseName')
  })

  it('flags a declared databaseName on a module that owns no migrations', () => {
    const m = buildRepo({ 'openbank-phantom-db': { yaml: STATEFUL, appYaml: WIDGETS_DS } })
    expect(onlyGap(m, 'openbank-phantom-db')).toContain('owns no Flyway migrations')
  })

  it('flags a non-Postgres datastore on a module with Postgres migrations', () => {
    const m = buildRepo({
      'openbank-wrong-store': {
        yaml: STATEFUL.replace('primaryDatastore: PostgreSQL', 'primaryDatastore: Redis'),
        migrations: ['V1__init.sql'],
        appYaml: WIDGETS_DS + '  redis:\n    hosts: redis://localhost:6379\n',
      },
    })
    expect(onlyGap(m, 'openbank-wrong-store')).toContain('the primary store is PostgreSQL')
  })

  it('flags a databaseName that disagrees with the datasource URL', () => {
    const m = buildRepo({
      'openbank-mismatch': {
        yaml: STATEFUL,
        migrations: ['V1__init.sql'],
        appYaml: WIDGETS_DS.replace('openbank_widgets', 'openbank_something_else'),
      },
    })
    const gap = onlyGap(m, 'openbank-mismatch')
    expect(gap).toContain('openbank_widgets')
    expect(gap).toContain('openbank_something_else')
  })

  it("flags primaryDatastore 'none' on a module wired to a store", () => {
    const m = buildRepo({
      'openbank-secret-redis': { yaml: OWNS_NO_DB, appYaml: 'quarkus:\n  redis:\n    hosts: redis://localhost:6379\n' },
    })
    expect(onlyGap(m, 'openbank-secret-redis')).toContain('wired to Redis')
  })

  it("flags primaryDatastore 'Redis' with no Redis configured anywhere", () => {
    const m = buildRepo({
      'openbank-phantom-redis': { yaml: withRetention(OWNS_NO_DB.replace('primaryDatastore: none', 'primaryDatastore: Redis')) },
    })
    expect(onlyGap(m, 'openbank-phantom-redis')).toContain('no Redis config exists')
  })

  it("flags primaryDatastore 'PostgreSQL' with no Postgres wiring", () => {
    const m = buildRepo({
      'openbank-phantom-pg': {
        yaml: withRetention(OWNS_NO_DB.replace('primaryDatastore: none', 'primaryDatastore: PostgreSQL')),
      },
    })
    expect(onlyGap(m, 'openbank-phantom-pg')).toContain('no Postgres wiring exists')
  })

  it('flags a placeholder retentionPolicy on a module that stores data (GDPR Art. 5(1)(e))', () => {
    const m = buildRepo({
      'openbank-no-retention': {
        yaml: OWNS_NO_DB.replace('primaryDatastore: none', 'primaryDatastore: Redis').replace('retentionPolicy: not applicable', 'retentionPolicy: N/A'),
        appYaml: 'quarkus:\n  redis:\n    hosts: redis://localhost:6379\n',
      },
    })
    expect(onlyGap(m, 'openbank-no-retention')).toContain('GDPR')
  })

  it('accepts `not applicable` retention on a module that stores nothing at all', () => {
    const m = buildRepo({ 'openbank-truly-storeless': { yaml: OWNS_NO_DB } })
    expect(gapFor(m, 'openbank-truly-storeless')).toEqual([])
  })

  it('ignores the %test/%it profile database, so an IT profile does not make a module unverifiable', () => {
    const m = buildRepo({
      'openbank-with-it-profile': {
        yaml: STATEFUL,
        migrations: ['V1__init.sql'],
        appYaml: WIDGETS_DS + `'%it':\n  quarkus:\n    datasource:\n      jdbc:\n        url: jdbc:postgresql://localhost:5432/openbank_widgets_it\n`,
      },
    })
    expect(gapFor(m, 'openbank-with-it-profile')).toEqual([])
    expect(m.services[0].databaseNameEvidence).toBe('derived')
  })
})

describe('governance gate — cross-fleet database lineage (ADR-0196)', () => {
  const withLineage = (body: string) => STATEFUL + body

  it('flags ownedDatabases that disagrees with the module’s own databaseName', () => {
    const m = buildRepo({
      'openbank-ok-stateful': OK_STATEFUL,
      'openbank-bad-owned': {
        ...OK_STATEFUL,
        yaml: withLineage('databaseLineage:\n  ownedDatabases:\n    - openbank_elsewhere\n'),
      },
    })
    expect(onlyGap(m, 'openbank-bad-owned')).toContain('contradicts databaseName')
  })

  it('flags a dependentDatabases entry no module in the fleet owns', () => {
    const m = buildRepo({
      'openbank-ok-stateful': OK_STATEFUL,
      'openbank-bad-dep': {
        ...OK_STATEFUL,
        yaml: withLineage('databaseLineage:\n  dependentDatabases:\n    - openbank_ghost\n'),
      },
    })
    expect(onlyGap(m, 'openbank-bad-dep')).toContain('openbank_ghost')
  })

  it('accepts a dependentDatabases entry another module really owns', () => {
    const other = `dataDomain: core
primaryDatastore: PostgreSQL
databaseName: openbank_other
dataLineageRole: producer
dataClassification: internal
retentionPolicy: 5 years
`
    const m = buildRepo({
      'openbank-other': { yaml: other, migrations: ['V1__init.sql'], appYaml: WIDGETS_DS.replace('openbank_widgets', 'openbank_other') },
      'openbank-dependent': {
        ...OK_STATEFUL,
        yaml: withLineage('databaseLineage:\n  dependentDatabases:\n    - openbank_other\n'),
      },
    })
    expect(m.gaps).toEqual([])
  })
})

describe('governance gate — the manifest the CI reporter reads (issue #2165)', () => {
  it('emits a top-level `gaps` ARRAY matching totals.withGaps and naming every module', () => {
    const m = buildRepo({
      'openbank-ok-stateful': OK_STATEFUL,
      'openbank-no-databasename': { yaml: STATEFUL.replace(/^databaseName:.*\n/m, ''), migrations: ['V1__init.sql'] },
      'openbank-no-yaml': { yaml: null },
    })
    expect(Array.isArray(m.gaps)).toBe(true)
    expect(m.gaps).toHaveLength(m.totals.withGaps)
    for (const name of ['openbank-no-databasename', 'openbank-no-yaml']) {
      expect(m.gaps.join('\n')).toContain(name)
    }
  })

  it('emits `gaps: []` (present, not undefined) when nothing is wrong', () => {
    const clean = buildRepo({ 'openbank-ok-stateful': OK_STATEFUL, 'openbank-ok-owns-nothing': { yaml: OWNS_NO_DB } })
    expect(clean.totals).toMatchObject({ modules: 2, withGaps: 0 })
    expect(clean.gaps).toEqual([])
  })

  it('records database ownership and the strength of the databaseName evidence', () => {
    const m = buildRepo({
      'openbank-ok-stateful': OK_STATEFUL,
      'openbank-ok-owns-nothing': { yaml: OWNS_NO_DB },
      // Migrations but no datasource URL anywhere: the name is a claim nobody can confirm.
      'openbank-unconfirmable': { yaml: STATEFUL, migrations: ['V1__init.sql'] },
    })
    expect(m.services.find(s => s.serviceName === 'ok-owns-nothing')).toMatchObject({
      ownsNoDatabase: true,
      databaseName: null,
      databaseNameEvidence: null,
    })
    expect(m.services.find(s => s.serviceName === 'ok-stateful')).toMatchObject({
      databaseName: 'openbank_widgets',
      databaseNameEvidence: 'derived',
    })
    expect(m.services.find(s => s.serviceName === 'ok-stateful')?.ownsNoDatabase).toBeUndefined()
    expect(m.services.find(s => s.serviceName === 'unconfirmable')?.databaseNameEvidence).toBe('declared-only')
    expect(m.totals.unverifiedDatabaseNames).toBe(1)
  })
})

describe('governance.schema.json is derived, not hand-written', () => {
  it('matches what scripts/governance-schema.mjs emits', () => {
    const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..', '..')
    const onDisk = readFileSync(path.join(repoRoot, 'openbank-libs', 'governance', 'governance.schema.json'), 'utf-8')
    expect(JSON.parse(onDisk)).toEqual(jsonSchema())
  })
})
