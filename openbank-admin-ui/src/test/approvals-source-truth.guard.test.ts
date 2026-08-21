// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import fs from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const routeSource = fs.readFileSync(path.join(process.cwd(), 'src/app/api/approvals/pending/route.ts'), 'utf8')
const pageSource = fs.readFileSync(path.join(process.cwd(), 'src/app/approvals/page.tsx'), 'utf8')

describe('approval inbox source truthfulness', () => {
  it('exposes known-but-unwired queues instead of treating them as empty', () => {
    expect(routeSource).toContain("'not-configured'")
    expect(routeSource).toContain("balance: 'not-configured'")
    expect(routeSource).toContain('...NOT_CONFIGURED_SOURCES')
  })

  it('does not render an empty-state claim while a source is not configured', () => {
    expect(pageSource).toContain('const notConfiguredSources = useMemo(')
    expect(pageSource).toContain('notConfiguredSources.length === 0 && domainItems.filter')
    expect(pageSource).toContain('Decisions from these domains will not appear here until their read endpoint is available.')
  })

  it('renders the proposer identity supplied by the BFF rather than assuming every proposer is a bot', () => {
    expect(pageSource).toContain("const aiGenerated = p.agent ? p.agent.icon === 'bot'")
    expect(pageSource).toContain('const ProposerIcon = aiGenerated ? Bot : UserRound')
  })
})
