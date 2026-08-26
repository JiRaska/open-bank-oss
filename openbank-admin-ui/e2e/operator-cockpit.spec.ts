// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

// Browser coverage for the operator cockpit added in #5905.  The calls below are
// deliberately intercepted at the browser/BFF boundary: each assertion proves a
// real page, its client-side state transitions and its rendered operator copy,
// without pretending a local Playwright run is a regulator, Tempo or Temporal.

import { test, expect, type Page } from '@playwright/test'
import { signInAsOperator } from './helpers/auth'

const PARTY_ID = '05a02ef1-381c-40e7-b73f-d6855eead42e'
const TRACE_ID = '0123456789abcdef0123456789abcdef'

const json = (page: Page, pattern: string | RegExp, body: unknown, status = 200) =>
  page.route(pattern, route => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) }))

test.beforeEach(async ({ context, baseURL }) => {
  await signInAsOperator(context, baseURL!)
})

test('KYC resolves a customer name before loading that customer’s cases', async ({ page }) => {
  const seen: string[] = []
  await page.route('**/api/svc/party-service/api/v1/parties/search**', route => {
    seen.push(route.request().url())
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ data: [{ id: PARTY_ID, legalName: 'Anna Nováková', status: 'ACTIVE', kycStatus: 'VERIFIED' }] }) })
  })
  await page.route('**/api/svc/kyc-service/api/v1/kyc/cases**', route => {
    seen.push(route.request().url())
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([{
      id: 'case-anna-1', partyId: PARTY_ID, status: 'OPEN', reviewedBy: 'reviewer@openbank.test',
      updatedAt: '2026-08-22T08:00:00Z', checks: [{ checkType: 'IDENTITY', status: 'APPROVED' }],
    }]) })
  })

  await page.goto('/kyc')
  await page.getByRole('textbox', { name: /Vyhledat stranu|Search parties/ }).fill('Anna Nováková')
  await page.getByRole('button', { name: /Vyhledat|Search/ }).first().click()
  await page.getByRole('button', { name: /Vybrat|Select/ }).click()

  await expect(page.getByText('case-ann')).toBeVisible()
  expect(seen.some(url => url.includes('/parties/search?q=Anna'))).toBe(true)
  expect(seen.some(url => url.includes(`/cases/party/${PARTY_ID}`))).toBe(true)
})

test('Customer 360 joins authoritative portfolio and documents to the name-selected party', async ({ page }) => {
  await json(page, '**/api/svc/party-service/api/v1/parties/search**', { data: [{ id: PARTY_ID, legalName: 'Anna Nováková', status: 'ACTIVE', kycStatus: 'VERIFIED' }] })
  await json(page, `**/api/customer-360/${PARTY_ID}`, { available: true, partyId: PARTY_ID, asOf: '2026-08-22 08:00:00', partyState: {}, accountIds: ['account-1'], domains: [{ aggregateType: 'party', events: 2, lastEventType: 'PARTY_UPDATED', lastOccurredAt: '2026-08-22 08:00:00' }], consents: [] })
  await json(page, '**/api/svc/account-service/api/v1/accounts**', [{ id: 'account-1', status: 'ACTIVE' }])
  await json(page, '**/api/svc/lending-service/api/v1/lending/applications**', [{ id: 'loan-1', status: 'APPROVED' }])
  await json(page, '**/api/svc/aml-service/api/v1/aml/cases**', [{ id: 'aml-1', status: 'OPEN' }])
  await json(page, '**/api/svc/document-service/api/v1/documents?**', [{ id: 'doc-1', templateCode: 'KYC_CONTRACT', templateVersion: '1', contentType: 'application/pdf', sizeBytes: 1024, status: 'READY', caseRef: 'case-anna-1', productRef: null, retainUntil: '2031-01-01', createdAt: '2026-08-22T08:00:00Z' }])

  await page.goto('/customer-360')
  await page.getByRole('textbox', { name: /Vyhledat stranu|Search parties/ }).fill('Anna Nováková')
  await page.getByRole('button', { name: /Vyhledat|Search/ }).click()
  await page.getByRole('button', { name: /Vybrat|Select/ }).click()
  await expect(page.getByText(/Autoritativní portfolio a riziko|Authoritative portfolio and risk/)).toBeVisible()
  await expect(page.getByText(/Dokumenty klienta|Customer documents/)).toBeVisible()
  await expect(page.getByText('KYC_CONTRACT')).toBeVisible()
})

