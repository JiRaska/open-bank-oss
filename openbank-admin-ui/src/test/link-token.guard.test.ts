// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

const read = (path: string) => readFileSync(join(process.cwd(), path), 'utf8')

describe('semantic link colour contract', () => {
  it('keeps entity and lifecycle links independent from the decorative accent', () => {
    for (const file of [
      'src/components/entities/EntityChip.tsx',
      'src/components/infra/LifecycleStrip.tsx',
    ]) {
      expect(read(file)).toContain("color: 'var(--link)'")
      expect(read(file)).not.toContain("color: 'var(--accent)'")
    }
  })
})
