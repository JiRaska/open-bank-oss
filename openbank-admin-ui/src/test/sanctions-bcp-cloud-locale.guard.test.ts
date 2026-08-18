import { describe, expect, it } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

const root = path.resolve(process.cwd(), 'src')
const read = (file: string) => fs.readFileSync(path.join(root, file), 'utf8')

describe('operator locale consistency for sanctions and continuity docs', () => {
  it('uses the active locale for all visible date/time and count formatting', () => {
    const sanctions = read('app/sanctions/page.tsx')
    const bcp = read('app/docs/bcp/page.tsx')
    const cloud = read('app/docs/cloud-architecture/page.tsx')

    for (const source of [sanctions, bcp, cloud]) {
      expect(source).not.toMatch(/toLocale(?:String|DateString|TimeString)\(['"](?:cs-CZ|en-US|en-GB)['"]\)/)
      expect(source).not.toMatch(/toLocale(?:String|DateString|TimeString)\(\)/)
    }
    expect(sanctions).toContain('toLocaleString(numberLocale)')
    expect(sanctions).toContain('toLocaleString(dateLocale)')
    expect(bcp).toContain('toLocaleTimeString(dateLocale)')
    expect(cloud).toContain('toLocaleTimeString(dateLocale)')
  })
})
