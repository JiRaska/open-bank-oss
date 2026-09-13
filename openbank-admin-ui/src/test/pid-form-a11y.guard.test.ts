import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

describe('PID quick-create form accessibility', () => {
  it('associates every operator-facing field label with its control', () => {
    const page = readFileSync(resolve(__dirname, '../app/pid/page.tsx'), 'utf8')
    const fields = [
      'given-name', 'family-name', 'birthdate', 'gender', 'birthplace', 'nationalities',
      'bankid-sub', 'email', 'phone', 'document-type', 'document-number',
      'document-country', 'document-issued-at', 'document-expires-at',
    ]
    for (const field of fields) {
      expect(page).toContain(`htmlFor="pid-${field}"`)
      expect(page).toContain(`id="pid-${field}"`)
    }
  })
})
