// SPDX-License-Identifier: Apache-2.0
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { ContextualInsights } from '@/components/insights/ContextualInsights'
import { PAYMENT_INSIGHTS } from '@/components/insights/catalog'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'

afterEach(() => { cleanup(); vi.useRealTimers() })

it('presents selected Grafana panels as native, period-aware insight cards', () => {
  vi.useFakeTimers()
  render(<LanguageProvider initialLanguage="en">
    <ContextualInsights dashboardUid="openbank-sla" panels={PAYMENT_INSIGHTS}
      titleCs="Zdraví plateb" titleEn="Payment health"
      descriptionCs="Stav" descriptionEn="Health"
      from="2026-09-01" to="2026-09-08" defaultOpen />
  </LanguageProvider>)

  expect(screen.getByRole('button', { name: /Payment health/ })).toHaveAttribute('aria-expanded', 'true')
  const toggle = screen.getByRole('button', { name: /Payment health/ })
  const controlled = document.getElementById(toggle.getAttribute('aria-controls') ?? '')
  expect(controlled).toHaveAttribute('role', 'region')
  expect(controlled).toHaveAccessibleName('Payment health')
  const panel = screen.getByTitle('Payment success rate')
  expect(panel).toHaveAttribute('src', expect.stringContaining('/tools/grafana/d-solo/openbank-sla?'))
  expect(panel).toHaveAttribute('src', expect.stringContaining('panelId=6'))
  expect(panel).toHaveAttribute('src', expect.stringContaining('kiosk=1'))
  expect(panel).toHaveAttribute('src', expect.stringContaining('from=1788220800000'))
  expect(screen.getByRole('link', { name: 'Detailed analysis' }).getAttribute('href')).not.toContain('kiosk=')

  const doc = document.implementation.createHTMLDocument()
  doc.body.innerHTML = '<div class="panel-content"></div>'
  Object.defineProperty(panel, 'contentDocument', { value: doc })
  act(() => vi.advanceTimersByTime(500))
  expect(panel).toBeVisible()
  expect(screen.queryAllByText('Loading data…')).toHaveLength(PAYMENT_INSIGHTS.length - 1)
})

it('recovers when Grafana becomes ready after the slow-connection warning', () => {
  vi.useFakeTimers()
  render(<LanguageProvider initialLanguage="en">
    <ContextualInsights dashboardUid="openbank-slo" panels={PAYMENT_INSIGHTS.slice(0, 1)}
      titleCs="Dopad" titleEn="Impact" descriptionCs="Stav" descriptionEn="Health" defaultOpen />
  </LanguageProvider>)
  const panel = screen.getByTitle('Payment success rate')
  act(() => vi.advanceTimersByTime(20_500))
  expect(screen.getByText('Connection is taking longer; still trying…')).toBeInTheDocument()
  const doc = document.implementation.createHTMLDocument()
  doc.body.innerHTML = '<div class="panel-content"></div>'
  Object.defineProperty(panel, 'contentDocument', { value: doc })
  act(() => vi.advanceTimersByTime(500))
  expect(panel).toBeVisible()
  expect(screen.queryByText('Connection is taking longer; still trying…')).not.toBeInTheDocument()
})

it('reveals a panel when its Grafana frame finishes loading', () => {
  render(<LanguageProvider initialLanguage="en">
    <ContextualInsights dashboardUid="openbank-slo" panels={PAYMENT_INSIGHTS.slice(0, 1)}
      titleCs="Dopad" titleEn="Impact" descriptionCs="Stav" descriptionEn="Health" defaultOpen />
  </LanguageProvider>)
  const panel = screen.getByTitle('Payment success rate')
  fireEvent.load(panel)
  expect(panel).toBeVisible()
  expect(screen.queryByText('Loading data…')).not.toBeInTheDocument()
})

it('keeps optional operational context collapsed until requested', () => {
  render(<LanguageProvider initialLanguage="en">
    <ContextualInsights dashboardUid="openbank-evb" panels={PAYMENT_INSIGHTS.slice(0, 1)}
      titleCs="Tok událostí" titleEn="Event processing" descriptionCs="Stav" descriptionEn="Health" />
  </LanguageProvider>)
  const toggle = screen.getByRole('button', { name: /Event processing/ })
  expect(toggle).toHaveAttribute('aria-expanded', 'false')
  expect(screen.queryByTitle('Payment success rate')).not.toBeInTheDocument()
  fireEvent.click(toggle)
  expect(screen.getByTitle('Payment success rate')).toBeInTheDocument()
})
