// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import fs from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const read = (relative: string) => fs.readFileSync(path.join(process.cwd(), relative), 'utf8')

describe('docs process status presentation', () => {
  const processView = read('src/components/docs/ProcessView.tsx')
  const sensorView = read('src/components/docs/SensorFamilyView.tsx')
  const status = read('src/lib/docs/status.tsx')

  it('uses the shared status badge and localized plan-vs-reality labels', () => {
    expect(processView).toContain("import { StatusBadge } from '@/components/ui/StatusBadge'")
    expect(sensorView).toContain("import { StatusBadge } from '@/components/ui/StatusBadge'")
    expect(processView).toContain('STATUS_META[s].label[language]')
    expect(status).toContain("cs: 'Částečně (nasazeno, neúplné)'")
    expect(status).toContain("en: 'Partial (deployed, incomplete)'")
  })

  it('keeps status colours themeable and removes the duplicated raw palette', () => {
    expect(status).toContain("text: 'var(--success-text)'")
    expect(status).toContain("background: 'var(--warning-bg)'")
    expect(status).not.toMatch(/#[0-9a-f]{3,8}/i)
    expect(processView).not.toMatch(/#(?:059669|d97706|94a3b8|ecfdf5|fffbeb|f8fafc|6ee7b7|fcd34d|cbd5e1)/i)
    expect(sensorView).not.toMatch(/#(?:059669|d97706|94a3b8|ecfdf5|fffbeb|f8fafc|6ee7b7|fcd34d|cbd5e1)/i)
  })
})
