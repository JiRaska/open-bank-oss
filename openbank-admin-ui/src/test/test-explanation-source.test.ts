// SPDX-License-Identifier: Apache-2.0
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { workspaceReport } from '../../e2e/fixtures/test-intelligence-workspace'
const mocks = vi.hoisted(() => ({ read: vi.fn(), open: vi.fn(), close: vi.fn(), payload: '', base: vi.fn() }))
vi.mock('server-only', () => ({}))
vi.mock('node:fs', () => ({ promises: { open: mocks.open } }))
vi.mock('@/lib/evidence/publishedSnapshot', () => ({ createEvidenceSnapshotReader: () => ({ read: mocks.read }) }))
vi.mock('@/lib/evidence/testIntelligenceSnapshot', async importOriginal => ({ ...await importOriginal<typeof import('@/lib/evidence/testIntelligenceSnapshot')>(), testIntelligenceSnapshotBaseUrl: mocks.base }))
import { readTestExplanationSource } from '@/lib/evidence/testExplanationSource'
beforeEach(() => {
  vi.clearAllMocks()
  mocks.base.mockReturnValue(null)
  mocks.payload = JSON.stringify(workspaceReport())
  mocks.open.mockResolvedValue({ close: mocks.close, read: async (buffer: Buffer, offset: number, length: number, position: number) => {
    const source = Buffer.from(mocks.payload)
    const bytesRead = source.copy(buffer, offset, position, Math.min(source.length, position + length))
    return { bytesRead, buffer }
  } })
})
describe('test explanation source', () => {
  it('uses a validated published snapshot without reading a bundled fallback', async () => {
    const report = workspaceReport()
    mocks.read.mockResolvedValue({ status: 'ready', snapshot: report })
    expect(await readTestExplanationSource()).toEqual(report)
    expect(mocks.open).not.toHaveBeenCalled()
  })
  it('does not silently substitute a bundled report during a configured outage', async () => {
    mocks.base.mockReturnValue('https://snapshot.example.invalid')
    mocks.read.mockResolvedValue({ status: 'unavailable', snapshot: null })
    expect(await readTestExplanationSource()).toBeNull()
    expect(mocks.open).not.toHaveBeenCalled()
  })
  it('validates the bounded local snapshot only when remote publication is disabled', async () => {
    mocks.read.mockResolvedValue({ status: 'disabled', snapshot: null })
    expect(await readTestExplanationSource()).toEqual(workspaceReport())
    mocks.payload = '{}'
    expect(await readTestExplanationSource()).toBeNull()
    mocks.payload = 'x'.repeat(17 * 1024 * 1024)
    mocks.close.mockClear()
    expect(await readTestExplanationSource()).toBeNull()
    expect(mocks.close).toHaveBeenCalledOnce()
  })
  it('keeps reader exceptions unavailable without leaking or substituting data', async () => {
    mocks.read.mockRejectedValue(new Error('unavailable'))
    expect(await readTestExplanationSource()).toBeNull()
    expect(mocks.open).not.toHaveBeenCalled()
  })
})
