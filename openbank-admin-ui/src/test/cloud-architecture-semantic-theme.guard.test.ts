// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import fs from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const source = fs.readFileSync(path.join(process.cwd(), 'src/app/docs/cloud-architecture/page.tsx'), 'utf8')

describe('cloud architecture semantic theme guard', () => {
  it('uses shared semantics instead of fixed presentation colours', () => {
    expect(source).not.toMatch(/["']#[0-9a-f]{3,8}\b/i)
    expect(source).toContain('var(--success-text)')
    expect(source).toContain('var(--warning-text)')
    expect(source).toContain('var(--danger-text)')
    expect(source).toContain('color-mix(in srgb,')
  })

  it('does not publish account, network, or registry identifiers', () => {
    expect(source).not.toContain('265175468565')
    expect(source).not.toContain('10.80.0.0/16')
    expect(source).not.toContain('.dkr.ecr.eu-north-1.amazonaws.com')
  })
})
