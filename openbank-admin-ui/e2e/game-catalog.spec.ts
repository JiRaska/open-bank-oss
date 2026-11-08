// SPDX-License-Identifier: Apache-2.0
import { expect, test } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'
import { signInWithRoles } from './helpers/auth'
const initial = {
  revision: 0,
  levels: ['embankment', 'rooftops', 'charles_bridge'].map((id, order) => ({
    id,
    theme: id,
    order,
    titleCs: ['Náplavka', 'Staroměstské střechy', 'Karlův most'][order],
    titleEn: ['Embankment', 'Old Town rooftops', 'Charles Bridge'][order],
    state: order === 0 ? 'LIVE' : 'BETA',
    startsAt: null,
    endsAt: null,
    requirement: { kind: order === 0 ? 'NONE' : 'PREVIOUS_LEVEL', source: null, count: 1, hintCs: '', hintEn: '' },
  })),
}
for (const theme of ['light', 'dark'])
  test(`visual studio edits course and retains conflict edits in ${theme}`, async ({
    context,
    page,
    baseURL,
  }, info) => {
    await signInWithRoles(context, baseURL!, ['ROLE_ADMIN'])
    await context.addInitScript((value) => localStorage.setItem('ob-admin-theme', value), theme)
    await context.addCookies([{ name: 'openbank-admin-lang', value: 'cs', url: baseURL! }])
    await page.route('**/api/auth/session', (r) =>
      r.fulfill({ json: { user: { roles: ['ROLE_ADMIN'] }, expires: '2099-01-01T00:00:00Z' } }),
    )
    let writes = 0
    await page.route('**/api/game/catalog', async (r) => {
      if (r.request().method() === 'GET') {
        await r.fulfill({ json: initial })
        return
      }
      writes++
      const body = r.request().postDataJSON()
      if (writes > 1) {
        await r.fulfill({ status: 409, json: { error: 'catalogue_changed' } })
        return
      }
      expect(body.levels[2].content.levelEnd).toBe(14000)
      expect(body.levels[2].content.id).toBe('charles_bridge')
      await r.fulfill({ json: { ...body, revision: 1 } })
    })
    await page.setViewportSize({ width: 1600, height: 1100 })
    await page.goto('/loyalty/game')
    const open = page.getByRole('button', { name: /Otevřít Karlův most|Open Charles Bridge/, exact: true })
    await expect(open).toBeVisible()
    await expect(page.getByRole('button', { name: /^Náplavka|^Embankment/ })).toBeEnabled()
    await page
      .locator('section img')
      .evaluateAll((imgs) => Promise.all(imgs.map((img) => (img as HTMLImageElement).decode().catch(() => {}))))
    await page.screenshot({ path: info.outputPath(`game-studio-catalog-${theme}.png`), fullPage: true })
    await open.click()
    const audit = await new AxeBuilder({page}).include('section[aria-labelledby="game-catalog-title"]').withTags(['wcag2a','wcag2aa']).analyze()
    expect(audit.violations).toEqual([])
    const length = page.getByLabel(/Délka trati|Course length/)
    await expect(length).toHaveValue('13500')
    await length.fill('14000')
    await page
      .locator('section img')
      .evaluateAll((imgs) => Promise.all(imgs.map((img) => (img as HTMLImageElement).decode().catch(() => {}))))
    await page.screenshot({ path: info.outputPath(`game-studio-editor-${theme}.png`), fullPage: true })
    await page.getByRole('button', { name: /Uložit změny|Save changes/ }).click()
    await expect(page.getByRole('status')).toContainText(/uložené|saved/)
    await page.getByLabel(/Český název|Czech title/).fill('Karlův most za úsvitu')
    await page.getByRole('button', { name: /Uložit změny|Save changes/ }).click()
    await expect(page.getByRole('alert').filter({ hasText: /Someone changed|někdo změnil/ })).toContainText(
      /někdo změnil|Someone changed/,
    )
    await expect(page.getByLabel(/Český název|Czech title/)).toHaveValue('Karlův most za úsvitu')
  })
test('create draft from real course and place coin visually', async ({ context, page, baseURL }) => {
  await signInWithRoles(context, baseURL!, ['ROLE_ADMIN'])
  await page.route('**/api/auth/session', (r) =>
    r.fulfill({ json: { user: { roles: ['ROLE_ADMIN'] }, expires: '2099-01-01T00:00:00Z' } }),
  )
  await page.route('**/api/game/catalog', async (r) => {
    if (r.request().method() === 'GET') {
      await r.fulfill({ json: initial })
      return
    }
    const body = r.request().postDataJSON()
    expect(body.levels).toHaveLength(4)
    expect(body.levels[3].state).toBe('OFF')
    expect(body.levels[3].order).toBe(3)
    await r.fulfill({ json: { ...body, revision: 1 } })
  })
  await page.goto('/loyalty/game')
  await page.getByRole('button', { name: /^Náplavka|^Embankment/ }).click()
  await expect(page.getByLabel(/Délka trati|Course length/)).toHaveValue('5700')
  await page.getByRole('button', { name: /^Mince$|^Coin$/ }).click()
  await page.getByRole('button', { name: /Umístit vybraný|Place selected/ }).click({ position: { x: 200, y: 160 } })
  await expect(page.getByRole('group', { name: /Vybraný prvek|Selected component/ })).toBeVisible()
  await page.getByRole('button', { name: /Uložit změny|Save changes/ }).click()
  await expect(page.getByRole('status')).toContainText(/uložené|saved/)
})
test('operator previews but cannot edit', async ({ context, page, baseURL }) => {
  await signInWithRoles(context, baseURL!, ['ROLE_OPERATOR'])
  await page.route('**/api/auth/session', (r) =>
    r.fulfill({ json: { user: { roles: ['ROLE_OPERATOR'] }, expires: '2099-01-01T00:00:00Z' } }),
  )
  await page.route('**/api/game/catalog', (r) => r.fulfill({ json: initial }))
  await page.goto('/loyalty/game')
  await page.getByRole('button', { name: /Otevřít Karlův most|Open Charles Bridge/, exact: true }).click()
  await expect(page.getByLabel(/Dostupnost|Availability/)).toBeDisabled()
  await expect(page.getByRole('button', { name: /Uložit změny|Save changes/ })).toHaveCount(0)
})

test('marketing can author drafts but cannot publish or edit live content', async ({ context, page, baseURL }) => {
  await signInWithRoles(context, baseURL!, ['ROLE_MARKETING'])
  await page.route('**/api/auth/session', (r) =>
    r.fulfill({ json: { user: { roles: ['ROLE_MARKETING'] }, expires: '2099-01-01T00:00:00Z' } }),
  )
  await page.route('**/api/game/catalog', (r) => r.fulfill({ json: initial }))
  await page.goto('/loyalty/game')
  await page.getByRole('button', { name: /Otevřít Embankment|Otevřít Náplavka|Open Embankment/, exact: true }).click()
  await expect(page.getByLabel(/Dostupnost|Availability/)).toBeDisabled()
  await page.getByRole('button', { name: /Všechna kola|All chapters/ }).click()
  await page.getByRole('button', { name: /Otevřít Karlův most|Open Charles Bridge/, exact: true }).click()
  await expect(page.getByLabel(/Dostupnost|Availability/)).toBeEnabled()
  await expect(page.locator('option[value="LIVE"]')).toHaveJSProperty('disabled', true)
  await page.getByLabel(/Dostupnost|Availability/).press('End')
  await expect(page.getByLabel(/Dostupnost|Availability/)).toHaveValue('BETA')
})