test('Test Coverage is backed by a dated CI evidence snapshot, not a static score', async ({ page }) => {
  await json(page, '**/api/test-intelligence', {
    schemaVersion: 1, collectedAt: '2026-08-22T08:00:00Z', contracts: [], mutations: [], performance: [], syntheticJourneys: [], clientExperiences: [], history: [], runHistory: [], testCases: [], warnings: [],
    totals: { components: 2, componentsWithExecutionEvidence: 1, moneyPathComponents: 1, failingEvidence: 1, missingEvidence: 1, staleEvidence: 0 },
    components: [
      { component: 'ledger-service', released: true, moneyPath: true, evidence: [{ kind: 'unit', state: 'failed', observedAt: '2026-08-22T08:00:00Z', source: 'Services CI', environment: 'ci', counts: { discovered: 10, executed: 10, passed: 9, failed: 1, skipped: 0, errors: 0 } }], coverage: { state: 'passed', observedAt: '2026-08-22T08:00:00Z', lines: { covered: 90, missed: 10, percentage: 90 }, branches: { covered: 0, missed: 0, percentage: null }, source: 'Kover' }, testInfrastructure: { declared: [], observed: [] } },
      { component: 'account-service', released: true, moneyPath: false, evidence: [], coverage: { state: 'not-run', observedAt: null, lines: { covered: 0, missed: 0, percentage: null }, branches: { covered: 0, missed: 0, percentage: null }, source: null }, testInfrastructure: { declared: [], observed: [] } },
    ],
  })

  await page.goto('/system/tests')
  await expect(page.getByText(/sesbíráno|collected/)).toBeVisible()
  await expect(page.getByText(/absence se nikdy nevykresluje jako nula|absence is never rendered as zero/)).toBeVisible()
  await expect(page.getByText('ledger-service')).toBeVisible()
  await expect(page.getByText('account-service')).toBeVisible()
})

test('Trace Explorer renders an OTLP waterfall after selecting a trace', async ({ page }) => {
  await json(page, '**/api/tempo/api/search**', { traces: [{ traceID: TRACE_ID, rootServiceName: 'ledger-service', rootTraceName: 'POST /entries', durationMs: 1.5 }] })
  await json(page, `**/api/tempo/api/traces/${TRACE_ID}`, { batches: [{
    resource: { attributes: [{ key: 'service.name', value: { stringValue: 'ledger-service' } }] },
    scopeSpans: [{ spans: [{ spanId: 'root-span', name: 'POST /entries', startTimeUnixNano: '1000000', endTimeUnixNano: '2500000' }] }],
  }] })

  await page.goto('/observability/traces')
  await page.getByRole('button', { name: /ledger-service.*POST \/entries/ }).click()
  await expect(page.getByText(/1 spanů|1 spans/)).toBeVisible()
  await expect(page.getByText('POST /entries').last()).toBeVisible()
})

test('compliance pack detail exposes exact reviewed content, not a summary only', async ({ page }) => {
  const pack = {
    id: 'pack-1', jurisdiction: 'CZ', productType: 'CONSUMER_CREDIT', packVersion: 1,
    effectiveFrom: '2026-08-01', contentHash: 'a'.repeat(64), state: 'EXECUTED', proposedBy: 'maker@openbank.test',
    proposedAt: '2026-08-01T08:00:00Z', decidedBy: 'checker@openbank.test', decidedAt: '2026-08-01T09:00:00Z', decisionReason: 'reviewed',
    pack: { jurisdiction: 'CZ', productType: 'CONSUMER_CREDIT', version: 1, coolingOffDays: 14 },
  }
  await json(page, '**/api/svc/lending-service/api/v1/lending/compliance-packs/active', [pack])
  await json(page, '**/api/svc/lending-service/api/v1/lending/compliance-packs/proposals/pending', [])

  await page.goto('/lending/compliance-packs')
  await page.getByRole('button', { name: /Zobrazit detail|View details/ }).click()
  await expect(page.getByText(/Přesný obsah packu|Exact pack content/)).toBeVisible()
  await expect(page.getByText('"coolingOffDays": 14')).toBeVisible()
  await expect(page.getByText('checker@openbank.test')).toBeVisible()
})

