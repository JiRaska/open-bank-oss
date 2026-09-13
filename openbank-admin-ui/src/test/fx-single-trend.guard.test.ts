// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const page = readFileSync(path.resolve(__dirname, '../app/fx/page.tsx'), 'utf8')
const chart = readFileSync(path.resolve(__dirname, '../components/fx/FxTrendChart.tsx'), 'utf8')

describe('FX trend ownership', () => {
  it('renders one shared trend and performs no legacy duplicate history request', () => {
    expect(page.match(/<FxTrendChart\b/g)).toHaveLength(1)
    expect(page).not.toContain('/api/fx/history?')
    expect(page).not.toContain('trendPair')
    expect(chart).toContain('`/api/fx/history/${base}/${quote}`')
  })

  it('keeps the operator action log separate from historical market data', () => {
    expect(page).toContain("t('Historie akcí operátora', 'Operator Action Log')")
    expect(page).toContain('`history` here is now only ever a genuine admin-action')
  })
})
