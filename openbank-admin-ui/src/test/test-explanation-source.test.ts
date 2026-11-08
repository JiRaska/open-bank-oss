// SPDX-License-Identifier: Apache-2.0
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { workspaceReport } from '../../e2e/fixtures/test-intelligence-workspace'
const mocks = vi.hoisted(() => ({ read: vi.fn(), stat: vi.fn(), readFile: vi.fn(), base: vi.fn() }))
vi.mock('server-only', () => ({}))
vi.mock('node:fs', () => ({ promises: { stat: mocks.stat, readFile: mocks.readFile } }))
vi.mock('@/lib/evidence/publishedSnapshot', () => ({ createEvidenceSnapshotReader: () => ({ read: mocks.read }) }))
vi.mock('@/lib/evidence/testIntelligenceSnapshot', async importOriginal => ({ ...await importOriginal<typeof import('@/lib/evidence/testIntelligenceSnapshot')>(), testIntelligenceSnapshotBaseUrl: mocks.base }))
import { readTestExplanationSource } from '@/lib/evidence/testExplanationSource'
beforeEach(() => { vi.clearAllMocks(); mocks.base.mockReturnValue(null); mocks.stat.mockResolvedValue({ size: 1024 }); mocks.readFile.mockResolvedValue(JSON.stringify(workspaceReport())) })
describe('test explanation source', () => {
  it('uses a validated published snapshot without reading a bundled fallback', async () => {
    const report = workspaceReport()
    mocks.read.mockResolvedValue({ status: 'ready', snapshot: report })
    expect(await readTestExplanationSource()).toEqual(report)
    expect(mocks.readFile).not.toHaveBeenCalled()
  })
  it('does not silently substitute a bundled report during a configured outage', async () => {
    mocks.base.mockReturnValue('https://snapshot.example.invalid')
    mocks.read.mockResolvedValue({ status: 'unavailable', snapshot: null })
    expect(await readTestExplanationSource()).toBeNull()
    expect(mocks.readFile).not.toHaveBeenCalled()
  })
  it('validates the bounded local snapshot only when remote publication is disabled', async () => {
    mocks.read.mockResolvedValue({ status: 'disabled', snapshot: null })
    expect(await readTestExplanationSource()).toEqual(workspaceReport())
    mocks.readFile.mockResolvedValue('{}')
    expect(await readTestExplanationSource()).toBeNull()
    mocks.stat.mockResolvedValue({ size: 9 * 1024 * 1024 })
    mocks.readFile.mockClear()
    expect(await readTestExplanationSource()).toBeNull()
    expect(mocks.readFile).not.toHaveBeenCalled()
  })
  it('keeps reader exceptions unavailable without leaking or substituting data', async () => {
    mocks.read.mockRejectedValue(new Error('unavailable'))
    expect(await readTestExplanationSource()).toBeNull()
    expect(mocks.readFile).not.toHaveBeenCalled()
  })
})
