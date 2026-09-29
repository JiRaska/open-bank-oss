// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const page = readFileSync(path.resolve(__dirname, '../app/lending/risk/page.tsx'), 'utf8')
const charts = readFileSync(path.resolve(__dirname, '../components/lending/risk/charts.tsx'), 'utf8')
const palette = readFileSync(path.resolve(__dirname, '../components/lending/risk/palette.ts'), 'utf8')

describe('credit risk chart loading boundary', () => {
  it('keeps Recharts out of loading, denial and tab-navigation bundles', () => {
    expect(page).not.toContain("from '@/components/lending/risk/charts'")
    expect(page).not.toContain("from 'recharts'")
    expect(page.match(/import\('@\/components\/lending\/risk\/charts'\)/g)).toHaveLength(5)
    expect(charts).toContain("from 'recharts'")
  })

  it('keeps the shared stage vocabulary independent from the chart engine', () => {
    expect(page).toContain("from '@/components/lending/risk/palette'")
    expect(charts).toContain("from './palette'")
    expect(palette).not.toContain('recharts')
    expect(palette).toContain("STAGE_3: '#ef4444'")
  })

  it('reserves each chart footprint while its shared chunk loads', () => {
    expect(page).toContain('height: 240')
    expect(page).toContain('height: 340')
    expect(page).toContain('height: 280')
    expect(page.match(/height: 220/g)).toHaveLength(2)
  })
})
