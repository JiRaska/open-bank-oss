import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

describe('segment draft form accessibility', () => {
  it('associates all rule controls with their labels', () => {
    const page = readFileSync(resolve(__dirname, '../app/segments/new/page.tsx'), 'utf8')
    for (const field of ['name', 'status', 'min-days']) {
      expect(page).toContain(`htmlFor="segment-${field}"`)
      expect(page).toContain(`id="segment-${field}"`)
    }
  })
})
