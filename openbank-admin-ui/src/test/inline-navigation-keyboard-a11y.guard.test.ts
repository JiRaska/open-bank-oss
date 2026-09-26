import { describe, expect, it } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

const root = path.resolve(process.cwd(), 'src')
const read = (file: string) => fs.readFileSync(path.join(root, file), 'utf8')

describe('inline navigation keyboard contract', () => {
  it('keeps native expandable rows and lineage links keyboard accessible', () => {
    const regulatory = read('app/regulatory/page.tsx')
    const lineage = read('app/docs/lineage/page.tsx')
    expect(regulatory).toContain('<button type="button" aria-expanded={isSelected}')
    expect(regulatory).toContain('aria-controls={`regulatory-report-${report.id}`}')
    expect(regulatory).toContain('id={`regulatory-report-${report.id}`}')
    expect(regulatory).not.toContain('<div role="button" tabIndex={0}')
    expect(lineage).toContain('<button type="button" disabled={!known.has(')
    expect(lineage).toContain('disabled={!known.has(')
    expect(lineage).toContain('is not loaded')
    expect(lineage).toContain('background: \'none\', border: 0')
  })
})
