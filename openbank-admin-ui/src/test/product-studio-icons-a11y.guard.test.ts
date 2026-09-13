import { describe, expect, it } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

const page = fs.readFileSync(path.resolve(process.cwd(), 'src/app/product-studio/page.tsx'), 'utf8')

describe('Product Studio decorative icon contract', () => {
  it('hides icons that accompany visible labels from assistive technology', () => {
    const iconNames = [
      'Bot', 'Boxes', 'CheckCircle2', 'CircleAlert', 'Eye', 'FileJson', 'Link2',
      'ListChecks', 'LockKeyhole', 'Plus', 'RefreshCw', 'Send', 'ShieldCheck', 'Sparkles', 'X',
    ]
    for (const name of iconNames) {
      expect(page).not.toMatch(new RegExp(`<${name}\\b(?:(?!aria-hidden).)*?/>`))
    }
  })
})
