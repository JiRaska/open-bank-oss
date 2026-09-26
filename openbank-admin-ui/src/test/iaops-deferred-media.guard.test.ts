// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const source = readFileSync(path.resolve(__dirname, '../app/iaops/page.tsx'), 'utf8')

describe('IAOps post-data media boundary', () => {
  it('keeps educational and findings panels out of the initial auth/loading bundle', () => {
    expect(source).toContain("import('@/components/agent/AgentMeshExplainer')")
    expect(source).toContain("import('@/components/agent/AgentInsightsPanel')")
    expect(source).not.toContain("import { AgentMeshExplainer }")
    expect(source).not.toContain("import { AgentInsightsPanel }")
    expect(source).toContain('minHeight: 420')
    expect(source).toContain('minHeight: 160')
  })

  it('does not preload the data-gated 207 kB hero asset', () => {
    expect(source).not.toContain("from 'next/image'")
    expect(source).toContain('src="/aiops-agent-crew.webp"')
    expect(source).toContain('width={1200} height={800} loading="lazy" decoding="async"')
    expect(source).not.toContain('priority unoptimized')
  })
})
