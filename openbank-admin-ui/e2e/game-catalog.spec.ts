// SPDX-License-Identifier: Apache-2.0
import { expect, test } from '@playwright/test'
import { signInWithRoles } from './helpers/auth'

const initial = {
  revision: 0,
  levels: ['embankment', 'rooftops', 'charles_bridge'].map((id, order) => ({
    id, theme: id, order, titleCs: ['Náplavka', 'Staroměstské střechy', 'Karlův most'][order],
    titleEn: ['Embankment', 'Old Town rooftops', 'Charles Bridge'][order],
    state: order === 0 ? 'LIVE' : 'BETA', startsAt: null, endsAt: null,
    requirement: { kind: order === 0 ? 'NONE' : 'PREVIOUS_LEVEL', source: null, count: 1, hintCs: 'Dokonči předchozí kolo', hintEn: 'Complete previous chapter' },
  })),
}

for (const theme of ['light', 'dark']) {
  test(`admin saves catalogue and handles a revision conflict in ${theme}`, async ({ context, page, baseURL }, info) => {
    await signInWithRoles(context, baseURL!, ['ROLE_ADMIN'])
    await context.addInitScript(value => localStorage.setItem('ob-admin-theme', value), theme)
    await page.route('**/api/auth/session', route => route.fulfill({ json: { user: { name: 'E2E Admin', roles: ['ROLE_ADMIN'], accessToken: 'synthetic' }, expires: '2099-01-01T00:00:00Z' } }))
    let writes = 0
    await page.route('**/api/game/catalog', async route => {
      if (route.request().method() === 'GET') { await route.fulfill({ json: initial }); return }
      writes++
      const body = route.request().postDataJSON()
      expect(body.revision).toBe(writes === 1 ? 0 : 1)
      if (writes > 1) { await route.fulfill({ status: 409, json: { error: 'catalogue_changed' } }); return }
      expect(body.levels[2].state).toBe('LIVE')
      await route.fulfill({ json: { ...body, revision: 1 } })
    })
    await page.goto('/loyalty/game')
    await expect(page.getByRole('heading', { name: /Lví stezka|Lion Quest/ })).toBeVisible()
    await expect(page.getByRole('group')).toHaveCount(3)
    await page.getByLabel(/Dostupnost|Availability/).nth(2).selectOption('LIVE')
    await page.getByRole('button', { name: /Uložit změny|Save changes/ }).click()
    await expect(page.getByRole('status')).toContainText(/uložené|saved/)
    await page.screenshot({ path: info.outputPath(`game-catalog-${theme}-bottom.png`) })
    await page.getByRole('heading', { name: /Lví stezka|Lion Quest/ }).scrollIntoViewIfNeeded()
    await page.screenshot({ path: info.outputPath(`game-catalog-${theme}.png`) })
    await page.getByLabel(/Český název|Czech title/).nth(2).fill('Karlův most za úsvitu')
    await page.getByRole('button', { name: /Uložit změny|Save changes/ }).click()
    await expect(page.getByRole('alert').filter({ hasText: /někdo změnil|Someone changed/ })).toContainText(/někdo změnil|Someone changed/)
    await expect(page.getByLabel(/Český název|Czech title/).nth(2)).toHaveValue('Karlův most za úsvitu')
  })
}

test('operator sees the real catalogue with disabled controls and no save action', async ({ context, page, baseURL }) => {
  await signInWithRoles(context, baseURL!, ['ROLE_OPERATOR'])
  await page.route('**/api/auth/session', route => route.fulfill({ json: { user: { name: 'E2E Operator', roles: ['ROLE_OPERATOR'], accessToken: 'synthetic' }, expires: '2099-01-01T00:00:00Z' } }))
  await page.route('**/api/game/catalog', route => route.fulfill({ json: initial }))
  await page.goto('/loyalty/game')
  await expect(page.getByRole('group')).toHaveCount(3)
  await expect(page.getByLabel(/Dostupnost|Availability/).first()).toBeDisabled()
  await expect(page.getByRole('button', { name: /Uložit změny|Save changes/ })).toHaveCount(0)
})
