// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

/**
 * One harmless direct-GET address for every dynamic App Router template. IDs copied
 * from E2E fixture constants are marked with their evidence spec; synthetic values
 * are only identifiers and never correspond to live customer records.
 *
 * The route smoke stubs browser API GETs as 404, so it proves URL resolution and
 * the page's settled error/unavailable state without relying on or changing backend
 * data. This is not proof that the referenced record is absent. Populated-state
 * evidence remains the responsibility of the named E2E fixtures.
 */
export type DynamicRouteFixture = {
  path: string
  kind: 'known-fixture-id' | 'synthetic-id' | 'repository-document'
  evidence: string
  existingPopulatedSpec: boolean
}

export const DYNAMIC_ROUTE_FIXTURES: Record<string, DynamicRouteFixture> = {
  '/accounts/[id]': { path: '/accounts/account-42', kind: 'known-fixture-id', evidence: 'party-related-accounts-recovery.spec.ts', existingPopulatedSpec: false },
  '/approvals/delegation/[id]': { path: '/approvals/delegation/018f4a3c-1b2d-7e00-9a11-000000000010', kind: 'known-fixture-id', evidence: 'delegation-approval-evidence.spec.ts', existingPopulatedSpec: true },
  '/approvals/sca/[id]': { path: '/approvals/sca/bde18d9e-3e49-4f4e-98cc-a56646ccbc61', kind: 'known-fixture-id', evidence: 'src/test/operator-approval-workbench.test.tsx (component fixture; render only)', existingPopulatedSpec: false },
  '/approvals/settlement/[id]': { path: '/approvals/settlement/bde18d9e-3e49-4f4e-98cc-a56646ccbc61', kind: 'known-fixture-id', evidence: 'src/test/operator-approval-workbench.test.tsx (component fixture; render only)', existingPopulatedSpec: false },
  '/balance-sheet/curve-sets/[id]': { path: '/balance-sheet/curve-sets/cs-1', kind: 'known-fixture-id', evidence: 'src/test/balance-sheet-pages.test.tsx (component fixture; render only)', existingPopulatedSpec: false },
  '/balance-sheet/snapshots/[id]': { path: '/balance-sheet/snapshots/run-2', kind: 'known-fixture-id', evidence: 'src/test/balance-sheet-pages.test.tsx (component fixture; render only)', existingPopulatedSpec: false },
  '/balance-sheet/snapshots/[id]/capital': { path: '/balance-sheet/snapshots/run-2/capital', kind: 'known-fixture-id', evidence: 'src/test/balance-sheet-pages.test.tsx (component fixture; render only)', existingPopulatedSpec: false },
  '/balance-sheet/snapshots/[id]/irrbb': { path: '/balance-sheet/snapshots/run-2/irrbb', kind: 'known-fixture-id', evidence: 'src/test/balance-sheet-pages.test.tsx (component fixture; render only)', existingPopulatedSpec: false },
  '/balance-sheet/snapshots/[id]/liquidity': { path: '/balance-sheet/snapshots/run-2/liquidity', kind: 'known-fixture-id', evidence: 'src/test/balance-sheet-pages.test.tsx (component fixture; render only)', existingPopulatedSpec: false },
  '/balance-sheet/snapshots/[id]/liquidity-forecast': { path: '/balance-sheet/snapshots/run-2/liquidity-forecast', kind: 'known-fixture-id', evidence: 'src/test/balance-sheet-pages.test.tsx (component fixture; render only)', existingPopulatedSpec: false },
  '/balance-sheet/snapshots/[id]/min-reserves': { path: '/balance-sheet/snapshots/run-2/min-reserves', kind: 'known-fixture-id', evidence: 'src/test/balance-sheet-pages.test.tsx (component fixture; render only)', existingPopulatedSpec: false },
  '/campaigns/[id]': { path: '/campaigns/019fb944-6c3a-7bd2-814d-7c946371f1ae', kind: 'known-fixture-id', evidence: 'campaign-attention-funnel.spec.ts', existingPopulatedSpec: true },
  '/cards/[id]': { path: '/cards/11111111-1111-4111-8111-111111111111', kind: 'known-fixture-id', evidence: 'card-detail-recovery.spec.ts', existingPopulatedSpec: true },
  '/communication/[personaId]': { path: '/communication/branch-assistant', kind: 'known-fixture-id', evidence: 'communication-studio-evidence.spec.ts', existingPopulatedSpec: true },
  '/communication/edit/[personaKey]': { path: '/communication/edit/branch-assistant', kind: 'known-fixture-id', evidence: 'communication-style-editor-evidence.spec.ts', existingPopulatedSpec: true },
  '/delegations/[id]': { path: '/delegations/018f4a3c-1b2d-7e00-9a11-000000000002', kind: 'known-fixture-id', evidence: 'delegations-console.spec.ts', existingPopulatedSpec: true },
  '/docs/adr/[slug]': { path: '/docs/adr/0145-api-deprecation-and-sunset-policy', kind: 'repository-document', evidence: 'docs/adr/0145-api-deprecation-and-sunset-policy.md', existingPopulatedSpec: false },
  '/docs/changelog/[service]': { path: '/docs/changelog/admin-ui', kind: 'repository-document', evidence: 'service key; server-side changelog fallback is route output', existingPopulatedSpec: false },
  '/docs/release-notes/[service]': { path: '/docs/release-notes/admin-ui', kind: 'repository-document', evidence: 'service key; server-side release fallback is route output', existingPopulatedSpec: false },
  '/docs/threat-models/[service]': { path: '/docs/threat-models/sepa-payment', kind: 'repository-document', evidence: 'docs/threat-models/openbank-sepa-payment.md', existingPopulatedSpec: false },
  '/iaops/agents/[agentId]': { path: '/iaops/agents/compliance-officer', kind: 'known-fixture-id', evidence: 'agent-charter-semantics.spec.ts', existingPopulatedSpec: true },
  '/iaops/cases/[caseId]': { path: '/iaops/cases/case-17', kind: 'known-fixture-id', evidence: 'src/test/iaops-case-thread-page.test.tsx (component fixture; render only)', existingPopulatedSpec: false },
  '/iaops/flaky-test-hunter/[id]': { path: '/iaops/flaky-test-hunter/f-200-detail', kind: 'known-fixture-id', evidence: 'flaky-test-hunter-evidence.spec.ts', existingPopulatedSpec: true },
  '/lending/applications/[id]': { path: '/lending/applications/019fb939-3e0a-7716-a1ed-7854754c8786', kind: 'known-fixture-id', evidence: 'src/test/origination-flow.test.tsx (component fixture; render only)', existingPopulatedSpec: false },
  '/parties/[id]': { path: '/parties/05a02ef1-381c-40e7-b73f-d6855eead42e', kind: 'known-fixture-id', evidence: 'party-related-accounts-recovery.spec.ts', existingPopulatedSpec: true },
  '/payments/[id]': { path: '/payments/123e4567-e89b-42d3-a456-426614174000?type=SEPA', kind: 'known-fixture-id', evidence: 'payment-detail-evidence.spec.ts', existingPopulatedSpec: true },
  '/pension/applications/[id]': { path: '/pension/applications/88888888-8888-4888-8888-888888888888', kind: 'known-fixture-id', evidence: 'src/test/pension-operations.test.tsx (component fixture; render only)', existingPopulatedSpec: false },
  '/pension/contracts/[id]': { path: '/pension/contracts/11111111-2222-4333-8444-555555555551', kind: 'known-fixture-id', evidence: 'pension-contract-detail-evidence.spec.ts', existingPopulatedSpec: true },
  '/r/[token]': { path: '/r/x', kind: 'synthetic-id', evidence: 'ReferralLanding invalid-token state (token shorter than 16 characters)', existingPopulatedSpec: false },
  '/services/[name]/docs/[[...slug]]': { path: '/services/libs/docs/01-overview', kind: 'repository-document', evidence: 'openbank-libs/docs/01-overview.en.md', existingPopulatedSpec: false },
  '/settlements/[id]': { path: '/settlements/settlement-test-1', kind: 'synthetic-id', evidence: 'no route-specific record fixture found', existingPopulatedSpec: false },
  '/swift/[id]': { path: '/swift/message-test-1', kind: 'synthetic-id', evidence: 'no route-specific record fixture found', existingPopulatedSpec: false },
  '/treasury/deals/[id]': { path: '/treasury/deals/deal-7', kind: 'known-fixture-id', evidence: 'src/test/approval-queue-evidence.test.tsx (link fixture; render only)', existingPopulatedSpec: false },
}

/** Known not-found/invalid-state examples already asserted in focused specs. */
export const DYNAMIC_UNKNOWN_RECORD_EVIDENCE = [
  { route: '/communication/[personaId]', path: '/communication/no-such-persona', spec: 'communication-studio-evidence.spec.ts' },
  { route: '/iaops/flaky-test-hunter/[id]', path: '/iaops/flaky-test-hunter/f-missing', spec: 'flaky-test-hunter-evidence.spec.ts' },
  { route: '/pension/contracts/[id]', path: '/pension/contracts/not-a-uuid', spec: 'pension-contract-detail-evidence.spec.ts' },
] as const
