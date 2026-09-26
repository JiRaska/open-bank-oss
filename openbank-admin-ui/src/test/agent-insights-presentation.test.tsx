// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AgentInsightsPanel } from '@/components/agent/AgentInsightsPanel'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'

afterEach(cleanup)

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

    expect(screen.getByText('FUTURE_STATE')).toHaveStyle({
      color: 'var(--text-secondary)',
      background: 'var(--surface-3)',
    })
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

    expect(screen.getByText('critical')).toHaveStyle({ color: 'var(--danger-text)', background: 'var(--danger-bg)' })
    expect(screen.getByText('APPROVED')).toHaveStyle({ color: 'var(--success-text)', background: 'var(--success-bg)' })
    expect(screen.getByText('Evidence')).toHaveStyle({ color: 'var(--accent-text)', background: 'var(--accent-bg)' })
    expect(screen.getByRole('button', { name: 'Approve' })).toHaveStyle({
      color: 'var(--success-text)',
      background: 'var(--success-bg)',
    })
  })
})
