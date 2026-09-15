// SPDX-License-Identifier: Apache-2.0

import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { AgentInsightsPanel } from '@/components/agent/AgentInsightsPanel'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'

const findings = [
  {
    id: 'finding-1',
    title: 'Idle capacity detected',
    detector: 'COST_IDLE',
    severity: 'warning' as const,
    status: 'PROPOSED',
    rootCause: 'Capacity exceeds the observed demand.',
    proposalUrl: 'https://example.test/proposal/1',
    proposalLabel: 'Review proposal',
    tags: [{ label: '€420/mo', tone: 'success' as const }],
  },
]

describe('AgentInsightsPanel', () => {
  it('keeps a human in control and exposes semantic states', () => {
    const approve = vi.fn()
    const reject = vi.fn()
    render(
      <LanguageProvider initialLanguage="en">
        <AgentInsightsPanel
          title="Cost insights"
          subtitle="Agent proposals require review."
          findings={findings}
          emptyMessage="No findings"
          onApprove={approve}
          onReject={reject}
          decideLabels={{ approve: 'Approve proposal', reject: 'Reject proposal' }}
        />
      </LanguageProvider>,
    )

    expect(screen.getByText('PROPOSED')).toHaveStyle({ color: 'var(--warning-text)' })
    expect(screen.getByText('COST_IDLE')).toHaveStyle({ color: 'var(--accent-text)' })
    expect(screen.getByRole('link', { name: 'Review proposal' })).toHaveStyle({ textDecoration: 'underline' })
    fireEvent.click(screen.getByRole('button', { name: 'Approve proposal' }))
    fireEvent.click(screen.getByRole('button', { name: 'Reject proposal' }))
    expect(approve).toHaveBeenCalledWith('finding-1')
    expect(reject).toHaveBeenCalledWith('finding-1')
  })

  it('disables both decisions while the finding is being decided', () => {
    render(
      <LanguageProvider initialLanguage="en">
        <AgentInsightsPanel
          title="Cost insights"
          findings={findings}
          emptyMessage="No findings"
          onApprove={vi.fn()}
          onReject={vi.fn()}
          decidingId="finding-1"
        />
      </LanguageProvider>,
    )

    for (const button of screen.getAllByRole('button')) {
      expect(button).toBeDisabled()
      expect(button).toHaveAttribute('aria-busy', 'true')
    }
  })

  it('never turns an untrusted proposal scheme into a clickable action', () => {
    render(
      <LanguageProvider initialLanguage="en">
        <AgentInsightsPanel
          title="Cost insights"
          findings={[{ ...findings[0], proposalUrl: 'javascript:alert(document.domain)' }]}
          emptyMessage="No findings"
        />
      </LanguageProvider>,
    )

    expect(screen.queryByRole('link', { name: 'Review proposal' })).not.toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('Proposal link is not safely available')
  })
})
