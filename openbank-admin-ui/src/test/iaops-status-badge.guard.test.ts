import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const source = readFileSync(path.resolve(__dirname, '../app/iaops/page.tsx'), 'utf8')

describe('IA Ops status presentation', () => {
  it('uses the shared semantic status badge for localized governance states', () => {
    expect(source).toContain("import { StatusBadge, type Tone } from '@/components/ui'")
    expect(source).toContain("built: { tone: 'success', en: 'Built', cs: 'Hotovo' }")
    expect(source).toContain("partial: { tone: 'warning', en: 'Partial', cs: 'Částečně' }")
    expect(source).toContain("planned: { tone: 'accent', en: 'Planned', cs: 'Plánováno' }")
    expect(source).toContain('return <StatusBadge status={status} tone={c.tone} label={language === \'cs\' ? c.cs : c.en} withDot />')
    expect(source).not.toContain("built:   { color: '#16a34a'")
  })
})
