// SPDX-License-Identifier: Apache-2.0
import { expect, it, vi } from 'vitest'

const { loadBpmnContent } = vi.hoisted(() => ({ loadBpmnContent: vi.fn() }))
vi.mock('@/lib/docs/bpmn/load', () => ({ loadBpmnContent }))
vi.mock('@/components/docs/DocsHub', () => ({ DocsHub: () => null }))
import DocsPage from '@/app/docs/page'

it('counts the current published BPMN inventory rather than the image copy', async () => {
  loadBpmnContent.mockResolvedValue({ processes: Array.from({ length: 18 }, (_, i) => ({ slug: `process-${i}` })) })
  expect((await DocsPage()).props.bpmnCount).toBe(18)
  loadBpmnContent.mockResolvedValue({ processes: [] })
  expect((await DocsPage()).props.bpmnCount).toBe(0)
})
