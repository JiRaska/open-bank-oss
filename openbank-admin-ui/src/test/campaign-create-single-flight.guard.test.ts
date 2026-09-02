import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const source = readFileSync(path.resolve(__dirname, '../app/campaigns/new/page.tsx'), 'utf8')

describe('Campaign Studio draft submission', () => {
  it('synchronously keeps create and revise requests single-flight', () => {
    expect(source).toContain("import { useEffect, useRef, useState } from 'react'")
    expect(source).toContain('const saveInFlight = useRef(false)')
    expect(source).toContain('if (saveInFlight.current) return')
    expect(source).toContain('saveInFlight.current = true')
    expect(source).toContain("draftId ? `/api/campaigns/${encodeURIComponent(draftId)}` : '/api/campaigns'")
    expect(source).toContain('saveInFlight.current = false')
  })
})
