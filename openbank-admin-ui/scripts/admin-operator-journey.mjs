// SPDX-License-Identifier: Apache-2.0
// The authenticated half of the admin browser journey (journeys.yaml: admin-operator-access).
// A least-privilege bank-owned canary operator completes the deployed sandbox SSO flow in a real
// browser and reaches the Test Intelligence workspace; the same session must be REFUSED on an
// admin-only API. It deliberately does not stop at "the public login page returned HTML".
//
// Fail-closed by contract: until the owner provisions the canary identity
// (openbank-infra/scripts/provision-synthetic-operator.sh) and the workflow secrets
// (SYNTHETIC_OPERATOR_USERNAME / SYNTHETIC_OPERATOR_PASSWORD), this script logs "credential
// absent" and fails every check — red, never green. A green run requires a real authenticated
// operator session.
//
// Never log the password, the session cookie, tokens or response bodies: only status codes,
// pathnames and element presence, so retained CI logs stay secret-free.
import { chromium, firefox } from 'playwright'
import { mkdir, writeFile } from 'node:fs/promises'

const target = process.env.ADMIN_OPERATOR_TARGET ?? 'https://admin.open-bank.tech/system/tests'
const report = process.env.PLAYWRIGHT_JUNIT_OUTPUT_FILE ?? 'build/test-results/e2e/admin-operator-journey.xml'
const vitalsReport = process.env.OPENBANK_BROWSER_VITALS_OUTPUT ?? 'build/test-intelligence/browser-vitals.json'
const engine = process.env.OPENBANK_BROWSER ?? 'chromium'
const journey = process.env.OPENBANK_SYNTHETIC_JOURNEY ?? 'admin-operator-access'
const operatorUsername = process.env.SYNTHETIC_OPERATOR_USERNAME ?? ''
const operatorPassword = process.env.SYNTHETIC_OPERATOR_PASSWORD ?? ''
const expectedBuildSha = process.env.ADMIN_UI_EXPECTED_BUILD_SHA?.trim().toLowerCase() || null
const launchers = { chromium, firefox }
if (!Object.hasOwn(launchers, engine)) throw new Error(`Unsupported browser engine: ${engine}`)
if (!/^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$/.test(journey)) throw new Error(`Invalid synthetic journey: ${journey}`)
// Authenticated-operator budgets are tighter than the public probe's: an operator console that
// paints after seconds of stall is not a usable workspace, and this journey is the evidence.
const FCP_BUDGET_MS = 7_000
const CLS_BUDGET = 0.1
const SSO_TIMEOUT_MS = 30_000
const started = Date.now()
let gateFailure = null
let ssoFailure = null
let workspaceFailure = null
let boundaryFailure = null
let fcpFailure = null
let clsFailure = null
let attestationFailure = null
let measuredVitals = null
let buildAttestation = null

async function failAll(message) {
  console.error(message)
  gateFailure = ssoFailure = workspaceFailure = boundaryFailure = fcpFailure = clsFailure = message
}

