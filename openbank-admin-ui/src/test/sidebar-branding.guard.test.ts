import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const sidebar = readFileSync(path.resolve(__dirname, '../components/layout/Sidebar.tsx'), 'utf8')

describe('sidebar product branding contract', () => {
  it('does not advertise a stale hard-coded platform version', () => {
    expect(sidebar).not.toContain('OpenBank v2.0')
    expect(sidebar).toContain("OpenBank Admin Portal")
    expect(sidebar).toContain("OpenBank Admin portál")
  })
})
