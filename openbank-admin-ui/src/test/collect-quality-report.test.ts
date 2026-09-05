// SPDX-License-Identifier: Apache-2.0
// Proves the #7544 classification: a Pact Broker verdict left 'pending' carries WHY it is
// pending (query-error / no-provider-main-version / pending-verification), distinct from a
// real 'passed' or 'failed' verdict — never flattened to one unexplained "unavailable", and
// never leaking the broker response body or credentials into the classification text.
import { afterEach, describe, expect, it, vi } from 'vitest'
import { enrichWithVerification, fetchPairVerification } from '../../scripts/collect-quality-report.mjs'

const jsonResponse = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

afterEach(() => vi.unstubAllGlobals())

describe('collect-quality-report contract classification (#7544)', () => {
  it('classifies a broker query error (e.g. HTTP 400) as query-error, without echoing the body', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse(400, { secret: 'do-not-leak' })))
    const v = await fetchPairVerification('http://broker.example', null, 'openbank-admin-ui', 'sha-consumer', 'openbank-case-coordinator-agent')
    expect(v).toMatchObject({ status: 'pending', reasonCode: 'query-error', detail: expect.stringMatching(/HTTP 400/) })
    expect(v.detail).not.toMatch(/do-not-leak/)
  })

  it('classifies an empty matrix with no published provider main version as no-provider-main-version', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      if (url.includes('/matrix')) return jsonResponse(200, { matrix: [] })
      if (url.includes('/branches/main/latest-version')) return jsonResponse(404, { error: 'not found' })
      throw new Error(`unexpected url ${url}`)
    }))
    const v = await fetchPairVerification('http://broker.example', null, 'openbank-alpha-service', 'sha-consumer', 'openbank-document-service')
    expect(v).toMatchObject({
      status: 'pending', reasonCode: 'no-provider-main-version',
      detail: expect.stringMatching(/no published main-branch version/),
    })
  })

  it('classifies an empty matrix with a published provider main version as pending-verification', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      if (url.includes('/matrix')) return jsonResponse(200, { matrix: [] })
      if (url.includes('/branches/main/latest-version')) return jsonResponse(200, { name: '1.0.0' })
      throw new Error(`unexpected url ${url}`)
    }))
    const v = await fetchPairVerification('http://broker.example', null, 'openbank-alpha-service', 'sha-consumer', 'openbank-incentive-service')
    expect(v).toMatchObject({
      status: 'pending', reasonCode: 'pending-verification',
      detail: expect.stringMatching(/no verification result/),
    })
  })

  it('resolves a real passed/failed verdict with no reasonCode — a broker query error cannot override an authoritative result', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse(200, {
      matrix: [{ verificationResult: { success: true, verifiedAt: '2026-08-28T00:00:00Z' } }],
    })))
    const passed = await fetchPairVerification('http://broker.example', null, 'openbank-alpha-service', 'sha-consumer', 'openbank-real-provider')
    expect(passed).toEqual({ status: 'passed', verifiedAt: '2026-08-28T00:00:00Z', providerVersion: null })
    expect(passed).not.toHaveProperty('reasonCode')

    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse(200, {
      matrix: [{ verificationResult: { success: false, verifiedAt: '2026-08-28T00:00:00Z' } }],
    })))
    const failed = await fetchPairVerification('http://broker.example', null, 'openbank-alpha-service', 'sha-consumer', 'openbank-real-provider')
    expect(failed).toEqual({ status: 'failed', verifiedAt: '2026-08-28T00:00:00Z', providerVersion: null })
    expect(failed).not.toHaveProperty('reasonCode')
  })

  it('pins the provider selector to main so a newer feature pass cannot mask a main failure', async () => {
    let selector: [string, string][] = []
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      const parsed = new URL(url)
      selector = [...parsed.searchParams.entries()]
      const selectsMain = parsed.searchParams.getAll('q[][branch]').at(-1) === 'main'
      return jsonResponse(200, {
        matrix: selectsMain
          ? [{
              providerVersion: { number: 'main-red' },
              verificationResult: { success: false, verifiedAt: '2026-08-31T00:00:00Z' },
            }]
          : [{
              providerVersion: { number: 'feature-green' },
              verificationResult: { success: true, verifiedAt: '2026-09-01T00:00:00Z' },
            }],
      })
    }))

    const v = await fetchPairVerification(
      'http://broker.example',
      null,
      'openbank-alpha-service',
      'sha-consumer',
      'openbank-real-provider',
    )

    expect(v).toEqual({ status: 'failed', verifiedAt: '2026-08-31T00:00:00Z', providerVersion: 'main-red' })
    expect(selector).toEqual([
      ['q[][pacticipant]', 'openbank-alpha-service'],
      ['q[][version]', 'sha-consumer'],
      ['q[][pacticipant]', 'openbank-real-provider'],
      ['q[][branch]', 'main'],
      ['q[][latest]', 'true'],
      ['latestby', 'cvpv'],
    ])
  })

  it('classifies a network/timeout exception as query-error, and never turns it into a pass', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new DOMException('The operation was aborted', 'TimeoutError') }))
    const contracts = [{ consumer: 'openbank-alpha-service', provider: 'openbank-real-provider', pactFile: 'x.json', consumerVersion: 'sha-consumer', status: 'pending', verifiedAt: null, interactions: [] }]
    const originalEnv = process.env.PACT_BROKER_URL
    process.env.PACT_BROKER_URL = 'http://broker.example'
    try {
      await enrichWithVerification(contracts)
    } finally {
      process.env.PACT_BROKER_URL = originalEnv
    }
    expect(contracts[0]).toMatchObject({ status: 'pending', reasonCode: 'query-error' })
    expect(contracts[0].status).not.toBe('passed')
  })
})
