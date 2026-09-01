import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const source = readFileSync(path.resolve(__dirname, '../app/segments/new/page.tsx'), 'utf8')

describe('audience draft creation', () => {
  it('synchronously rejects a second submit while the first create request is pending', () => {
    expect(source).toContain("import { useRef, useState } from 'react'")
    expect(source).toContain('const createInFlight = useRef(false)')
    expect(source).toContain('if (createInFlight.current) return')
    expect(source).toContain('createInFlight.current = true')
    expect(source).toContain("fetch('/api/audiences', { method: 'POST'")
    expect(source).toContain('createInFlight.current = false')
  })
})
