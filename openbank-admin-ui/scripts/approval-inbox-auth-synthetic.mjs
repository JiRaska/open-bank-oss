// SPDX-License-Identifier: Apache-2.0
// Read-only sandbox journey. Run as the child of approval-inbox-fixture.mjs with a
// bank-owned, short-lived Playwright storage state; never store that state in git.
import { chromium } from 'playwright'
import { realpath, stat } from 'node:fs/promises'
import { isAbsolute, relative } from 'node:path'
import { verifyApprovalInboxEvidence, writeApprovalReadEvidence } from './approval-inbox-evidence.mjs'

const required = [
  'OPENBANK_APPROVAL_SYNTHETIC_URL', 'OPENBANK_APPROVAL_STORAGE_STATE',
  'OPENBANK_APPROVAL_FIXTURE_ID', 'OPENBANK_APPROVAL_FIXTURE_ACTION',
  'OPENBANK_APPROVAL_FIXTURE_RESOURCE_ID', 'OPENBANK_APPROVAL_FIXTURE_MAKER_ID',
  'OPENBANK_APPROVAL_FIXTURE_CREATED_AT', 'ADMIN_UI_EXPECTED_BUILD_SHA',
  'OPENBANK_APPROVAL_EVIDENCE_FILE',
]
const missing = required.filter(name => !process.env[name]?.trim())
if (missing.length) throw new Error(`authenticated approval probe requires ${missing.join(', ')}`)

const base = new URL(process.env.OPENBANK_APPROVAL_SYNTHETIC_URL)
if (base.protocol !== 'https:' || base.username || base.password || base.search || base.hash) {
  throw new Error('approval probe requires an explicit HTTPS sandbox Admin UI URL without credentials or query')
}
const expectedSha = process.env.ADMIN_UI_EXPECTED_BUILD_SHA.trim().toLowerCase()
if (!/^[0-9a-f]{7,40}$/.test(expectedSha)) throw new Error('ADMIN_UI_EXPECTED_BUILD_SHA must be a git SHA')
const id = process.env.OPENBANK_APPROVAL_FIXTURE_ID
if (!/^[0-9a-f]{8}-[0-9a-f-]{27,}$/.test(id)) throw new Error('approval fixture id must be a UUID')

const statePath = process.env.OPENBANK_APPROVAL_STORAGE_STATE
if (!isAbsolute(statePath)) throw new Error('storage state must be an absolute path')
const state = await realpath(statePath)
const repository = await realpath(new URL('../../', import.meta.url).pathname)
const withinRepo = relative(repository, state)
if (withinRepo === '' || (!withinRepo.startsWith('..') && !isAbsolute(withinRepo))) {
  throw new Error('storage state must stay outside the repository')
}
const stateInfo = await stat(state)
if (!stateInfo.isFile() || (stateInfo.mode & 0o077) !== 0 ||
    (process.getuid && stateInfo.uid !== process.getuid())) {
  throw new Error('storage state must be a current-user, owner-only file')
}

let browser
try {
  browser = await chromium.launch({ headless: true })
  const context = await browser.newContext({ storageState: state })
  const page = await context.newPage()
  // The journey reads a real inbox. Even if the page changes, it must never
  // approve, reject, post a fee, or cause any other mutation while probing.
  await page.route('**/*', route => {
    if (route.request().method() === 'GET' || route.request().method() === 'HEAD') return route.continue()
    return route.abort('blockedbyclient')
  })
  const attestation = await context.request.get(new URL('/.well-known/openbank-build-attestation', base).toString(), { timeout: 10_000 })
  if (!attestation.ok()) throw new Error('deployed build attestation unavailable')
  const attested = await attestation.json()
  const observedSha = typeof attested?.gitSha === 'string' ? attested.gitSha.toLowerCase() : null
  if (typeof observedSha !== 'string' ||
      !(expectedSha.startsWith(observedSha) || observedSha.startsWith(expectedSha))) {
    throw new Error('deployed build does not match requested source')
  }

  const [response] = await Promise.all([
    page.waitForResponse(response =>
      response.url() === new URL('/api/approvals/pending', base).toString(), { timeout: 25_000 }),
    page.goto(new URL('/approvals', base).toString(), { waitUntil: 'domcontentloaded', timeout: 25_000 }),
  ])
  const landed = new URL(page.url())
  if (landed.origin !== base.origin || landed.pathname !== '/approvals') {
    throw new Error('approved operator session did not reach approval inbox')
  }
  if (response.status() !== 200) throw new Error(`approval inbox BFF returned HTTP ${response.status()}`)
  const payload = await response.json()
  const item = verifyApprovalInboxEvidence(payload, {
    id, action: process.env.OPENBANK_APPROVAL_FIXTURE_ACTION,
    resourceId: process.env.OPENBANK_APPROVAL_FIXTURE_RESOURCE_ID,
    makerId: process.env.OPENBANK_APPROVAL_FIXTURE_MAKER_ID,
    createdAt: process.env.OPENBANK_APPROVAL_FIXTURE_CREATED_AT,
  })
  const row = page.getByTestId(`domain-approval-billing:${id}`)
  await row.waitFor({ state: 'visible', timeout: 10_000 })
  if (!await row.getByText(process.env.OPENBANK_APPROVAL_FIXTURE_ACTION, { exact: true }).isVisible()) {
    throw new Error('sandbox fixture was not rendered in the operator inbox')
  }
  const resource = row.getByTestId('approval-resource')
  const maker = row.getByTestId('approval-maker')
  const time = row.locator('time')
  if (!await resource.isVisible() || !(await resource.textContent())?.includes(item.resourceId) ||
      !await maker.isVisible() || !(await maker.textContent())?.includes(item.maker) ||
      !await time.isVisible() || await time.getAttribute('datetime') !== item.proposedAt) {
    throw new Error('provider audit context was not rendered unchanged in the operator inbox')
  }
  await writeApprovalReadEvidence(process.env.OPENBANK_APPROVAL_EVIDENCE_FILE, observedSha, id)
} finally {
  await browser?.close()
}
