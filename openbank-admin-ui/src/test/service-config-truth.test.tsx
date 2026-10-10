// SPDX-License-Identifier: Apache-2.0

import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import ServiceConfigPage from '@/app/system/config/page'

afterEach(() => { cleanup(); vi.unstubAllGlobals() })

it('keeps intentional scale-to-zero distinct from an outage or default configuration', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => Response.json([
    { name: 'communication-service', port: 8120, config: null, health: { status: 'UNKNOWN', checks: [] }, reachable: false, scaledToZero: true, latencyMs: 1 },
    { name: 'account-service', port: 8100, config: null, health: { status: 'UP', checks: [] }, reachable: true, scaledToZero: false, latencyMs: 1 },
  ])))
  render(<LanguageProvider><ServiceConfigPage /></LanguageProvider>)

  expect(await screen.findByText('communication-service')).toBeInTheDocument()
  expect(screen.getAllByText('Scaled to zero').length).toBeGreaterThan(0)
  fireEvent.click(screen.getByRole('button', { name: /communication-service/ }))
  expect(screen.getByText(/intentionally scaled to zero/)).toBeInTheDocument()
  expect(screen.queryByText('defaults only')).not.toBeInTheDocument()
  expect(screen.queryByText('1 Unreachable')).not.toBeInTheDocument()
})
