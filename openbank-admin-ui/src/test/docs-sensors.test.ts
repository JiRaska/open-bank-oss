// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Guards for the /docs/sensors catalogue.
//
// The catalogue is hand-curated prose about a DIFFERENT repository (openbank-app),
// so nothing here can check that a description is true. What it can check is the
// two ways this page rots without anyone noticing: an entry that stops naming the
// file it is derived from (the only pointer back to the evidence), and a family
// whose subpage exists but has nothing in it — or entries in a family with no
// subpage, which renders nowhere and reads as "we do not do that".

import { existsSync, readdirSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import { FAMILY_ORDER, FAMILY_META, SENSORS, sensorsByFamily } from '@/lib/docs/sensors'

const ROUTE_DIR = 'src/app/docs/sensors'

describe('sensor catalogue (docs/sensors)', () => {
  it('has a unique id per entry', () => {
    const ids = SENSORS.map(s => s.id)
    expect(ids.length).toBe(new Set(ids).size)
  })

  it('names an implementation path for every entry that claims to exist', () => {
    // A `planned` entry has nothing to point at — that is the point of the status.
    // Anything else must carry the openbank-app path that is the evidence for it.
    const unsourced = SENSORS
      .filter(s => s.status !== 'planned')
      .filter(s => !s.source || s.source === '—')
      .map(s => s.id)

    expect(unsourced).toEqual([])
  })

  it('states a limit for every entry', () => {
    // None of these features is complete on both platforms. An entry with an empty
    // `gap` is the shape that quietly sells the target as the reality.
    const noGap = SENSORS.filter(s => !s.gap.cs.trim() || !s.gap.en.trim()).map(s => s.id)
    expect(noGap).toEqual([])
  })

  it('declares at least one platform per entry', () => {
    expect(SENSORS.filter(s => s.platforms.length === 0).map(s => s.id)).toEqual([])
  })

  it('gives every family a subpage, and every subpage entries', () => {
    for (const family of FAMILY_ORDER) {
      expect(existsSync(`${ROUTE_DIR}/${family}/page.tsx`), `${family} subpage`).toBe(true)
      expect(sensorsByFamily(family).length, `${family} entries`).toBeGreaterThan(0)
    }
  })

  it('has no subpage route outside the declared families', () => {
    const routes = readdirSync(ROUTE_DIR, { withFileTypes: true })
      .filter(e => e.isDirectory())
      .map(e => e.name)

    expect(routes.sort()).toEqual([...FAMILY_ORDER].sort())
  })

  it('assigns every entry to a declared family', () => {
    const declared = new Set(FAMILY_ORDER)
    expect(SENSORS.filter(s => !declared.has(s.family)).map(s => s.id)).toEqual([])
    expect(Object.keys(FAMILY_META).sort()).toEqual([...FAMILY_ORDER].sort())
  })

  it('is bilingual everywhere — no entry falls back to one language', () => {
    const monolingual = SENSORS.filter(s =>
      [s.title, s.signal, s.useCase, s.invocation, s.where, s.value, s.gap]
        .some(f => !f.cs.trim() || !f.en.trim()),
    ).map(s => s.id)

    expect(monolingual).toEqual([])
  })
})
