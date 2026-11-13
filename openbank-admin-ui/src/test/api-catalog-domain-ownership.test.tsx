// SPDX-License-Identifier: Apache-2.0
import React from 'react'
import { afterEach, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import ApiCatalogPage from '@/app/docs/api/page'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'

const modules = [
  ['pension-service', 'pensions'], ['pension-fund-service', 'pensions'],
  ['wealth-service', 'wealth'], ['ledger-service', 'accounting'], ['fx-service', 'payments'],
] as const

afterEach(() => { cleanup(); vi.unstubAllGlobals() })

it('uses canonical ownership for API cards and filters, including modules with conflicting editorial prose', async () => {
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input)
    const json = (body: unknown) => new Response(JSON.stringify(body), { headers: { 'content-type': 'application/json' } })
    if (url === '/api/catalog/services') return json({ available: true, services: modules.map(([short, dataDomain], index) => ({
      name: `openbank-${short}`, short, kind: 'service', runnable: true, dataDomain, port: 9000 + index,
      apiVersion: '1.0.0', releaseVersion: '1.0.0', moneyPath: false, gaps: [],
    })) })
    if (url === '/api/services/health') return json({ source: 'static', discoveryStatus: 'static', services: [] })
    if (url === '/api/auth/session') return json(null)
    return json({})
  }))
  render(<LanguageProvider><ApiCatalogPage /></LanguageProvider>)
  const filters = await screen.findByRole('group', { name: 'Filter by domain' })
  const pensions = await within(filters).findByRole('button', { name: 'Pensions', exact: true })
  expect(within(filters).getByRole('button', { name: 'Financial accounting', exact: true })).toBeVisible()
  expect(within(filters).getByRole('button', { name: 'Wealth', exact: true })).toBeVisible()
  expect(within(filters).queryByRole('button', { name: 'Core Banking', exact: true })).toBeNull()
  fireEvent.click(pensions)
  expect(screen.getByText('Pension Service', { exact: true })).toBeVisible()
  expect(screen.getByText('Pension Fund Service', { exact: true })).toBeVisible()
  expect(screen.queryByText('Wealth Service', { exact: true })).toBeNull()
  expect(screen.queryByText('Ledger Service', { exact: true })).toBeNull()
  fireEvent.click(within(filters).getByRole('button', { name: 'Financial accounting', exact: true }))
  expect(screen.getByText('Ledger Service', { exact: true })).toBeVisible()
  expect(screen.queryByText('Pension Service', { exact: true })).toBeNull()
})
