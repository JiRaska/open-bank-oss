// SPDX-License-Identifier: Apache-2.0

import { expect, test } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const SANCTIONS_LIST = {
  id: 'eu-consolidated',
  listType: 'EU',
  displayName: 'EU Consolidated Financial Sanctions List',
  sourceUrl: 'https://example.test/eu-sanctions',
  enabled: true,
  lastUpdatedAt: '2026-08-31T12:00:00Z',
  lastEntryCount: 1248,
  cronHour: 3,
  cronMinute: 0,
  cronDays: 'MON,TUE,WED,THU,FRI,SAT,SUN',
}

const OTHER_SANCTIONS_LIST = {
  ...SANCTIONS_LIST,
  id: 'ofac-sdn',
  listType: 'OFAC_SDN',
  displayName: 'OFAC Specially Designated Nationals',
}

test.beforeEach(async ({ context, baseURL, page }) => {
  await signInAsOperator(context, baseURL!)
  await page.route('**/api/auth/session', route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({
      user: {
        name: 'E2E Operator',
        email: 'e2e-operator@openbank.test',
        roles: ['ROLE_ADMIN', 'ROLE_OPERATOR', 'ROLE_AUDITOR', 'ROLE_COMPLIANCE', 'ROLE_PAYMENTS'],
        accessToken: 'e2e-fake-access-token',
      },
      expires: '2099-01-01T00:00:00.000Z',
    }),
  }))
})

test('reviews the real disable impact, suppresses duplicate PUTs, and recovers from an unconfirmed response', async ({ page }) => {
  let list = { ...SANCTIONS_LIST }
  let putRequests = 0

  await page.route('**/api/sanctions/checks', route =>
    route.fulfill({ contentType: 'application/json', body: '[]' }),
  )
  await page.route('**/api/sanctions/approvals', route =>
    route.fulfill({ contentType: 'application/json', body: '[]' }),
  )
  await page.route('**/api/sanctions/lists', route =>
    route.fulfill({ contentType: 'application/json', body: JSON.stringify([list]) }),
  )
  await page.route(`**/api/sanctions/lists/${SANCTIONS_LIST.id}`, async route => {
    putRequests += 1
    expect(route.request().method()).toBe('PUT')
    expect(await route.request().postDataJSON()).toEqual({ enabled: false })
    if (putRequests === 1) {
      await route.fulfill({ status: 503, contentType: 'application/json', body: '{"error":"upstream_error"}' })
      return
    }
    list = { ...list, enabled: false }
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify(list) })
  })

  await page.goto('/sanctions')
  await page.getByRole('button', { name: /Správa listů|List Management/ }).click()
  await page.getByRole('button', { name: `Review pausing automatic updates for ${SANCTIONS_LIST.displayName}` }).click()
  expect(putRequests).toBe(0)

  const dialog = page.getByRole('alertdialog')
  await expect(dialog).toContainText('API screenings outside this console are not excluded by this setting')
  await expect(dialog).toContainText('does not enter the four-eyes approval queue')

  const confirm = dialog.getByRole('button', { name: 'Pause automatic updates' })
  await confirm.evaluate(button => {
    const control = button as HTMLButtonElement
    control.click()
    control.click()
  })
  await expect(dialog.getByRole('alert')).toContainText('The service did not confirm the change')
  await expect(dialog.getByRole('button', { name: 'Close and refresh status' })).toBeVisible()
  expect(putRequests).toBe(1)

  await confirm.click()
  await expect(dialog).toBeHidden()
  expect(putRequests).toBe(2)

  const resumedToggle = page.getByRole('button', { name: `Review resuming automatic updates for ${SANCTIONS_LIST.displayName}` })
  await expect(resumedToggle).toBeVisible()
  await expect(resumedToggle).toBeFocused()
  await page.getByRole('button', { name: /Manuální vyhledávání|Manual Search/ }).click()
  const scope = page.getByRole('checkbox', { name: SANCTIONS_LIST.displayName })
  await expect(scope).toBeDisabled()
  await expect(scope).not.toBeChecked()
})

