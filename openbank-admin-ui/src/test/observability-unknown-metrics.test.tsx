// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import React from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import ObservabilityPage from '@/app/observability/page'

vi.mock('@/components/auth/AuthGuard', () => ({
  AuthGuard: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}))

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('Business observability unknown metric states', () => {
  it('never presents missing values with healthy or unhealthy severity colors', async () => {
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL) => {
      if (String(input).endsWith('/-/ready')) return Promise.resolve(new Response('ready'))
      return Promise.resolve(new Response(JSON.stringify({ status: 'success', data: { result: [] } })))
    }))

    render(<LanguageProvider><ObservabilityPage /></LanguageProvider>)

    await screen.findByText('Prometheus Connected')
    for (const label of ['Service Availability', 'Service Error Rate', 'p99 Latency', 'Edge Error Rate', 'Failed Payments']) {
      const card = screen.getByText(label).closest('.stat-card')
      expect(card).toHaveAttribute('data-metric-state', 'unknown')
      expect(card?.querySelector('[data-metric-icon]')).toHaveStyle({ color: 'var(--text-muted)' })
      expect(card).toHaveTextContent('N/A')
    }
  })
})
