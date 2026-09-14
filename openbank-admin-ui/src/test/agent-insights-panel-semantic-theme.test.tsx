// SPDX-License-Identifier: Apache-2.0

import { fireEvent, render, screen } from '@testing-library/react'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it, vi } from 'vitest'
import { AgentInsightsPanel } from '@/components/agent/AgentInsightsPanel'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'

const source = readFileSync(path.resolve(__dirname, '../components/agent/AgentInsightsPanel.tsx'), 'utf8')

describe('AgentInsightsPanel semantic theme contract', () => {
  it('derives all presentation colours from shared light/dark tokens', () => {
    expect(source).not.toMatch(/#[0-9a-f]{3,8}\b|rgba?\(/i)
    for (const token of [
      '--accent-bg', '--accent-text', '--danger-bg', '--danger-text',
      '--info-bg', '--info-text', '--success-bg', '--success-text',
      '--warning-bg', '--warning-text', '--surface', '--surface-2',
      '--text-primary', '--text-secondary', '--text-tertiary',
    ]) {
      expect(source).toContain(`var(${token})`)
    }
  })

  it('keeps custom accents valid and preserves HITL decisions', () => {
    const approve = vi.fn()
    const reject = vi.fn()
    expect(source).toContain('color-mix(in srgb, ${accentColor} 25%, transparent)')
    render(
      <LanguageProvider initialLanguage="en">
        <AgentInsightsPanel
          title="Agent findings"
          findings={[{
            id: 'finding-1', title: 'Review this finding', detector: 'D1',
            severity: 'critical', status: 'PROPOSED', rootCause: 'Evidence needs a human decision.',
          }]}
          emptyMessage="No findings"
          accentColor="var(--danger)"
          onApprove={approve}
          onReject={reject}
        />
      </LanguageProvider>,
    )

    fireEvent.click(screen.getByRole('button', { name: 'Approve' }))
    fireEvent.click(screen.getByRole('button', { name: 'Reject' }))
    expect(approve).toHaveBeenCalledWith('finding-1')
    expect(reject).toHaveBeenCalledWith('finding-1')
  })
})
