// SPDX-License-Identifier: Apache-2.0
// ADR-0370 U7 — the skew check must flag a mock that relies on a field only main serves.

import { mkdirSync, mkdtempSync, writeFileSync } from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { describe, expect, it } from 'vitest'
import { normalize } from '../../e2e/helpers/openapi-contract'
import { skew } from '../../scripts/contract-version-skew'

const spec = (extra: string) => `
openapi: 3.1.0
info: { title: t, version: 1.0.0 }
paths:
  /api/v1/loans/{id}:
    get:
      responses:
        '200':
          content:
            application/json:
              schema:
                type: object
                required: [id]
                properties:
                  id: { type: string }
${extra}
`

function repo(extra: string): string {
  const root = mkdtempSync(path.join(os.tmpdir(), 'ob-skew-'))
  const dir = path.join(root, 'openbank-loan-service/src/main/resources')
  mkdirSync(dir, { recursive: true })
  writeFileSync(path.join(dir, 'openapi.yaml'), spec(extra))
  return root
}

const MAIN = repo('                  riskGrade: { type: string }')
const RELEASE = repo('')
const mock = (body: unknown) => ({ method: 'GET', url: 'http://h/api/svc/loan-service/api/v1/loans/l1', status: 200, body })

describe('contract version skew', () => {
  it('flags a mocked field that exists on main but not in the released spec', () => {
    const found = skew([mock({ id: 'l1', riskGrade: 'B' })], MAIN, RELEASE)
    expect([...found.keys()]).toEqual(['GET loan-service /api/v1/loans/{id}|200|schema'])
    expect([...found.values()][0]).toMatch(/additional properties/)
  })

  it('is quiet when the mock uses only fields both versions serve', () => {
    expect(skew([mock({ id: 'l1' })], MAIN, RELEASE).size).toBe(0)
  })

  it('does not report what main itself rejects — that is the U1 guard', () => {
    expect(skew([mock({ id: 'l1', bogus: 1 })], MAIN, RELEASE).size).toBe(0)
  })

  it('skips a service with no staged release spec', () => {
    const empty = mkdtempSync(path.join(os.tmpdir(), 'ob-skew-none-'))
    expect(skew([mock({ id: 'l1', riskGrade: 'B' })], MAIN, empty).size).toBe(0)
  })
})

describe('normalize strictObjects', () => {
  it('closes a plain object schema and leaves composed ones open', () => {
    const out = normalize({
      type: 'object', properties: { a: { type: 'string' } },
      allOf: [{ properties: { b: {} } }],
    }, { strictObjects: true }) as Record<string, unknown>
    expect(out.additionalProperties).toBeUndefined()
    expect((out.allOf as Record<string, unknown>[])[0]!.additionalProperties).toBeUndefined()
    const plain = normalize({ type: 'object', properties: { a: {} } }, { strictObjects: true }) as Record<string, unknown>
    expect(plain.additionalProperties).toBe(false)
    const open = normalize({ type: 'object', properties: { a: {} }, additionalProperties: true }, { strictObjects: true }) as Record<string, unknown>
    expect(open.additionalProperties).toBe(true)
    expect((normalize({ properties: { a: {} } }) as Record<string, unknown>).additionalProperties).toBeUndefined()
  })
})
