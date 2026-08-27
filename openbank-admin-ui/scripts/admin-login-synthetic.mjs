// SPDX-License-Identifier: Apache-2.0
// A credential-free browser synthetic. It proves the public Admin UI reaches its SSO boundary;
// it deliberately does not authenticate or claim an authorised operator journey.
import { chromium } from 'playwright'
import { mkdir, writeFile } from 'node:fs/promises'

const target = process.env.ADMIN_UI_SYNTHETIC_URL ?? 'https://admin.open-bank.tech/system/tests'
const report = process.env.PLAYWRIGHT_JUNIT_OUTPUT_FILE ?? 'build/test-results/e2e/admin-login-synthetic.xml'
// A deliberately forgiving public-edge budget. This is a synthetic availability guard, not an
// authenticated operator-flow SLO or a substitute for RUM Web Vitals. It prevents a page that
// eventually renders after a multi-second upstream stall from reading as simply "healthy".
const RESPONSE_START_BUDGET_MS = 5_000
const started = Date.now()
let boundaryFailure = null
let latencyFailure = null
let browser
try {
  browser = await chromium.launch({ headless: true })
  const page = await browser.newPage()
  const response = await page.goto(target, { waitUntil: 'domcontentloaded', timeout: 20_000 })
  if (!response || response.status() >= 500) boundaryFailure = `Admin UI returned ${response?.status() ?? 'no response'}`
  const signIn = page.getByRole('button', { name: 'Continue with Keycloak SSO' })
  if (!await signIn.isVisible({ timeout: 10_000 })) boundaryFailure ??= 'SSO boundary was not rendered'
  const responseStart = await page.evaluate(() => performance.getEntriesByType('navigation')[0]?.responseStart)
  if (!Number.isFinite(responseStart) || responseStart > RESPONSE_START_BUDGET_MS) {
    latencyFailure = `SSO boundary responseStart ${Number.isFinite(responseStart) ? Math.round(responseStart) : 'unavailable'}ms exceeds ${RESPONSE_START_BUDGET_MS}ms budget`
  }
} catch (error) {
  boundaryFailure ??= error instanceof Error ? error.message : 'unknown browser synthetic failure'
  latencyFailure ??= boundaryFailure
} finally {
  await browser?.close()
}
await mkdir(report.slice(0, report.lastIndexOf('/')), { recursive: true })
const seconds = ((Date.now() - started) / 1000).toFixed(3)
const escape = value => value.replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&apos;' })[char])
const boundary = boundaryFailure && `<failure message="${escape(boundaryFailure)}"/>`
const latency = latencyFailure && `<failure message="${escape(latencyFailure)}"/>`
const failureCount = Number(Boolean(boundaryFailure)) + Number(Boolean(latencyFailure))
await writeFile(report, `<testsuites><testsuite name="admin-login-synthetic" tests="2" failures="${failureCount}" errors="0" skipped="0" time="${seconds}"><testcase classname="admin-login-synthetic" name="renders SSO boundary" time="${seconds}">${boundary ?? ''}</testcase><testcase classname="admin-login-synthetic" name="SSO boundary responds within public latency budget" time="${seconds}">${latency ?? ''}</testcase></testsuite></testsuites>\n`)
if (failureCount) throw new Error([boundaryFailure, latencyFailure].filter(Boolean).join('; '))
