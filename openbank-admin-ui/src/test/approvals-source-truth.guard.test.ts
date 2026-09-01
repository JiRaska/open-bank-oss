// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import fs from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const routeSource = fs.readFileSync(path.join(process.cwd(), 'src/app/api/approvals/pending/route.ts'), 'utf8')
const pageSource = fs.readFileSync(path.join(process.cwd(), 'src/app/approvals/page.tsx'), 'utf8')

describe('approval inbox source truthfulness', () => {
  // Was three `toContain` string assertions naming `balance: 'not-configured'` and the
  // NOT_CONFIGURED_SOURCES spread. Once balance gained its read (#5679) that set is empty and
  // gone, and those assertions would have had to be deleted — leaving nothing behind. A guard
  // that asserts a literal only ever describes the state it was written in; this one asserts the
  // invariant that state existed to protect: every domain the route can EMIT is a domain the
  // route actually READS. A source added to the response without a fetcher — the shape that made
  // an unwired queue render as an empty one — fails here regardless of what it is called.
  it('reads every source it reports, so no queue can render as empty without being read', () => {
    const declaredDomains = [...routeSource.matchAll(/domain: '([\w-]+)' as const/g)].map(m => m[1])
    const fetchers = [...routeSource.matchAll(/^async function (\w+)Pending/gm)].map(m => m[1])
    const reported = [...routeSource.matchAll(/^ {6}'?([\w-]+)'?: (\w+)\.state,$/gm)].map(m => m[2])

    expect(declaredDomains.length).toBeGreaterThan(15)
    // Every reported source is backed by a fetcher of the same name...
    expect([...new Set(reported)].sort()).toEqual([...new Set(fetchers)].sort())
    // ...and every fetcher is awaited in the fan-out rather than declared and forgotten.
    for (const f of fetchers) {
      expect(routeSource).toContain(`${f}Pending(headers).catch(() => unavailable)`)
    }
  })

  it('still distinguishes an unreadable source from an empty one', () => {
    // `stateFor` is the whole reason a 403 does not read as "no approvals pending" — the most
    // dangerous thing an approvals screen can say wrongly.
    expect(routeSource).toContain("return status === 401 || status === 403 ? 'forbidden' : 'unavailable'")
    expect(routeSource).toContain("const unavailable: SourceResult = { items: [], state: 'unavailable' }")
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
