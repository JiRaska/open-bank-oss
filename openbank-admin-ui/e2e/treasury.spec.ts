// SPDX-License-Identifier: Apache-2.0
// ADR-0315 / #10618 smoke: the treasury desk reaches the Treasury section. Backends are mocked (as
// in every spec here); the hide-approve-from-the-creator rule is covered by treasury-pages.test.tsx.
import { expect, test } from '@playwright/test'
import { signInWithRoles } from './helpers/auth'

const deal = (dealId: string, state: string, createdBy: string) => ({
  dealId, product: 'MM_PLACEMENT', counterpartyId: 'SIM-A', currency: 'CZK', principal: 1000000, rate: 4.25,
  dayCount: 'ACT/360', days: 3, interest: 354.17, tradeDate: '2026-09-25', valueDate: '2026-09-25', maturityDate: '2026-09-28',
  state, createdBy, createdByType: 'HUMAN', submittedBy: createdBy, approvedBy: null, rationale: null, limitCheck: null,
  createdAt: '2026-09-25T08:00:00Z', updatedAt: '2026-09-25T08:00:00Z', history: [], journals: [],
})
const counterparties = [
  { counterpartyId: 'SIM-A', name: 'Sim Bank A', kind: 'BANK', synthetic: true, currency: 'CZK', limit: 5000000, exposure: 1000000, headroom: 4000000 },
]

test.beforeEach(async ({ context, baseURL }) => {
  await signInWithRoles(context, baseURL!, ['ROLE_TREASURY_APPROVER'])
})

test('approver sees the blotter with the synthetic counterparty label and no new-deal button', async ({ page }) => {
  await page.route('**/api/svc/treasury-service/api/v1/treasury/counterparties', route => route.fulfill({
    status: 200, contentType: 'application/json', body: JSON.stringify(counterparties),
  }))
  await page.route('**/api/svc/treasury-service/api/v1/treasury/deals**', route => route.fulfill({
    status: 200, contentType: 'application/json', body: JSON.stringify([deal('d-1', 'BOOKED', 'dana.dealer')]),
  }))
  await page.goto('/treasury/deals')
  const main = page.locator('main')
  await expect(main.getByText('dana.dealer')).toBeVisible({ timeout: 20_000 })
  await expect(main.getByText(/Simulovaná protistrana|Synthetic counterparty/)).toBeVisible()
  await expect(main.getByRole('link', { name: /Nový obchod|New deal/ })).toHaveCount(0)
  await expect(main.getByRole('heading', { name: /Nejdřív obchod|First the deal/ })).toBeVisible()
  await expect(main.getByRole('link', { name: /Ke schválení|Approval inbox/ })).toBeVisible()
  await expect(main.getByLabel(/Přehled treasury|Treasury overview/).getByText('1', { exact: true })).toBeVisible()
})

test('approval inbox offers approve on a pending deal', async ({ page }) => {
  // The e2e session's access token is not a JWT, so the actor is unknown and the page must NOT
  // hide approve (the server's 422 is the control).
  await page.route('**/api/svc/treasury-service/api/v1/treasury/counterparties', route => route.fulfill({
    status: 200, contentType: 'application/json', body: JSON.stringify(counterparties),
  }))
  await page.route('**/api/svc/treasury-service/api/v1/treasury/deals**', route => route.fulfill({
    status: 200, contentType: 'application/json', body: JSON.stringify([deal('d-2', 'PENDING_APPROVAL', 'dana.dealer')]),
  }))
  await page.goto('/treasury/approvals')
  const main = page.locator('main')
  await expect(main.getByText(/dana\.dealer/)).toBeVisible({ timeout: 20_000 })
  await expect(main.getByRole('button', { name: /^(Schválit|Approve)$/ })).toHaveCount(1)
})

// ── ADR-0344 workbench: deal ticket → schedule preview → submit ─────────────────────────────────
// Backends are mocked as above. The dealer's access token is not a JWT here, so the actor is
// unknown and nothing four-eyes-related is hidden client-side; the server's 422 is the control.
const json = (body: unknown, status = 200) => ({ status, contentType: 'application/json', body: JSON.stringify(body) })

