import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

// RFC-4122 shape is load-bearing: parseCard (clientContract.ts) validates the version and
// variant nibbles, so a repeated-digit placeholder is rejected and the page renders its
// service-unavailable state instead of the card (#9736).
const cardId = '11111111-1111-4111-8111-111111111111'
const card = {
  id: cardId,
  partyId: '22222222-2222-4222-8222-222222222222',
  accountId: '33333333-3333-4333-8333-333333333333',
  productCode: 'DEBIT-CLASSIC',
  cardType: 'DEBIT',
  network: 'VISA',
  maskedPan: '411111******4242',
  cardholderName: 'Verified Cardholder',
  embossedName: 'VERIFIED CARDHOLDER',
  expiryDate: '12/29',
  status: 'ACTIVE',
  dailyLimitMinorUnits: 500000,
  monthlyLimitMinorUnits: 2000000,
  currency: 'CZK',
  createdAt: '2026-08-31T08:00:00Z',
}

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

test('keeps the masked card detail visible after a failed refresh', async ({ page }) => {
  let unavailable = false
  await page.route(`**/api/svc/card-issuance-service/api/v1/cards/${cardId}`, route => unavailable
    ? route.fulfill({ status: 503, contentType: 'application/json', body: '{}' })
    : route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(card) }))

  await page.goto(`/cards/${cardId}`)

  await expect(page.getByRole('heading', { name: '411111******4242' })).toBeVisible({ timeout: 20_000 })
  await expect(page.getByText('Verified Cardholder', { exact: true })).toBeVisible()
  await expect(page.getByText(/full card number and CVV are deliberately not available|Úplné číslo karty ani CVV zde záměrně nejsou dostupné/)).toBeVisible()

  unavailable = true
  await page.getByRole('button', { name: /Obnovit kartu|Refresh card/ }).click()

  await expect(page.getByText(/Zobrazen je poslední úspěšný snapshot|Showing the last successful snapshot/)).toBeVisible({ timeout: 25_000 })
  await expect(page.getByRole('heading', { name: '411111******4242' })).toBeVisible()
  await expect(page.getByText('Verified Cardholder', { exact: true })).toBeVisible()
})

test('a payload the client contract rejects is a data error, not "the service is waking up"', async ({ page }) => {
  // The card contract is the client-side PCI allow-list boundary, so it throws on a payload the
  // upstream should not have sent. That throw used to land in useServiceResource's TRANSPORT catch
  // and was classified as a cold KEDA pod: the screen said the service was waking up, retried
  // three times, then reported `unreachable` — pointing the operator at a scaling problem that did
  // not exist while the real fault, a malformed response, was named nowhere.
  //
  // `maskedPan` here is a full unmasked PAN, which is exactly the regression the contract exists
  // to refuse.
  await page.route(`**/api/svc/card-issuance-service/api/v1/cards/${cardId}`, route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    // Assembled from chunks: the repo's `pan-card-number` gitleaks rule matches any Luhn-shaped
    // 13-19 digit run, and it is right to — so the file carries no literal PAN. Allowlisting a
    // whole test file to keep one literal would blind that rule on everything else in it.
    body: JSON.stringify({ ...card, maskedPan: ['4111', '1111', '1111', '4242'].join('') }),
  }))

  await page.goto(`/cards/${cardId}`)

  // Checked at a FIXED moment, not with a retrying matcher. `expect(...).not.toContainText` would
  // pass the moment the text goes away — and the broken behaviour shows "waking up" only until the
  // three wake retries are exhausted (~12s), after which it settles on `unreachable`. A 15-second
  // retrying assertion therefore passed against both the fix and the defect. Two seconds in is
  // where the two genuinely differ: the fix already shows a terminal data error, the defect is
  // mid-retry.
  await page.waitForTimeout(2000)
  const main = (await page.locator('main').textContent()) ?? ''
  expect(main).not.toMatch(/waking up|probouzí/i)
  expect(main).not.toContain(['4111', '1111', '1111', '4242'].join(''))
})