test('regulatory preview blocks fiction: it shows real FINREP cells and no submission claim', async ({ page }) => {
  await json(page, '**/api/svc/finrep-service/api/v1/finrep/periods', {
    latest: '2026-06-30', periods: ['2026-06-30'],
  })
  await page.route('**/api/svc/finrep-service/api/v1/finrep/templates/**', route => {
    const path = new URL(route.request().url()).pathname
    const body = path.includes('F01.01')
      ? { templateId: 'F01.01', period: '2026-06-30', isBalanced: true, cells: [{ rowRef: 'r010', colRef: 'c010', value: 1250.5, currency: 'CZK' }] }
      : { templateId: 'F02.00', period: '2026-06-30', isBalanced: true, cells: [{ rowRef: 'r450', colRef: 'c010', value: 42, currency: 'CZK' }] }
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
  })

  await page.goto('/regulatory')
  const finrep = page.locator('.card').filter({ hasText: 'CNB — Finanční výkazy (FINREP)' })
  await finrep.getByRole('button', { name: /Náhled exportu|Preview export/ }).click()
  await expect(page.getByText('Celková aktiva')).toBeVisible()
  await expect(page.getByTestId('export-readiness')).toContainText(/Připraveno pro interní export|Ready for internal export/)
  await expect(page.getByText(/ČNB XBRL\/SDAT přenos nejsou součástí tohoto náhledu|ČNB XBRL\/SDAT transmission are not part of this preview/)).toBeVisible()
})

test('Temporal and approvals show live source-backed operator state and human provenance', async ({ page }) => {
  await json(page, '**/api/temporal/status', {
    available: true, temporalDeployed: true,
    metrics: { workflows: { scheduled1h: 12, completed1h: 11, failed1h: 1, timedOut1h: 0 }, latency: { activityScheduleToStartMs: 20, workflowTaskScheduleToStartMs: 10, serverRequestP99Ms: 30 }, persistence: { requestsPerSec: 4 }, workers: { totalSlotsAvailable: 8, slotsUsed: 2 }, namespaces: ['openbank'] },
  })
  await page.goto('/temporal')
  await expect(page.getByText(/Provozní|Running/)).toBeVisible()
  await page.getByRole('button', { name: /Metriky|Metrics/ }).click()
  await expect(page.getByRole('heading', { name: /Workflowy \(posledních 60 minut\)|Workflows \(last 60 minutes\)/ })).toBeVisible()
  await expect(page.getByLabel(/^(Spuštěno|Scheduled): 12$/)).toBeVisible()

  await json(page, '**/api/agent/proposals?state=all', [{
    id: 'proposal-human', title: 'Human customer correction', rationale: 'verified with customer', suggestedAction: 'party.correct',
    proposedBy: 'alice@openbank.test', proposedAt: '2026-08-22T08:00:00Z', state: 'PROPOSED', decidedBy: null, decidedAt: null, decisionReason: null, modelId: null,
    agent: { id: 'alice@openbank.test', displayName: 'Alice Nováková', icon: 'user', charterKnown: false },
  }])
  await json(page, '**/api/approvals/pending', { items: [], sources: {} })
  await json(page, '**/api/governance/agent-identities', { available: true, agents: [] })
  await page.goto('/approvals')
  await expect(page.getByText('Alice Nováková')).toBeVisible()
  await expect(page.getByText('Human customer correction')).toBeVisible()
  await expect(page.getByText(/Tento návrh vytvořila AI|This proposal was generated by AI/)).toHaveCount(0)
})