test.describe('deal ticket', () => {
  test.beforeEach(async ({ context, baseURL }) => {
    await signInWithRoles(context, baseURL!, ['ROLE_TREASURY_DEALER'])
  })

  test('dealer drafts a deal, previews its cash-flow schedule inline, then submits it for a second person', async ({ page }) => {
    const requests: string[] = []
    const flow = (n: number) => ({
      flowId: `f-${n}`, legIndex: 0, periodIndex: n, kind: 'INTEREST', direction: 'IN', currency: 'CZK', accrualStart: '2026-10-21', accrualEnd: '2027-01-21',
      fixingDate: null, paymentDate: '2027-01-21', notional: 1000000, rate: 4.25, rateSource: 'FIXED', amount: 10590.28, state: 'PROJECTED', manual: false,
      original: null, override: null, paymentId: null, journalRefs: [],
    })
    await page.route('**/api/svc/treasury-service/api/v1/treasury/counterparties', route => route.fulfill(json(counterparties)))
    await page.route('**/api/svc/treasury-service/api/v1/treasury/deals**', route => {
      const req = route.request()
      requests.push(`${req.method()} ${new URL(req.url()).pathname}`)
      const path = new URL(req.url()).pathname
      if (req.method() === 'POST' && path.endsWith('/deals')) return route.fulfill(json({ ...deal('d-new', 'DRAFT', 'e2e-operator'), submittedBy: null }, 201))
      if (path.endsWith('/d-new/schedule-preview')) {
        return route.fulfill(json({ dealId: 'd-new', dealVersion: 1, scheduleVersion: 1, scheduleHash: 'abc123def456', generatedAt: '2026-10-19T08:00:00Z', flows: [flow(0), flow(1)] }))
      }
      if (path.endsWith('/d-new/submit')) return route.fulfill(json({ ...deal('d-new', 'PENDING_APPROVAL', 'e2e-operator') }))
      return route.fulfill(json([]))
    })
    await page.goto('/treasury/deals/new')
    const main = page.locator('main')
    await expect(main.getByLabel(/^(Produkt|Product)$/)).toBeVisible({ timeout: 20_000 })
    await main.getByLabel(/^(Protistrana|Counterparty)$/).selectOption('SIM-A')
    await main.getByLabel(/^(Jistina|Principal)$/).fill('1000000')
    await main.getByLabel(/Annual rate in percent|Roční sazba/).fill('4.25')
    await main.getByRole('button', { name: /Generate schedule|Vygenerovat harmonogram/ }).click()
    const grid = main.getByTestId('cashflow-grid')
    await expect(grid).toBeVisible()
    await expect(grid.getByRole('row')).toHaveCount(3)
    await expect(main.getByTestId('preview-note')).toBeVisible()
    await main.getByRole('button', { name: /Submit for approval|Předložit ke schválení/ }).click()
    await expect(main.getByRole('status').filter({ hasText: /different person|jiná osoba/ })).toBeVisible()
    expect(requests).toEqual(expect.arrayContaining(['POST /api/v1/treasury/deals', 'POST /api/v1/treasury/deals/d-new/schedule-preview', 'POST /api/v1/treasury/deals/d-new/submit']))
    expect(requests.indexOf('POST /api/v1/treasury/deals/d-new/schedule-preview')).toBeLessThan(requests.indexOf('POST /api/v1/treasury/deals/d-new/submit'))
  })

  test('an interest-rate swap ticket shows two leg panels and says plainly when the preview is not served', async ({ page }) => {
    await page.route('**/api/svc/treasury-service/api/v1/treasury/counterparties', route => route.fulfill(json(counterparties)))
    await page.route('**/api/svc/treasury-service/api/v1/treasury/deals**', route => {
      const req = route.request()
      if (req.method() === 'POST' && new URL(req.url()).pathname.endsWith('/deals')) return route.fulfill(json(deal('d-irs', 'DRAFT', 'e2e-operator'), 201))
      return route.fulfill({ status: 404, body: '' })
    })
    await page.goto('/treasury/deals/new')
    const main = page.locator('main')
    await main.getByLabel(/^(Produkt|Product)$/).selectOption('IRS')
    await expect(main.getByLabel(/Leg notional|Jistina nohy/)).toHaveCount(2)
    await main.getByLabel(/^(Protistrana|Counterparty)$/).selectOption('SIM-A')
    await main.getByLabel(/Portfolio \(book\)|Portfolio \(kniha\)/).fill('BANKING')
    await main.getByLabel(/Leg notional|Jistina nohy/).nth(0).fill('1000000')
    await main.getByLabel(/Leg notional|Jistina nohy/).nth(1).fill('1000000')
    await main.getByLabel(/Accrual start|Začátek akruálu/).nth(0).fill('2026-10-21')
    await main.getByLabel(/Accrual start|Začátek akruálu/).nth(1).fill('2026-10-21')
    await main.getByLabel(/Accrual end|Konec akruálu/).nth(0).fill('2027-10-21')
    await main.getByLabel(/Accrual end|Konec akruálu/).nth(1).fill('2027-10-21')
    await main.getByLabel(/Fixed rate|Pevná sazba/).fill('4.25')
    await main.getByLabel(/Reference index|Referenční index/).fill('PRIBOR_3M')
    await main.getByRole('button', { name: /Generate schedule|Vygenerovat harmonogram/ }).click()
    await expect(main.getByTestId('not-available')).toContainText(/Not yet available on this deployment|Zatím není dostupné/)
    await expect(main.getByTestId('cashflow-grid')).toHaveCount(0)
  })
})

