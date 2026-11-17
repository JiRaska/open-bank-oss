// SPDX-License-Identifier: Apache-2.0
// ADR-0370 U1 — the mock-contract validator must FAIL on a wrong mock, not only pass a right one.

import { mkdirSync, mkdtempSync, readdirSync, existsSync, writeFileSync } from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { describe, expect, it } from 'vitest'
import { OpenApiContract, normalize, parseProxyUrl } from '../../e2e/helpers/openapi-contract'

const SPEC_30 = `
openapi: 3.0.3
info: { title: t, version: 1.0.0 }
paths:
  /api/v1/cards/summary:
    get:
      responses:
        '200':
          content:
            application/json:
              schema: { $ref: '#/components/schemas/Summary' }
  /api/v1/cards/{id}:
    get:
      responses:
        '200': { $ref: '#/components/responses/Card' }
        '404': { description: missing }
components:
  responses:
    Card:
      description: ok
      content:
        application/json:
          schema: { $ref: '#/components/schemas/Card' }
  schemas:
    Summary:
      type: object
      required: [total]
      properties: { total: { type: integer } }
    Card:
      type: object
      required: [id, status]
      additionalProperties: false
      properties:
        id: { type: string }
        status: { type: string, enum: [ACTIVE, BLOCKED] }
        label: { type: string, nullable: true }
`

function fixtureRepo(): string {
  const root = mkdtempSync(path.join(os.tmpdir(), 'ob-contract-'))
  const dir = path.join(root, 'openbank-card-service/src/main/resources')
  mkdirSync(dir, { recursive: true })
  writeFileSync(path.join(dir, 'openapi.yaml'), SPEC_30)
  return root
}

const url = (p: string) => `http://localhost:3001/api/svc/card-service${p}`

describe('OpenApiContract', () => {
  const contract = new OpenApiContract(fixtureRepo())

  it('accepts a mock that matches the declared schema, including a 3.0 nullable', () => {
    expect(contract.check({ method: 'GET', url: url('/api/v1/cards/c1'), status: 200, body: { id: 'c1', status: 'ACTIVE', label: null } })).toEqual([])
    expect(contract.check({ method: 'GET', url: url('/api/v1/cards/summary?x=1'), status: 200, body: '{"total":3}' })).toEqual([])
  })

  it('prefers the literal template over a path parameter', () => {
    // `/summary` matched as `{id}` would demand id+status and report a schema violation.
    expect(contract.check({ method: 'GET', url: url('/api/v1/cards/summary'), status: 200, body: { total: 1 } })).toEqual([])
  })

  it.each([
    ['a dropped required field', { id: 'c1' }],
    ['an undeclared field', { id: 'c1', status: 'ACTIVE', extra: true }],
    ['a value outside the enum', { id: 'c1', status: 'SETTLED' }],
  ])('rejects %s', (_label, body) => {
    const [v] = contract.check({ method: 'GET', url: url('/api/v1/cards/c1'), status: 200, body }) ?? []
    expect(v?.kind).toBe('schema')
    expect(v?.operation).toBe('/api/v1/cards/{id}')
  })

  it('rejects an operation, method or status the spec does not declare', () => {
    expect(contract.check({ method: 'GET', url: url('/api/v1/nope'), status: 200, body: {} })?.[0]?.kind).toBe('unknown-operation')
    expect(contract.check({ method: 'POST', url: url('/api/v1/cards/c1'), status: 200, body: {} })?.[0]?.kind).toBe('unknown-operation')
    expect(contract.check({ method: 'GET', url: url('/api/v1/cards/c1'), status: 500, body: {} })?.[0]?.kind).toBe('undeclared-status')
  })

  it('accepts a declared status with no body schema', () => {
    expect(contract.check({ method: 'GET', url: url('/api/v1/cards/c1'), status: 404, body: { error: 'x' } })).toEqual([])
  })

  it('reports an unknown service and a non-JSON body', () => {
    expect(contract.check({ method: 'GET', url: 'http://h/api/svc/ghost/api/v1/x', status: 200, body: {} })?.[0]?.kind).toBe('unmapped-service')
    expect(contract.check({ method: 'GET', url: url('/api/v1/cards/c1'), status: 200, body: 'not json' })?.[0]?.kind).toBe('non-json-body')
  })

  it('ignores BFF-owned routes', () => {
    expect(contract.check({ method: 'GET', url: 'http://h/api/sanctions/lists', status: 200, body: {} })).toBeNull()
    expect(parseProxyUrl('http://h/api/svc/lending-service/api/v1/x?y=1')).toEqual({ service: 'lending-service', path: '/api/v1/x' })
  })

  it('normalizes 3.0 nullable enums and refs', () => {
    expect(normalize({ type: 'string', enum: ['A'], nullable: true })).toEqual({ type: ['string', 'null'], enum: ['A', null] })
    expect(normalize({ $ref: '#/components/schemas/X' })).toEqual({ $ref: 'spec#/components/schemas/X' })
  })
})

describe('every fleet openapi.yaml loads into the validator', () => {
  const repoRoot = path.resolve(__dirname, '../../..')
  const services = readdirSync(repoRoot)
    .filter(d => d.startsWith('openbank-') && existsSync(path.join(repoRoot, d, 'src/main/resources/openapi.yaml')))
    .map(d => d.slice('openbank-'.length))

  it('finds the fleet', () => {
    expect(services.length).toBeGreaterThan(40)
  })

  it.each(services)('%s compiles', service => {
    const contract = new OpenApiContract(repoRoot)
    // A probe path no spec declares: forces the spec to load and its templates to compile.
    const result = contract.check({ method: 'GET', url: `http://h/api/svc/${service}/__probe__`, status: 200, body: {} })
    expect(result?.[0]?.kind).toBe('unknown-operation')
  })
})
