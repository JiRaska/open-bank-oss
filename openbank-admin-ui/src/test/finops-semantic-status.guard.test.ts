// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import fs from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

describe('FinOps semantic status presentation', () => {
  const source = fs.readFileSync(path.join(process.cwd(), 'src/app/finops/page.tsx'), 'utf8')

  it('renders support tiers through the shared semantic status badge', () => {
    expect(source).toContain("import { PageHeader } from '@/components/ui/PageHeader'")
    expect(source).toContain("import { StatusBadge, type Tone } from '@/components/ui'")
    expect(source).toContain('return <StatusBadge status={tier} label={c.label} tone={c.tone} />')
  })

  it('uses semantic tokens for runway and resource-efficiency text', () => {
    const helperSource = source.slice(source.indexOf('function RunwayBar'), source.indexOf('function DailySpendTrend'))
    expect(helperSource).toContain("'var(--success-text)'")
    expect(helperSource).toContain("'var(--warning-text)'")
    expect(helperSource).toContain("'var(--danger-text)'")
    expect(helperSource).not.toMatch(/#[0-9a-fA-F]{6}\\b/)
  })
})
