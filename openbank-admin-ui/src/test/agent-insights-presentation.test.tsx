// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AgentInsightsPanel } from '@/components/agent/AgentInsightsPanel'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'

afterEach(cleanup)

function expectSemanticTone(element: HTMLElement, color: string, background: string) {
  expect(element).toHaveStyle({ color })
  // Inspect the authored token: computed shorthand serialization varies across DOM versions.
  expect(element.style.getPropertyValue('background')).toBe(background)
}

describe('shared agent findings presentation', () => {
  it('does not reintroduce raw colour literals in the shared panel', () => {
    const source = readFileSync(path.join(process.cwd(), 'src/components/agent/AgentInsightsPanel.tsx'), 'utf8')
    expect(source).not.toMatch(/#[\da-f]{6}\b/i)
  })

  it('keeps a new lifecycle state neutral instead of suggesting it is OPEN', () => {
    render(
      <LanguageProvider initialLanguage="en">
        <AgentInsightsPanel title="Findings" emptyMessage="None" findings={[{
          id: 'one', title: 'Review this case', status: 'FUTURE_STATE',
        }]} />
      </LanguageProvider>,
    )

    expectSemanticTone(screen.getByText('FUTURE_STATE'), 'var(--text-secondary)', 'var(--surface-3)')
  })

  it('keeps decision controls hidden for rows rejected by the caller policy', () => {
    render(
      <LanguageProvider initialLanguage="en">
        <AgentInsightsPanel
          title="Findings" emptyMessage="None"
          findings={[{ id: 'future', title: 'Future finding', status: 'FUTURE_STATE' }]}
          onApprove={vi.fn()} onReject={vi.fn()}
          canDecideFinding={finding => finding.status === 'PROPOSED'}
        />
      </LanguageProvider>,
    )

    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Reject' })).not.toBeInTheDocument()
  })

  it('uses semantic AA text on finding tones and human-decision controls', () => {
    render(
      <LanguageProvider initialLanguage="en">
        <AgentInsightsPanel
          title="Findings" emptyMessage="None"
          findings={[{ id: 'one', title: 'Review this case', status: 'APPROVED', severity: 'critical',
            detector: 'D1', tags: [{ label: 'Evidence', tone: 'accent' }] }]}
          onApprove={vi.fn()} onReject={vi.fn()}
        />
      </LanguageProvider>,
    )

    expectSemanticTone(screen.getByText('critical'), 'var(--danger-text)', 'var(--danger-bg)')
    expectSemanticTone(screen.getByText('APPROVED'), 'var(--success-text)', 'var(--success-bg)')
    expectSemanticTone(screen.getByText('Evidence'), 'var(--accent-text)', 'var(--accent-bg)')
    expectSemanticTone(screen.getByRole('button', { name: 'Approve' }), 'var(--success-text)', 'var(--success-bg)')
  })
})
