// SPDX-License-Identifier: Apache-2.0
// A credential-free browser synthetic. It proves the public Admin UI reaches its SSO boundary;
// it deliberately does not authenticate or claim an authorised operator journey.
import { chromium } from 'playwright'
import { mkdir, writeFile } from 'node:fs/promises'

const target = process.env.ADMIN_UI_SYNTHETIC_URL ?? 'https://admin.open-bank.tech/system/tests'
const report = process.env.PLAYWRIGHT_JUNIT_OUTPUT_FILE ?? 'build/test-results/e2e/admin-login-synthetic.xml'
const started = Date.now()
let failure = null
let browser
try {
  browser = await chromium.launch({ headless: true })
  const page = await browser.newPage()
  const response = await page.goto(target, { waitUntil: 'domcontentloaded', timeout: 20_000 })
  if (!response || response.status() >= 500) throw new Error(`Admin UI returned ${response?.status() ?? 'no response'}`)
  const signIn = page.getByRole('button', { name: 'Continue with Keycloak SSO' })
  if (!await signIn.isVisible({ timeout: 10_000 })) throw new Error('SSO boundary was not rendered')
} catch (error) {
  failure = error instanceof Error ? error.message : 'unknown browser synthetic failure'
} finally {
  await browser?.close()
}
await mkdir(report.slice(0, report.lastIndexOf('/')), { recursive: true })
const seconds = ((Date.now() - started) / 1000).toFixed(3)
const escaped = failure?.replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&apos;' })[char])
await writeFile(report, `<testsuites><testsuite name="admin-login-synthetic" tests="1" failures="${failure ? 1 : 0}" errors="0" skipped="0" time="${seconds}"><testcase classname="admin-login-synthetic" name="renders SSO boundary" time="${seconds}">${failure ? `<failure message="${escaped}"/>` : ''}</testcase></testsuite></testsuites>\n`)
if (failure) throw new Error(failure)
