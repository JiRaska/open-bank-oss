import { describe, expect, it } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

const read = (file: string) => fs.readFileSync(path.resolve(process.cwd(), file), 'utf8')

describe('operator surface decorative icon contract', () => {
  it('hides sized icons that accompany visible operational labels', () => {
    for (const file of [
      'src/app/notifications/page.tsx',
      'src/app/lending/compliance-packs/page.tsx',
    ]) {
      const source = read(file)
      expect(source).not.toMatch(/<[A-Z][A-Za-z0-9]*(?=[^>]*\bsize=\{)(?![^>]*\baria-hidden\b)[^>]*\/>/)
    }
  })
})
