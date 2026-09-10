// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import fs from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const source = fs.readFileSync(path.join(process.cwd(), 'src/components/testing/LazyTestAgentPanel.tsx'), 'utf8')
const page = fs.readFileSync(path.join(process.cwd(), 'src/app/system/tests/page.tsx'), 'utf8')

describe('lazy Test Intelligence agent panel', () => {
  it('splits the advisory panel and waits for a viewport boundary before mounting it', () => {
    expect(source).toContain("dynamic(")
    expect(source).toContain("import('./TestAgentPanel')")
    expect(source).toContain("new IntersectionObserver")
    expect(source).toContain("rootMargin: '600px 0px'")
    expect(source).toContain('nearViewport\n        ? <TestAgentPanel />')
  })

  it('keeps the evidence page on the deferred seam', () => {
    expect(page).toContain("import { LazyTestAgentPanel } from '@/components/testing/LazyTestAgentPanel'")
    expect(page).toContain('{report && <LazyTestAgentPanel />}')
    expect(page).not.toContain("from '@/components/testing/TestAgentPanel'")
  })
})
