import { expect, test } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'
import { signInAsOperator } from './helpers/auth'

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

for (const scenario of [
  { status: 401, title: 'Session expired' },
  { status: 404, title: 'Agent-service (MCP) is not deployed in this environment' },
  { status: 502, title: 'Agent-service (MCP) is not responding' },
  { status: 500, title: 'Failed to load: MCP tools' },
] as const) {
  test(`explains an MCP ${scenario.status} without leaking the upstream response`, async ({ page }) => {
    await page.route('**/api/agent/mcp', route => route.fulfill({
      status: scenario.status,
      contentType: 'application/json',
      body: JSON.stringify({ error: { code: 'PRIVATE_UPSTREAM_DETAIL', message: 'internal-host:8109 failed' } }),
    }))

    await page.goto('/system/agent')

    await expect(page.getByText(scenario.title, { exact: true })).toBeVisible()
    await expect(page.getByRole('button', { name: 'Try again' })).toBeVisible()
    await expect(page.getByText(/PRIVATE_UPSTREAM_DETAIL|internal-host/)).toHaveCount(0)
  })
}

test('recovers in place when retry reaches the MCP server', async ({ page }) => {
  let requests = 0
  await page.route('**/api/agent/mcp', async route => {
    requests += 1
    if (requests === 1) {
      await route.fulfill({ status: 502, contentType: 'application/json', body: JSON.stringify({ error: 'unreachable' }) })
      return
    }
    const request = route.request().postDataJSON() as { method: string }
    const result = request.method === 'initialize'
      ? { serverInfo: { name: 'openbank-agent-service', version: '1.0.0' }, protocolVersion: '2024-11-05' }
      : { tools: [] }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ jsonrpc: '2.0', id: 1, result }) })
  })
  await page.route('**/api/agent/chat', route => route.fulfill({ status: 200, contentType: 'application/json', body: '{}' }))

  await page.goto('/system/agent')
  await expect(page.getByText('Agent-service (MCP) is not responding', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Try again' }).click()

  await expect(page.getByText('openbank-agent-service', { exact: true })).toBeVisible()
  await expect(page.getByText('0 tools available', { exact: true })).toBeVisible()
})

for (const theme of ['light', 'dark'] as const) {
  test(`keeps the explained failure accessible in ${theme} mode`, async ({ page }) => {
    await page.route('**/api/agent/mcp', route => route.fulfill({
      status: 502,
      contentType: 'application/json',
      body: JSON.stringify({ error: 'unreachable' }),
    }))
    await page.goto('/system/agent')
    await page.locator('html').evaluate((element, selectedTheme) => {
      element.classList.toggle('dark', selectedTheme === 'dark')
    }, theme)
    await expect(page.getByText('Agent-service (MCP) is not responding', { exact: true })).toBeVisible()
    await page.waitForTimeout(450)

    const scan = await new AxeBuilder({ page })
      .include('main')
      .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
      .analyze()
    expect(scan.violations).toEqual([])
  })
}
