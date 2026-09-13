import { readFileSync } from 'node:fs'
import { globSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

/**
 * `Ids.newId()` in openbank-libs-domain returns a **UUIDv7** and is documented as "the default for
 * durable, indexed identifiers" (ADR-0106 also prefers Postgres 18's `uuidv7()` server-side). A
 * parser whose version class stops at 5 therefore rejects the fleet's own primary keys.
 *
 * That failure is invisible in the worst way: `useServiceResource` catches a parser throw in the
 * same `catch` as a network error, retries, and renders "the service is not responding". An
 * operator opens an incident against a healthy service (#9738).
 *
 * This guard reads the SOURCE rather than calling each parser, on purpose — the point is to catch
 * the SEVENTH copy of the regex someone adds next, which no per-parser test would cover.
 */
const SOURCES = globSync('src/lib/**/*.ts', { cwd: process.cwd() })

describe('UUID version class across evidence parsers', () => {
  it('finds the parsers it is meant to guard (a zero-file glob would pass vacuously)', () => {
    expect(SOURCES.length).toBeGreaterThan(20)
  })

  it('no parser restricts the UUID version class to 1-5 — that rejects UUIDv7', () => {
    const offenders = SOURCES.filter(f => /\[1-5\]\[0-9a-f\]\{3\}-\[89ab\]/.test(readFileSync(f, 'utf8')))
    expect(offenders).toEqual([])
  })

  it('the version-class regex that IS used accepts v1, v4, v7 and v8 and still rejects placeholders', () => {
    const re = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
    expect(re.test('3f2504e0-4f89-11d3-9a0c-0305e82c3301')).toBe(true)   // v1
    expect(re.test('24977cca-20b2-4877-80d1-403b40181a89')).toBe(true)   // v4
    expect(re.test('019fb944-6c3a-7bd2-814d-7c946371f1ae')).toBe(true)   // v7 — Ids.newId()
    expect(re.test('019fb944-6c3a-8bd2-814d-7c946371f1ae')).toBe(true)   // v8 — RFC 9562
    expect(re.test('11111111-1111-1111-1111-111111111111')).toBe(false)  // placeholder: bad variant
    expect(re.test('00000000-0000-0000-0000-000000000000')).toBe(false)  // nil
    expect(re.test('019fb944-6c3a-7bd2-014d-7c946371f1ae')).toBe(false)  // variant nibble 0
  })
})