for (const status of [401, 403]) {
  test(`reconciles status after a ${status} authorization rejection instead of promising the previous state`, async ({ page }) => {
    let listReads = 0

    await page.route('**/api/sanctions/checks', route =>
      route.fulfill({ contentType: 'application/json', body: '[]' }),
    )
    await page.route('**/api/sanctions/approvals', route =>
      route.fulfill({ contentType: 'application/json', body: '[]' }),
    )
    await page.route('**/api/sanctions/lists', route => {
      listReads += 1
      return route.fulfill({ contentType: 'application/json', body: JSON.stringify([SANCTIONS_LIST]) })
    })
    await page.route(`**/api/sanctions/lists/${SANCTIONS_LIST.id}`, route =>
      route.fulfill({ status, contentType: 'application/json', body: '{"error":"forbidden"}' }),
    )

    await page.goto('/sanctions')
    await page.getByRole('button', { name: /Správa listů|List Management/ }).click()
    const toggle = page.getByRole('button', { name: `Review pausing automatic updates for ${SANCTIONS_LIST.displayName}` })
    await toggle.click()
    const readsBeforeReconcile = listReads
    const dialog = page.getByRole('alertdialog')
    await dialog.getByRole('button', { name: 'Pause automatic updates' }).click()
    await expect(dialog.getByRole('alert')).toContainText('This session is not authorised to make the change')
    await dialog.getByRole('button', { name: 'Close and refresh status' }).click()

    await expect(dialog).toBeHidden()
    await expect.poll(() => listReads).toBeGreaterThan(readsBeforeReconcile)
    await expect(toggle).toBeFocused()
  })
}

test('prunes an ambiguously applied disable from the reconciled manual-screening scope', async ({ page }) => {
  let list = { ...SANCTIONS_LIST }

  await page.route('**/api/sanctions/checks', route =>
    route.fulfill({ contentType: 'application/json', body: '[]' }),
  )
  await page.route('**/api/sanctions/approvals', route =>
    route.fulfill({ contentType: 'application/json', body: '[]' }),
  )
  await page.route('**/api/sanctions/lists', route =>
    route.fulfill({ contentType: 'application/json', body: JSON.stringify([list, OTHER_SANCTIONS_LIST]) }),
  )
  await page.route(`**/api/sanctions/lists/${SANCTIONS_LIST.id}`, route => {
    list = { ...list, enabled: false }
    return route.abort('failed')
  })
  await page.route('**/api/sanctions/screen', route =>
    route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify({
        id: 'manual-check-1',
        name: 'Acme Ltd',
        entityType: 'INDIVIDUAL',
        status: 'CLEAR',
        overallScore: 0,
        checkedLists: [OTHER_SANCTIONS_LIST.listType],
        matches: [],
        checkedAt: '2026-09-01T12:00:00Z',
      }),
    }),
  )

  await page.goto('/sanctions')
  await page.getByRole('button', { name: /Správa listů|List Management/ }).click()
  await page.getByRole('button', { name: `Review pausing automatic updates for ${SANCTIONS_LIST.displayName}` }).click()
  const dialog = page.getByRole('alertdialog')
  await dialog.getByRole('button', { name: 'Pause automatic updates' }).click()
  await expect(dialog.getByRole('alert')).toContainText('The service did not confirm the change')
  await dialog.getByRole('button', { name: 'Close and refresh status' }).click()
  await expect(dialog).toBeHidden()

  await page.getByRole('button', { name: /Manuální vyhledávání|Manual Search/ }).click()
  const disabledScope = page.getByRole('checkbox', { name: SANCTIONS_LIST.displayName })
  await expect(disabledScope).toBeDisabled()
  await expect(disabledScope).not.toBeChecked()
  await expect(page.getByRole('checkbox', { name: OTHER_SANCTIONS_LIST.displayName })).toBeChecked()

  await page.getByLabel(/Jméno \/ Název|Name \/ Entity/).fill('Acme Ltd')
  const screenRequestPromise = page.waitForRequest('**/api/sanctions/screen')
  await page.getByRole('button', { name: /Spustit prověření sankcí|Run sanctions screening/ }).click()
  const screenPayload = await (await screenRequestPromise).postDataJSON()
  expect(screenPayload.listTypes).toEqual([OTHER_SANCTIONS_LIST.listType])
})