test.describe('workbench areas', () => {
  test('a payment releaser sees the payment queue and the release action; an unserved calendar is stated, not blank', async ({ page, context, baseURL }) => {
    await signInWithRoles(context, baseURL!, ['ROLE_TREASURY_PAYMENT_RELEASER'])
    await page.route('**/api/svc/treasury-service/api/v1/treasury/payments**', route => route.fulfill(json([{
      paymentId: 'p-1', status: 'PROPOSED', direction: 'OUT', counterpartyId: 'SIM-A', counterpartyName: 'Sim Bank A', currency: 'CZK', amount: 1000, valueDate: '2026-10-21',
      rail: 'CERTIS', nettedFlowIds: ['a'], dealIds: ['d-1'], createdBy: 'system', dealCreators: ['dana.dealer'],
    }])))
    await page.route('**/api/svc/treasury-service/api/v1/treasury/calendar**', route => route.fulfill({ status: 404, body: '' }))
    await page.goto('/treasury/payments')
    const main = page.locator('main')
    await expect(main.getByText('Sim Bank A')).toBeVisible({ timeout: 20_000 })
    await expect(main.getByRole('button', { name: /^(Release|Uvolnit)$/ })).toHaveCount(1)
    await page.goto('/treasury/calendar')
    await expect(main.getByTestId('not-available')).toContainText(/Not yet available on this deployment|Zatím není dostupné/)
  })

  test('the reference editor lists the code lists and a pending version offers approve to another person', async ({ page, context, baseURL }) => {
    await signInWithRoles(context, baseURL!, ['ROLE_TREASURY_APPROVER'])
    await page.route('**/api/svc/treasury-service/api/v1/treasury/reference/calendar**', route => route.fulfill(json([{
      kind: 'calendar', key: 'CZ-1', version: 1, status: 'PENDING', validFrom: '2026-10-19', validTo: null, verified: false,
      payload: { code: 'CZ-1', baseCalendar: 'CERTIS' }, createdBy: 'rita.editor', createdAt: '2026-10-19T08:00:00Z', reason: 'new calendar',
    }])))
    await page.goto('/treasury/reference')
    const main = page.locator('main')
    await expect(main.getByRole('navigation', { name: /kinds|druhy/i }).getByRole('button')).toHaveCount(33, { timeout: 20_000 })
    await main.getByRole('tab', { name: /Pending queue|Ke schválení/ }).click()
    await expect(main.getByRole('button', { name: /^(Approve|Schválit)$/ })).toHaveCount(1)
  })
})
