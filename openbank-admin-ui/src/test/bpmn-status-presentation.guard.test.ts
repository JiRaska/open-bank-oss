// SPDX-License-Identifier: Apache-2.0
import { readFileSync } from 'node:fs'
import path from 'node:path'
import React from 'react'
import { describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import { BpmnView } from '@/components/docs/BpmnView'
import { loadBpmnProcess } from '@/lib/docs/bpmn/load'

const source = () => readFileSync(path.resolve(__dirname, '../components/docs/BpmnView.tsx'), 'utf8')

describe('BPMN service status presentation', () => {
  it('uses the shared semantic status system without changing operator labels', () => {
    const component = source()

    expect(component).toContain("import { StatusBadge } from '@/components/ui'")
    expect(component).toContain('<StatusBadge status="up" label={t(\'AKTIVNÍ\', \'UP\')}')
    expect(component).toContain('<StatusBadge status="down" label={t(\'NEDOSTUPNÉ\', \'DOWN\')}')
    expect(component).toContain('<StatusBadge status="loading" tone="warning" label={t(\'OVĚŘUJI\', \'CHECKING\')}')
    expect(component).toContain('<StatusBadge status="unknown" label={t(\'N/A\', \'N/A\')}')
    expect(component).toContain("{ shape: 'status-up', color: 'var(--success-text)'")
    expect(component).toContain("{ shape: 'status-down', color: 'var(--danger-text)'")
    expect(component).not.toContain("color: '#16a34a', fontWeight: 600, background: '#dcfce7'")
    expect(component).not.toContain("color: '#dc2626', fontWeight: 600, background: '#fee2e2'")
  })

  it('reports health-probe failures as unknown and checks status automatically', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('network unavailable')))
    render(React.createElement(
      LanguageProvider,
      { initialLanguage: 'en' },
      React.createElement(BpmnView, { processes: [loadBpmnProcess('account-opening')] }),
    ))

    await waitFor(() => expect(screen.getAllByText('N/A').length).toBeGreaterThan(0))
    expect(fetch).toHaveBeenCalledWith('/api/services/health', expect.objectContaining({ cache: 'no-store', signal: expect.any(AbortSignal) }))
    expect(screen.getByText(/Status checked/)).toBeTruthy()
    expect(screen.getByText('This is an authored process reference; it does not show live workflow execution.')).toBeTruthy()
    vi.unstubAllGlobals()
  })
})
