// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('auth error recovery contract', () => {
  it('keeps retry sign-in explicit, stateful, and callback-scoped', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/auth/error/page.tsx'), 'utf8')
    expect(source).toContain('type="button"')
    expect(source).toContain('onClick={retry}')
    expect(source).toContain('aria-busy={pending}')
    expect(source).toContain('disabled={pending}')
    expect(source).toContain('{pending ? copy.retrying : copy.retry}')
    expect(source).toContain('signIn("keycloak", { callbackUrl })')
  })
})
