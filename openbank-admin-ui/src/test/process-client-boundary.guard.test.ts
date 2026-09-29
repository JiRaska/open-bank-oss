// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const processView = readFileSync(path.resolve(__dirname, '../components/docs/ProcessView.tsx'), 'utf8')
const score = readFileSync(path.resolve(__dirname, '../lib/docs/process/score.ts'), 'utf8')

describe('process documentation client boundary', () => {
  it('keeps the Zod manifest validator on the server side of the interactive view', () => {
    expect(processView).toContain(
      "import type { Process, Status, TechNode } from '@/lib/docs/process/schema'",
    )
    expect(processView).not.toMatch(/import\s*{(?!\s*type)[^}]*}\s*from '@\/lib\/docs\/process\/schema'/)
    expect(processView).toContain(
      "import { overallScore } from '@/lib/docs/process/score'",
    )
    expect(score).toContain("import type { Control } from './schema'")
  })
})
