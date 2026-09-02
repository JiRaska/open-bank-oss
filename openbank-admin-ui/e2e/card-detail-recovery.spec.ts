import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const cardId = '11111111-1111-1111-1111-111111111111'
const card = {
  id: cardId,
  partyId: '22222222-2222-2222-2222-222222222222',
  accountId: '33333333-3333-3333-3333-333333333333',
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
