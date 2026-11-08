// SPDX-License-Identifier: Apache-2.0
import { act, renderHook, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { useTestEvidenceView } from '@/components/testing/workspace/useTestEvidenceView'
import { workspaceCase, workspaceReport, workspaceObservedAt } from '../../e2e/fixtures/test-intelligence-workspace'
import { selectTestReadView, type TestReadRequest } from '@/lib/test-intelligence-delivery'
const request: TestReadRequest = { view: 'case', component: workspaceCase.component, kind: 'unit', fingerprint: workspaceCase.fingerprint, collectedAt: workspaceObservedAt }
afterEach(() => vi.unstubAllGlobals())
it('aborts superseded detail requests and refuses late responses for the previous selection', async () => {
  const pending: Array<{ finish: (value: Response) => void; signal: AbortSignal }> = []
  vi.stubGlobal('fetch', vi.fn((_url: string, options: RequestInit) => new Promise<Response>(finish => pending.push({ finish, signal: options.signal as AbortSignal }))))
  const { result, rerender } = renderHook(({ selected }: { selected: TestReadRequest }) => useTestEvidenceView(selected), { initialProps: { selected: request } })
  await waitFor(() => expect(pending).toHaveLength(1))
  rerender({ selected: { ...request, fingerprint: 'other-case' } })
  await waitFor(() => expect(pending).toHaveLength(2))
  expect(pending[0].signal.aborted).toBe(true)
  await act(async () => { pending[0].finish(new Response(JSON.stringify(workspaceReport()))) })
  expect(result.current.value).toBeNull()
  expect(result.current.loading).toBe(true)
  const report = workspaceReport({ testCases: [{ ...workspaceCase, fingerprint: 'other-case' }] })
  await act(async () => { pending[1].finish(new Response(JSON.stringify(report))) })
  await waitFor(() => expect(result.current.loading).toBe(false))
  expect(result.current.value).toMatchObject({ view: 'case', testCase: { fingerprint: 'other-case' } })
})
it('preserves last good evidence after a failed background refresh and rejects a changed collection', async () => {
  const selected = selectTestReadView(workspaceReport(), request)
  if (!selected.ok) throw new Error('fixture')
  vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(new Response(JSON.stringify(selected.value))).mockResolvedValueOnce(new Response('{}', { status: 503 })))
  const { result, rerender } = renderHook(({ refresh }: { refresh: string }) => useTestEvidenceView(request, refresh), { initialProps: { refresh: 'first' } })
  await waitFor(() => expect(result.current.value).not.toBeNull())
  rerender({ refresh: 'second' })
  await waitFor(() => expect(result.current.error).toBe('unavailable'))
  expect(result.current.value).toEqual(selected.value)
  vi.mocked(fetch).mockResolvedValueOnce(new Response(JSON.stringify({ ...selected.value, collectedAt: '2026-11-09T00:00:00Z' })))
  rerender({ refresh: 'third' })
  await waitFor(() => expect(result.current.error).toBe('evidence_changed'))
  expect(result.current.value).toEqual(selected.value)
})
