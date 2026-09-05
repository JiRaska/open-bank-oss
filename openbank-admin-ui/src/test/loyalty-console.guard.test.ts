// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// The Lípa console teaches the programme, so its claims are part of the product and can go wrong
// the way any other output can. These are the claims that must not drift.
//
// The one that matters most is the granted-versus-delivered distinction. `BenefitGrantStatus.GRANTED`
// means the benefit is owed and published for its delivering engine, and this platform has already
// shipped the other arrangement once: a push adapter counted an APNs 200 as a delivery, so every
// undelivered notification read as delivered until a customer reported it. A console that renders
// "granted" as "delivered" would reintroduce exactly that, in the place an operator goes to check.

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { beforeAll, describe, expect, it } from 'vitest'
import { AI_RED_LINES, AI_ROLES, CONNECTIONS, LEGAL, LIFECYCLE_DIAGRAM, PRINCIPLES } from '@/lib/loyalty/lipaContent'
import { PERMISSIONS, hasPermission } from '@/lib/auth/roles'

const ROOT = path.join(__dirname, '..')
const read = (rel: string) => readFileSync(path.join(ROOT, rel), 'utf8')

describe('the Lípa console does not claim a granted benefit was delivered', () => {
  const content = read('lib/loyalty/lipaContent.ts')
  const page = read('app/loyalty/page.tsx')

  it('never describes a benefit as delivered, applied or received by the customer', () => {
    // Deliberately checked against the copy, in both languages, rather than against a type: the
    // defect this guards is a sentence, not a value.
    const forbidden = [/\bdelivered to the customer\b/i, /\bhas been applied\b/i, /\bdoručen(o|a|ý) klientovi\b/i]
    for (const source of [content, page]) {
      for (const pattern of forbidden) {
        expect(source).not.toMatch(pattern)
      }
    }
  })

  it('says explicitly that Lípa delivers nothing itself', () => {
    const catalogConnection = CONNECTIONS.find(c => c.id === 'catalog')
    expect(catalogConnection?.limit.en).toMatch(/calls none of those engines/i)
    expect(catalogConnection?.limit.cs).toMatch(/nevolá/i)
  })
})

describe('the four closed-loop principles are all present and each says what would break it', () => {
  const REQUIRED = ['no-cash-out', 'no-transfer', 'no-fiat-price', 'no-credit-reward']

  it.each(REQUIRED)('%s is documented', id => {
    expect(PRINCIPLES.map(p => p.id)).toContain(id)
  })

  // A principle without its falsifier is decoration: an operator cannot recognise the change that
  // costs it, which is the only thing the section is for.
  it.each(PRINCIPLES)('$id states both the reason and the thing that would break it', principle => {
    expect(principle.why.cs.length).toBeGreaterThan(20)
    expect(principle.why.en.length).toBeGreaterThan(20)
    expect(principle.breaks.cs.length).toBeGreaterThan(20)
    expect(principle.breaks.en.length).toBeGreaterThan(20)
  })
})

describe('the AI section keeps its red lines', () => {
  it('states that AI proposes and never decides', () => {
    expect(AI_RED_LINES.some(l => /never decides/i.test(l.en))).toBe(true)
    expect(AI_RED_LINES.some(l => /nikdy nerozhoduje/i.test(l.cs))).toBe(true)
  })

  it('states that no model sets a price, a term or credit eligibility', () => {
    expect(AI_RED_LINES.some(l => /price.*term.*credit eligibility/i.test(l.en))).toBe(true)
  })

  // Every role names a guardrail and an authority. A role that only says what it does reads as a
  // capability the bank already delegated, which is the opposite of what this page is claiming.
  it.each(AI_ROLES)('$id names what it cannot do and who decides instead', role => {
    expect(role.cannot.cs.length).toBeGreaterThan(20)
    expect(role.cannot.en.length).toBeGreaterThan(20)
    expect(role.decides.cs.length).toBeGreaterThan(10)
    expect(role.decides.en.length).toBeGreaterThan(10)
  })

  it('marks every role honestly, and none of them as wired while none is', () => {
    expect(AI_ROLES.every(r => r.status === 'proposed')).toBe(true)
  })
})

describe('the legal section stays honest about what is not settled', () => {
  it('carries the regimes the programme actually turns on', () => {
    expect(LEGAL.map(l => l.id)).toEqual(
      expect.arrayContaining(['emd2', 'mica', 'ifrs15', 'gdpr', 'ai-act', 'consumer-credit']),
    )
  })

  it('does not present the EMD2 position as legally reviewed, because it is not', () => {
    const emd2 = LEGAL.find(l => l.id === 'emd2')
    expect(emd2?.open).not.toBeNull()
    expect(emd2?.open?.en).toMatch(/has not happened/i)
  })

  it('records that erasure is still unwired rather than implying it works', () => {
    const gdpr = LEGAL.find(l => l.id === 'gdpr')
    expect(gdpr?.open?.en).toMatch(/not wired yet/i)
  })
})

