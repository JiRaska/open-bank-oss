// SPDX-License-Identifier: Apache-2.0
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mkdtempSync, rmSync, writeFileSync } from 'fs'
import { tmpdir } from 'os'
import path from 'path'

vi.mock('@/auth', () => ({ auth: vi.fn() }))
import { auth } from '@/auth'

describe('Test Intelligence agent BFF', () => {
  beforeEach(() => { vi.mocked(auth).mockResolvedValue({ user: { accessToken: 'viewer-token', roles: ['ROLE_ADMIN'] } } as never) })
  afterEach(() => { vi.restoreAllMocks(); delete process.env.OPENBANK_TEST_INTELLIGENCE })

  it('relays the operator token to flaky-test-hunter', async () => {
    const finding = { id: 'f-1', component: 'openbank-ledger-service', title: 'Flaky integration', severity: 'WARNING' }
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify([finding]), { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)
    const { GET } = await import('@/app/api/test-intelligence/agents/route')
    const body = await (await GET()).json()
    expect(body).toEqual({ findings: [{ ...finding, checkType: 'advisory', detectedAt: '', rootCause: null, proposalUrl: null, status: 'open' }], available: true })
    expect(new Headers(fetchMock.mock.calls[0][1].headers).get('Authorization')).toBe('Bearer viewer-token')
  })

  it('never reaches the agent without a session', async () => {
    vi.mocked(auth).mockResolvedValue(null as never)
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    const { GET } = await import('@/app/api/test-intelligence/agents/route')
    expect((await GET()).status).toBe(401)
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('does not let a viewer spend the AI analysis budget', async () => {
    vi.mocked(auth).mockResolvedValue({ user: { accessToken: 'viewer-token', roles: ['ROLE_VIEWER'] } } as never)
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    const { POST } = await import('@/app/api/test-intelligence/agents/route')
    expect((await POST()).status).toBe(403)
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('sends only the bounded evidence projection for operator-triggered AI analysis', async () => {
    const dir = mkdtempSync(path.join(tmpdir(), 'test-intelligence-agent-'))
    const file = path.join(dir, 'report.json')
    writeFileSync(file, JSON.stringify({
      schemaVersion: 1, collectedAt: '2026-08-22T10:00:00Z',
      components: [{ component: 'openbank-ledger-service', moneyPath: true,
        evidence: [{ kind: 'integration', state: 'passed', observedAt: '2020-01-01T00:00:00Z', source: '/private/path' }],
        testInfrastructure: { declared: ['postgres'], observed: [{ lifecycle: 'started' }] } }],
      clientExperiences: [{ id: 'openbank-app', evidence: [{ kind: 'visual', state: 'passed', observedAt: new Date().toISOString(), source: '/private/app/path' }],
        rum: { state: 'passed', detail: '12 sampled spans', sampledSpansLast7d: 12 } }],
      testCases: [
        { component: 'openbank-ledger-service', state: 'flaky', sameCommitTransitions: 2, wastedDurationMs: 1250, name: 'private test name', fingerprint: 'private-fingerprint' },
        { component: 'openbank-ledger-service', state: 'failing', sameCommitTransitions: 0, wastedDurationMs: 500, name: 'another private test', fingerprint: 'private-fingerprint-2' },
      ],
    }))
    process.env.OPENBANK_TEST_INTELLIGENCE = file
    const agentFinding = { id: 'diagnosed-1', component: 'openbank-ledger-service', title: 'Investigate stale integration', severity: 'WARNING' }
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify([agentFinding]), { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)
    const { POST } = await import('@/app/api/test-intelligence/agents/route')
    const body = await (await POST()).json()
    expect(body.findings).toEqual([{ ...agentFinding, checkType: 'advisory', detectedAt: '', rootCause: null, proposalUrl: null, status: 'open' }])
    const outbound = JSON.parse(fetchMock.mock.calls[0][1].body as string)
    expect(outbound.components[0]).toEqual({ component: 'openbank-ledger-service', moneyPath: true,
      evidence: [{ kind: 'integration', state: 'stale' }], declaredInfrastructure: ['postgres'], observedInfrastructureStarts: 1,
      flakyTests: 1, failingTests: 1, sameCommitTransitions: 2, wastedDurationMs: 1750 })
    expect(outbound.components[1]).toEqual({ component: 'openbank-app', moneyPath: true,
      evidence: [{ kind: 'visual', state: 'passed' }], declaredInfrastructure: [], observedInfrastructureStarts: 0,
      flakyTests: 0, failingTests: 0, sameCommitTransitions: 0, wastedDurationMs: 0 })
    expect(outbound.components[0]).toMatchObject({ flakyTests: 1, failingTests: 1, sameCommitTransitions: 2, wastedDurationMs: 1750 })
    expect(JSON.stringify(outbound)).not.toContain('/private/path')
    expect(JSON.stringify(outbound)).not.toContain('/private/app/path')
    expect(JSON.stringify(outbound)).not.toContain('sampledSpansLast7d')
    expect(JSON.stringify(outbound)).not.toContain('private test name')
    expect(JSON.stringify(outbound)).not.toContain('private-fingerprint')
    rmSync(dir, { recursive: true, force: true })
  })

  it('fails closed for unrecognised snapshot vocabulary before AI analysis', async () => {
    const dir = mkdtempSync(path.join(tmpdir(), 'test-intelligence-agent-'))
    const file = path.join(dir, 'report.json')
    writeFileSync(file, JSON.stringify({
      schemaVersion: 1, collectedAt: '2026-08-22T10:00:00Z',
      components: [
        { component: 'openbank-ledger-service', moneyPath: true,
          evidence: [{ kind: 'future-evidence-kind', state: 'future-state' }],
          testInfrastructure: { declared: ['untrusted-resource'], observed: [{ lifecycle: 'started' }] } },
        { component: '../outside-the-contract', moneyPath: true, evidence: [], testInfrastructure: { declared: [], observed: [] } },
      ], clientExperiences: [],
    }))
    process.env.OPENBANK_TEST_INTELLIGENCE = file
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify([]), { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)
    const { POST } = await import('@/app/api/test-intelligence/agents/route')
    expect((await POST()).status).toBe(200)
    const outbound = JSON.parse(fetchMock.mock.calls[0][1].body as string)
    expect(outbound.components).toEqual([{
      component: 'openbank-ledger-service', moneyPath: true,
      evidence: [{ kind: 'unknown', state: 'unknown' }], declaredInfrastructure: [], observedInfrastructureStarts: 1,
      flakyTests: 0, failingTests: 0, sameCommitTransitions: 0, wastedDurationMs: 0,
    }])
    rmSync(dir, { recursive: true, force: true })
  })

  it('normalizes advisory agent output before it reaches the browser', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify([
      { id: 'safe-1', component: 'openbank-ledger-service', title: 'Investigate retry', severity: 'CRITICAL', rootCause: 'timeout', proposalUrl: 'https://github.com/JiRaska/open-bank-oss/pull/1' },
      { id: 'unsafe-link', component: 'openbank-ledger-service', title: 'Do not render a command', severity: 'WARNING', proposalUrl: 'javascript:alert(1)' },
      { id: 'invalid-component', component: '../outside', title: 'Reject', severity: 'WARNING' },
      { id: 'invalid-severity', component: 'openbank-ledger-service', title: 'Reject', severity: 'PASSED' },
    ]), { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)
    const { GET } = await import('@/app/api/test-intelligence/agents/route')
    const body = await (await GET()).json()
    expect(body.findings).toEqual([
      expect.objectContaining({ id: 'safe-1', proposalUrl: 'https://github.com/JiRaska/open-bank-oss/pull/1' }),
      expect.objectContaining({ id: 'unsafe-link', proposalUrl: null }),
    ])
  })
})