if (!operatorUsername || !operatorPassword) {
  await failAll('admin-operator-access credential absent: SYNTHETIC_OPERATOR_USERNAME/SYNTHETIC_OPERATOR_PASSWORD are not set (canary identity not provisioned, see openbank-infra/scripts/provision-synthetic-operator.sh); refusing to probe unauthenticated')
} else {
  const origin = new URL(target).origin
  let browser
  try {
    browser = await launchers[engine].launch({ headless: true })
    const page = await browser.newPage()
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

    // 1. The auth gate: an unauthenticated operator must be bounced to /auth/login with the
    //    callback preserved. A 200 here is an auth-bypass regression, the exact failure mode
    //    this journey exists to catch.
    {
      const response = await page.goto(target, { waitUntil: 'domcontentloaded', timeout: 20_000 })
      const landed = new URL(page.url())
      if (!response || response.status() >= 500) {
        gateFailure = `Admin UI returned ${response?.status() ?? 'no response'}`
      } else if (landed.origin !== origin || landed.pathname !== '/auth/login' || landed.searchParams.get('callbackUrl') !== new URL(target).pathname) {
        gateFailure = `auth gate failed: HTTP ${response?.status()}, landed on ${landed.pathname}${landed.search}`
      }
    }

    // 2. The deployed SSO flow: Keycloak realm 'openbank' issues a session for the canary
    //    operator and the Admin UI callback completes. This is the browser half the
    //    credential-free sibling (admin-ui-sso-boundary) deliberately does not claim.
    if (!gateFailure) {
      const signIn = page.getByRole('button', { name: 'Continue with Keycloak SSO' })
      if (!await signIn.isVisible({ timeout: 10_000 })) {
        ssoFailure = 'SSO boundary was not rendered on the login page'
      } else {
        await Promise.all([
          page.waitForURL(/\/realms\/openbank\//, { timeout: SSO_TIMEOUT_MS }),
          signIn.click(),
        ])
        // Keycloak's default login template (the openbank theme overrides CSS/messages only,
        // not login.ftl), so the stock field ids apply.
        await page.locator('#username').fill(operatorUsername)
        await page.locator('#password').fill(operatorPassword)
        const submit = page.locator('#kc-login, input[type="submit"]').first()
        await Promise.all([
          page.waitForURL(url => url.origin === origin && url.pathname === new URL(target).pathname, { timeout: SSO_TIMEOUT_MS }),
          submit.click(),
        ])
      }
    }

    // 3. The Test Intelligence workspace serves THIS operator: the page renders and its data
    //    plane answers with a well-formed report. Presence of the SSO session alone is not
    //    workspace access.
    if (!gateFailure && !ssoFailure) {
      const workspace = await page.goto(target, { waitUntil: 'domcontentloaded', timeout: 20_000 })
      const landed = new URL(page.url())
      if (!workspace || workspace.status() >= 400 || landed.pathname !== new URL(target).pathname) {
        workspaceFailure = `Test Intelligence workspace did not serve the operator: HTTP ${workspace?.status() ?? 'no response'}, landed on ${landed.pathname}`
      } else {
        const api = await page.request.get(`${origin}/api/test-intelligence`, { timeout: 15_000 })
        const body = api.ok() ? await api.json().catch(() => null) : null
        if (api.status() !== 200 || !body || body.schemaVersion !== 1) {
          workspaceFailure = `Test Intelligence API did not serve the operator: status=${api.status()}`
        }
      }
      // 4. The role boundary: an admin-only BFF route must REFUSE this operator session. A
      //    canary holding ROLE_ADMIN, or a BFF that ignores roles, turns this green and is the
      //    boundary regression the falsification names.
      if (!workspaceFailure) {
        const adminOnly = await page.request.get(`${origin}/api/context/assignments/pending`, { timeout: 15_000 })
        if (adminOnly.status() !== 403) {
          boundaryFailure = `admin-only API answered ${adminOnly.status()} to the operator session — the role boundary is gone`
        }
      }
      if (expectedBuildSha) {
        const attestationResponse = await page.request.get(`${origin}/.well-known/openbank-build-attestation`, { timeout: 10_000 })
        const attestation = attestationResponse.ok() ? await attestationResponse.json() : null
        const observedBuildSha = typeof attestation?.gitSha === 'string' ? attestation.gitSha.toLowerCase() : null
        buildAttestation = { requestedSha: expectedBuildSha, observedSha: observedBuildSha }
        if (!observedBuildSha || !(expectedBuildSha.startsWith(observedBuildSha) || observedBuildSha.startsWith(expectedBuildSha))) {
          attestationFailure = `deployed build attestation ${observedBuildSha ?? 'unavailable'} does not match requested ${expectedBuildSha}`
        }
      }
      await page.waitForTimeout(1_000)
      const vitals = await page.evaluate(() => {
        const firstContentfulPaint = performance.getEntriesByName('first-contentful-paint')[0]?.startTime
        return { firstContentfulPaint, ...(window.__openbankSyntheticVitals ?? {}) }
      })
      if (Number.isFinite(vitals.firstContentfulPaint) && vitals.clsAvailable && Number.isFinite(vitals.cls)) {
        measuredVitals = { fcpMs: Math.round(vitals.firstContentfulPaint), cls: Math.round(vitals.cls * 1000) / 1000 }
      }
      if (!Number.isFinite(vitals.firstContentfulPaint) || vitals.firstContentfulPaint > FCP_BUDGET_MS) {
        fcpFailure = `workspace FCP ${Number.isFinite(vitals.firstContentfulPaint) ? Math.round(vitals.firstContentfulPaint) : 'unavailable'}ms exceeds ${FCP_BUDGET_MS}ms budget`
      }
      if (!vitals.clsAvailable || !Number.isFinite(vitals.cls) || vitals.cls > CLS_BUDGET) {
        clsFailure = `workspace CLS ${Number.isFinite(vitals.cls) ? vitals.cls.toFixed(3) : 'unavailable'} exceeds ${CLS_BUDGET} budget`
      }
    }
  } catch (error) {
    const message = error instanceof Error ? error.message : 'unknown operator journey failure'
    gateFailure ??= message
    ssoFailure ??= message
    workspaceFailure ??= message
    boundaryFailure ??= message
    fcpFailure ??= message
    clsFailure ??= message
    attestationFailure ??= message
  } finally {
    await browser?.close()
  }
}

await mkdir(report.slice(0, report.lastIndexOf('/')), { recursive: true })
await mkdir(vitalsReport.slice(0, vitalsReport.lastIndexOf('/')), { recursive: true })
const seconds = ((Date.now() - started) / 1000).toFixed(3)
const escape = value => value.replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&apos;' })[char])
const testcase = (name, failure) => `<testcase classname="admin-operator-journey" name="${name}" time="${seconds}">${failure ? `<failure message="${escape(failure)}"/>` : ''}</testcase>`
const checks = [
  ['auth gate redirects the unauthenticated operator to SSO', gateFailure],
  ['Keycloak SSO authenticates the canary operator', ssoFailure],
  ['Test Intelligence workspace serves the operator', workspaceFailure],
  ['admin-only API refuses the operator session', boundaryFailure],
  ['workspace FCP is within operator Web Vitals budget', fcpFailure],
  ['workspace CLS is within operator Web Vitals budget', clsFailure],
  ...(expectedBuildSha ? [['deployed build matches requested source', attestationFailure]] : []),
]
const failureCount = checks.filter(([, failure]) => failure).length
await writeFile(report, `<testsuites><testsuite name="admin-operator-journey" tests="${checks.length}" failures="${failureCount}" errors="0" skipped="0" time="${seconds}">${checks.map(([name, failure]) => testcase(name, failure)).join('')}</testsuite></testsuites>\n`)
await writeFile(vitalsReport, `${JSON.stringify({ schemaVersion: 1, journey, browser: engine, ...(measuredVitals ? { metrics: measuredVitals } : {}), ...(buildAttestation ? { buildAttestation } : {}) })}\n`)
if (failureCount) throw new Error(checks.map(([, failure]) => failure).filter(Boolean).join('; '))
