// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import fs from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const source = fs.readFileSync(path.join(process.cwd(), 'src/app/docs/qrlesspay-readiness/page.tsx'), 'utf8')

describe('QR-less readiness semantic theme guard', () => {
  it('uses shared verdict semantics instead of fixed presentation colours', () => {
    expect(source).not.toMatch(/["']#[0-9a-f]{3,8}\b/i)
    expect(source).not.toMatch(/rgba?\(\s*\d/i)
    expect(source).toContain('var(--success-text)')
    expect(source).toContain('var(--warning-text)')
    expect(source).toContain('var(--danger-text)')
    expect(source).toContain('var(--border-strong)')
  })
})
