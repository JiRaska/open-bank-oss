import { describe, expect, it } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

const root = path.resolve(process.cwd(), 'src')
const read = (file: string) => fs.readFileSync(path.join(root, file), 'utf8')

describe('interactive architecture maps keyboard contract', () => {
  it('keeps SVG and custom map nodes reachable and activatable', () => {
    const sources = [
      read('app/docs/cluster/page.tsx'),
      read('app/docs/service-map/page.tsx'),
      read('app/infrastructure/topology/page.tsx'),
    ]
    for (const source of sources) {
      expect(source).toContain('role="button"')
      expect(source).toContain('tabIndex={0}')
      expect(source).toContain("e.key === 'Enter'")
      expect(source).toContain("e.key === ' '")
    }
    expect(sources[0]).toContain('aria-label={l.label}')
    expect(sources[0]).toContain('role="group" aria-label={lang')
    expect(sources[0]).not.toContain('role="img" aria-label={lang')
    expect(sources[1]).toContain('aria-label={label}')
    expect(sources[2]).toContain('aria-label={n.label}')
  })
})