describe('the lifecycle diagram parses as Mermaid and avoids the two label traps', () => {
  // Parsed with the same mermaid the console renders with, so this is not a convention check
  // dressed up as a verification. The probe is held to a known-positive first: the three forms
  // below are the ones this repository has actually been broken by, and mermaid must reject all
  // three or the parse assertion under them proves nothing. An earlier version of this probe used
  // `A[Outbox @Scheduled every 5s]`, which mermaid ACCEPTS — so it would have reported a clean
  // parse while being unable to detect the defect it was written for.
  const TRAPS = [
    'graph TD\n  outbox[Outbox<br/>Dispatcher<br/>@Scheduled every 5s] --> db[(DB)]\n',
    'sequenceDiagram\n  A->>B: do it; then stop\n',
    'graph LR\n  A[@Scheduled] --> B[x]\n',
  ]

  // Loading mermaid is slow enough to exceed the default 5s timeout when the whole suite runs
  // concurrently, so it is imported once and the two cases get their own budget. A timeout here
  // would read as a broken diagram, which is the wrong alarm.
  let mermaid: typeof import('mermaid').default
  beforeAll(async () => {
    mermaid = (await import('mermaid')).default
    mermaid.initialize({ startOnLoad: false })
  }, 30_000)

  it.each(TRAPS)('the probe rejects a known-broken diagram (case %#)', async chart => {
    await expect(mermaid.parse(chart)).rejects.toBeTruthy()
  }, 30_000)

  it('parses in the renderer the console actually uses', async () => {
    await expect(mermaid.parse(LIFECYCLE_DIAGRAM)).resolves.toBeTruthy()
  }, 30_000)

  it('quotes every label, so a bare @ or ; cannot break the parse', () => {
    // A bare `@` is Mermaid 11's node-metadata shorthand and a literal `;` is a statement
    // separator. Both render as a red "Mermaid render failed" box that nothing in CI loads —
    // 40 of 248 blocks across the fleet were broken that way, found only when someone opened one.
    const labels = [...LIFECYCLE_DIAGRAM.matchAll(/\[([^\]]*)\]/g)].map(m => m[1])
    expect(labels.length).toBeGreaterThan(0)
    for (const label of labels) {
      expect(label.startsWith('"') && label.endsWith('"')).toBe(true)
    }
  })

  it('shows the two endings that are not errors', () => {
    expect(LIFECYCLE_DIAGRAM).toMatch(/CAPPED/)
    expect(LIFECYCLE_DIAGRAM).toMatch(/EXPIRE/)
  })
})

describe('the console route and its permission agree', () => {
  it('loyalty:view exists and an auditor holds it', () => {
    expect(Object.keys(PERMISSIONS)).toContain('loyalty:view')
    expect(hasPermission(['ROLE_AUDITOR'], 'loyalty:view')).toBe(true)
    expect(hasPermission(['ROLE_ADMIN'], 'loyalty:view')).toBe(true)
  })

  it('a role with no bearing on the programme does not hold it', () => {
    expect(hasPermission(['ROLE_KYC'], 'loyalty:view')).toBe(false)
  })

  it('the sidebar entry and the route table name the same path and permission', () => {
    const sidebar = read('components/layout/Sidebar.tsx')
    expect(sidebar).toMatch(/href: '\/loyalty',\s+icon: Leaf,\s+permission: 'loyalty:view'/)
    expect(read('lib/auth/roles.ts')).toMatch(/\['loyalty:view', \['\/loyalty'\]\]/)
  })
})

describe('the BFF keeps a missing service distinct from an empty one', () => {
  const route = read('app/api/loyalty/route.ts')

  it('maps 404 and a transport failure to different states', () => {
    expect(route).toMatch(/status === 404\) return 'not_deployed'/)
    expect(route).toMatch(/return \{ state: 'unreachable', body: null \}/)
  })

  it('lets the worst upstream state win rather than rendering a partial answer as whole', () => {
    expect(route).toMatch(/\.find\(s => s !== 'ok'\) \?\? 'ok'/)
  })

  it('enforces the permission at the route, not only in the page', () => {
    expect(route).toMatch(/requireApiPermission\('loyalty:view'\)/)
  })
})
