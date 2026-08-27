// SPDX-License-Identifier: Apache-2.0
// A credential-free browser synthetic. It proves the public Admin UI reaches its SSO boundary;
// it deliberately does not authenticate or claim an authorised operator journey.
import { chromium } from 'playwright'
import { mkdir, writeFile } from 'node:fs/promises'

const target = process.env.ADMIN_UI_SYNTHETIC_URL ?? 'https://admin.open-bank.tech/system/tests'
const report = process.env.PLAYWRIGHT_JUNIT_OUTPUT_FILE ?? 'build/test-results/e2e/admin-login-synthetic.xml'
// Deliberately forgiving public-edge budgets. These are synthetic availability guards, not an
// authenticated operator-flow SLO or a substitute for RUM. Together they prevent a page that
// eventually renders after a multi-second upstream or client-side stall from reading as healthy.
const RESPONSE_START_BUDGET_MS = 5_000
const DOM_CONTENT_LOADED_BUDGET_MS = 7_000
const FCP_BUDGET_MS = 7_000
const CLS_BUDGET = 0.1
const started = Date.now()
let boundaryFailure = null
let latencyFailure = null
let renderFailure = null
let fcpFailure = null
let clsFailure = null
let browser
try {
  browser = await chromium.launch({ headless: true })
  const page = await browser.newPage()
  // Install observers before navigation. An unavailable measurement is a failed check, not a
  // numeric zero: zero CLS is valid, while absent FCP/LCP evidence is not.
  await page.addInitScript(() => {
    const vitals = { cls: 0, clsAvailable: false }
    window.__openbankSyntheticVitals = vitals
    try {
      new PerformanceObserver(entries => {
        for (const entry of entries.getEntries()) {
          if (!entry.hadRecentInput) vitals.cls += entry.value
        }
      }).observe({ type: 'layout-shift', buffered: true })
      vitals.clsAvailable = true
    } catch { /* unsupported browser metric stays unavailable */ }
  })
  const response = await page.goto(target, { waitUntil: 'domcontentloaded', timeout: 20_000 })
  if (!response || response.status() >= 500) boundaryFailure = `Admin UI returned ${response?.status() ?? 'no response'}`
  const signIn = page.getByRole('button', { name: 'Continue with Keycloak SSO' })
  if (!await signIn.isVisible({ timeout: 10_000 })) boundaryFailure ??= 'SSO boundary was not rendered'
  const timing = await page.evaluate(() => {
    const navigation = performance.getEntriesByType('navigation')[0]
    return {
      responseStart: navigation?.responseStart,
      domContentLoaded: navigation?.domContentLoadedEventEnd,
    }
  })
  if (!Number.isFinite(timing.responseStart) || timing.responseStart > RESPONSE_START_BUDGET_MS) {
    latencyFailure = `SSO boundary responseStart ${Number.isFinite(timing.responseStart) ? Math.round(timing.responseStart) : 'unavailable'}ms exceeds ${RESPONSE_START_BUDGET_MS}ms budget`
  }
  if (!Number.isFinite(timing.domContentLoaded) || timing.domContentLoaded > DOM_CONTENT_LOADED_BUDGET_MS) {
    renderFailure = `SSO boundary DOMContentLoaded ${Number.isFinite(timing.domContentLoaded) ? Math.round(timing.domContentLoaded) : 'unavailable'}ms exceeds ${DOM_CONTENT_LOADED_BUDGET_MS}ms budget`
  }
  // The public probe does not authenticate or interact with Keycloak. It merely leaves one quiet
  // second for the document's browser-native paint observer to publish its result.
  await page.waitForTimeout(1_000)
  const vitals = await page.evaluate(() => {
    const firstContentfulPaint = performance.getEntriesByName('first-contentful-paint')[0]?.startTime
    return { firstContentfulPaint, ...(window.__openbankSyntheticVitals ?? {}) }
  })
  if (!Number.isFinite(vitals.firstContentfulPaint) || vitals.firstContentfulPaint > FCP_BUDGET_MS) {
    fcpFailure = `SSO boundary FCP ${Number.isFinite(vitals.firstContentfulPaint) ? Math.round(vitals.firstContentfulPaint) : 'unavailable'}ms exceeds ${FCP_BUDGET_MS}ms budget`
  }
  if (!vitals.clsAvailable || !Number.isFinite(vitals.cls) || vitals.cls > CLS_BUDGET) {
    clsFailure = `SSO boundary CLS ${Number.isFinite(vitals.cls) ? vitals.cls.toFixed(3) : 'unavailable'} exceeds ${CLS_BUDGET} budget`
  }
} catch (error) {
  boundaryFailure ??= error instanceof Error ? error.message : 'unknown browser synthetic failure'
  latencyFailure ??= boundaryFailure
  renderFailure ??= boundaryFailure
  fcpFailure ??= boundaryFailure
  clsFailure ??= boundaryFailure
} finally {
  await browser?.close()
}
await mkdir(report.slice(0, report.lastIndexOf('/')), { recursive: true })
const seconds = ((Date.now() - started) / 1000).toFixed(3)
const escape = value => value.replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&apos;' })[char])
const boundary = boundaryFailure && `<failure message="${escape(boundaryFailure)}"/>`
const latency = latencyFailure && `<failure message="${escape(latencyFailure)}"/>`
const render = renderFailure && `<failure message="${escape(renderFailure)}"/>`
const fcp = fcpFailure && `<failure message="${escape(fcpFailure)}"/>`
const cls = clsFailure && `<failure message="${escape(clsFailure)}"/>`
const failures = [boundaryFailure, latencyFailure, renderFailure, fcpFailure, clsFailure]
const failureCount = failures.filter(Boolean).length
await writeFile(report, `<testsuites><testsuite name="admin-login-synthetic" tests="5" failures="${failureCount}" errors="0" skipped="0" time="${seconds}"><testcase classname="admin-login-synthetic" name="renders SSO boundary" time="${seconds}">${boundary ?? ''}</testcase><testcase classname="admin-login-synthetic" name="SSO boundary responds within public latency budget" time="${seconds}">${latency ?? ''}</testcase><testcase classname="admin-login-synthetic" name="SSO boundary DOMContentLoaded within public render budget" time="${seconds}">${render ?? ''}</testcase><testcase classname="admin-login-synthetic" name="SSO boundary FCP is within public Web Vitals budget" time="${seconds}">${fcp ?? ''}</testcase><testcase classname="admin-login-synthetic" name="SSO boundary CLS is within public Web Vitals budget" time="${seconds}">${cls ?? ''}</testcase></testsuite></testsuites>\n`)
if (failureCount) throw new Error(failures.filter(Boolean).join('; '))
