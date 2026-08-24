// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('auth error recovery contract', () => {
  it('keeps retry sign-in an explicit accessible button', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/auth/error/page.tsx'), 'utf8')
    expect(source).toContain('<button type="button" aria-label="Retry sign-in / Zkusit přihlášení znovu"')
    expect(source).toContain('signIn("keycloak")')
  })
})
